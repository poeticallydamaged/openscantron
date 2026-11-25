package com.omrgrader.omr

import com.omrgrader.core.OmrResult
import com.omrgrader.domain.Classification
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.export.PortableQrCodec
import com.omrgrader.export.QrCodec
import kotlin.math.max
import kotlin.math.min

data class CalibrationReport(
    val thresholds: OmrThresholds,
    val samples: Int,
    val blankFillMax: Double,
    val markedFillMin: Double,
    val estimatedSeparation: Double
)

/**
 * Data-driven threshold calibration from real captured (or synthetic) sheets.
 * Runs the full pipeline once per calibration sheet, collects ROI fill statistics,
 * and chooses the midpoint between the darkest blank and lightest confident mark.
 * No thresholds are hardcoded from a demo dataset.
 */
object ThresholdCalibration {
    fun recommend(
        cases: List<SyntheticCase>,
        layoutRegistry: LayoutRegistry,
        roster: RosterLookup,
        imageOps: ImageOps = PortableImageOps,
        qrCodec: QrCodec = PortableQrCodec,
        base: OmrThresholds = OmrThresholds()
    ): CalibrationReport {
        val blankFills = ArrayList<Double>()
        val markedFills = ArrayList<Double>()
        val engine = OmrEngine(OmrEngineConfig(imageOps, qrCodec, base))
        for (case in cases) {
            val outcome = engine.process(OmrRequest(case.image, layoutRegistry, roster))
            if (outcome !is OmrResult.Success) continue
            val result = outcome.value
            val readings = BubbleAnalyzer.analyze(result.canonicalImage, result.canonicalRaw,
                result.canonicalBinary, result.layout, base)
            readings.forEach { reading ->
                val expected = case.expectedClassification[reading.questionNumber] ?: return@forEach
                when (expected) {
                    Classification.BLANK -> blankFills += reading.topFill
                    Classification.SINGLE_MARK -> markedFills += reading.topFill
                    else -> Unit
                }
            }
        }
        if (blankFills.isEmpty() || markedFills.isEmpty())
            return CalibrationReport(base, cases.size, 0.0, 0.0, 0.0)
        val blankMax = blankFills.max()
        val markMin = markedFills.min()
        val midpoint = (blankMax + markMin) / 2.0
        val markRatio = midpoint.coerceIn(0.30, 0.62)
        val blankRatio = (blankMax * 1.25).coerceIn(0.10, markRatio - 0.08)
        val separation = markMin - blankMax
        return CalibrationReport(
            thresholds = base.copy(
                blankFillRatio = blankRatio,
                markFillRatio = markRatio,
                minimumMargin = max(0.06, separation * 0.45),
                ambiguousMargin = max(0.10, separation * 0.65),
                relativeContrastMinimum = min(0.18, max(0.06, separation * 0.35))
            ),
            samples = cases.size,
            blankFillMax = blankMax,
            markedFillMin = markMin,
            estimatedSeparation = separation
        )
    }
}
