package com.omrgrader.omr

import com.omrgrader.core.AppError
import com.omrgrader.domain.CellValue
import com.omrgrader.domain.Classification
import com.omrgrader.domain.ConfidencePolicy
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.domain.QuestionRecognition
import com.omrgrader.core.Ids
import kotlin.math.max
import kotlin.math.min

data class BubbleReading(
    val questionNumber: Int,
    val classification: Classification,
    val answer: String,
    val alternatives: List<String>,
    val confidence: Double,
    val fills: List<Double>,
    val baseline: Double,
    val topFill: Double,
    val margin: Double
)

/** Stage 5 + 6: local-baseline ROI analysis and five-state classification. */
object BubbleAnalyzer {
    fun analyze(
        gray: GrayImage,
        rawGray: GrayImage,
        binary: GrayImage,
        layout: SheetLayout,
        thresholds: OmrThresholds
    ): List<BubbleReading> {
        val byQuestion = layout.bubbles.groupBy { it.question }.toSortedMap()
        // Global unmarked baseline: median of each question's darkest choice.
        val questionMin = byQuestion.values.map { specs ->
            specs.minOf { combinedFill(fillStats(gray, rawGray, binary, layout, it)) }
        }.sorted()
        val globalBaseline = if (questionMin.isEmpty()) 0.0
        else if (questionMin.size % 2 == 1) questionMin[questionMin.size / 2]
        else (questionMin[questionMin.size / 2 - 1] + questionMin[questionMin.size / 2]) / 2.0

        return byQuestion.map { (q, specs) ->
            val stats = specs.map { fillStats(gray, rawGray, binary, layout, it) }
            val fills = stats.map { combinedFill(it) }
            val localMin = fills.minOrNull() ?: 0.0
            val baseline = min(globalBaseline, localMin)
            val sorted = fills.withIndex().sortedByDescending { it.value }
            val top = sorted[0]
            val second = sorted.getOrNull(1)?.value ?: 0.0
            val contrast = max(0.0, top.value - baseline)
            val candidates = fills.filter { it >= thresholds.markFillRatio && it - baseline >= thresholds.relativeContrastMinimum }
            val margin = top.value - second
            val classification: Classification
            val answer: String
            val alternatives: List<String>
            val confidence: Double
            when {
                fills.any { it.isNaN() } -> {
                    classification = Classification.INVALID
                    answer = CellValue.INVALID; alternatives = emptyList(); confidence = 0.0
                }
                candidates.size >= 2 -> {
                    classification = Classification.MULTIPLE_MARKS
                    answer = CellValue.MULTIPLE
                    alternatives = fills.withIndex().filter { candidates.contains(it.value) }.map { specs[it.index].label }
                    confidence = ConfidencePolicy.fromMargins(top.value, second, contrast, thresholds).coerceAtMost(0.85)
                }
                candidates.size == 1 && margin < thresholds.ambiguousMargin -> {
                    classification = Classification.AMBIGUOUS
                    answer = CellValue.AMBIGUOUS
                    alternatives = listOf(specs[top.index].label)
                    confidence = (margin / thresholds.ambiguousMargin).coerceIn(0.0, 1.0) * 0.65
                }
                candidates.size == 1 && stats[top.index].rawIntensity < 0.58 -> {
                    // Light pencil, partial shading or erased graphite: the mark is real
                    // enough to propose a candidate but too weak for an automatic grade.
                    classification = Classification.AMBIGUOUS
                    answer = CellValue.AMBIGUOUS
                    alternatives = listOf(specs[top.index].label)
                    confidence = (stats[top.index].rawIntensity / 0.58).coerceIn(0.0, 1.0) * 0.55
                }
                candidates.size == 1 -> {
                    classification = Classification.SINGLE_MARK
                    answer = specs[top.index].label
                    alternatives = listOf(answer)
                    confidence = ConfidencePolicy.fromMargins(top.value, second, contrast, thresholds)
                }
                top.value > thresholds.blankFillRatio + 0.04 -> {
                    classification = Classification.AMBIGUOUS
                    answer = CellValue.AMBIGUOUS
                    alternatives = listOf(specs.getOrNull(top.index)?.label ?: "")
                    confidence = (1.0 - (top.value - thresholds.blankFillRatio)).coerceIn(0.0, 1.0) * 0.6
                }
                else -> {
                    classification = Classification.BLANK
                    answer = CellValue.BLANK
                    alternatives = emptyList()
                    confidence = (0.80 + 0.20 *
                        (1.0 - top.value / thresholds.blankFillRatio.coerceAtLeast(1e-6))
                    ).coerceIn(0.0, 1.0)
                }
            }
            BubbleReading(q, classification, answer, alternatives, confidence, fills, baseline, top.value, margin)
        }
    }

    private data class FillStats(val coverage: Double, val intensity: Double, val rawIntensity: Double)

    private fun combinedFill(s: FillStats): Double =
        (0.72 * s.coverage + 0.28 * s.intensity).coerceIn(0.0, 1.0)

    private fun fillStats(
        gray: GrayImage,
        rawGray: GrayImage,
        binary: GrayImage,
        layout: SheetLayout,
        spec: BubbleSpec
    ): FillStats {
        val cx = layout.canonicalX(spec.x); val cy = layout.canonicalY(spec.y)
        val r = spec.radiusPx * 0.66
        if (cx - r < 0 || cy - r < 0 || cx + r >= gray.width || cy + r >= gray.height)
            return FillStats(Double.NaN, Double.NaN, Double.NaN)
        var dark = 0; var total = 0; var sum = 0L; var rawSum = 0L
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy > r * r) continue
            total++
            sum += gray.at(x, y)
            rawSum += rawGray.at(x, y)
            if (binary.at(x, y) < 128) dark++
        }
        if (total == 0) return FillStats(Double.NaN, Double.NaN, Double.NaN)
        return FillStats(
            coverage = dark.toDouble() / total,
            intensity = 1.0 - (sum.toDouble() / total) / 255.0,
            rawIntensity = 1.0 - (rawSum.toDouble() / total) / 255.0
        )
    }

    fun toRecognitions(readings: List<BubbleReading>, submissionId: String): List<QuestionRecognition> =
        readings.map {
            QuestionRecognition(
                id = Ids.newId("qr"),
                submissionId = submissionId,
                questionNumber = it.questionNumber,
                recognizedAnswer = it.answer,
                finalAnswer = it.answer,
                confidence = it.confidence,
                classification = it.classification,
                alternatives = it.alternatives
            )
        }
}
