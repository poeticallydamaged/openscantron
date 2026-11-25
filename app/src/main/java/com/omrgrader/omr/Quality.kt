package com.omrgrader.omr

import com.omrgrader.domain.OmrThresholds
import kotlin.math.abs

data class QualityMetrics(
    val laplacianVariance: Double,
    val meanBrightness: Double,
    val darkRatio: Double,
    val minSidePixels: Int,
    val markerCount: Int,
    val skewDegrees: Double,
    val shadowContrast: Double
)

data class QualityReport(
    val metrics: QualityMetrics,
    val warnings: List<String>,
    /** True when all hard pre-capture gates pass; advisory warnings may remain. */
    val passed: Boolean
)

object ImageQualityGate {
    fun evaluate(
        original: GrayImage,
        processed: GrayImage,
        imageOps: ImageOps,
        markerCount: Int,
        skewDegrees: Double,
        thresholds: OmrThresholds
    ): QualityReport {
        val warnings = ArrayList<String>()
        val variance = imageOps.laplacianVariance(processed)
        val brightness = imageOps.brightness(original)
        val quadrant = quadrantContrast(original)
        if (variance < thresholds.minimumLaplacianVariance) warnings += "BLURRY_IMAGE"
        if (minOf(original.width, original.height) < thresholds.minimumShortSidePixels) warnings += "LOW_RESOLUTION"
        if (brightness.mean < 85 || brightness.mean > 253) warnings += "POOR_LIGHTING"
        if (quadrant > 30.0) warnings += "UNEVEN_LIGHTING_OR_SHADOW"
        if (markerCount < 4) warnings += "INCOMPLETE_SHEET_VISIBILITY"
        if (abs(skewDegrees) > 12.0) warnings += "EXCESSIVE_ROTATION_OR_SKEW"
        if (brightness.darkRatio > thresholds.maxDarknessRatio) warnings += "EXCESSIVE_DARK_AREA"
        val critical = setOf("BLURRY_IMAGE", "LOW_RESOLUTION", "INCOMPLETE_SHEET_VISIBILITY",
            "EXCESSIVE_ROTATION_OR_SKEW")
        return QualityReport(
            QualityMetrics(
                laplacianVariance = variance,
                meanBrightness = brightness.mean,
                darkRatio = brightness.darkRatio,
                minSidePixels = minOf(original.width, original.height),
                markerCount = markerCount,
                skewDegrees = skewDegrees,
                shadowContrast = quadrant
            ),
            warnings = warnings.distinct(),
            passed = warnings.none { it in critical }
        )
    }

    private fun quadrantContrast(image: GrayImage): Double {
        val hw = image.width / 2; val hh = image.height / 2
        fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0L; var n = 0
            for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) { sum += image.at(x, y); n++ }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }
        val values = listOf(
            mean(0, 0, hw, hh), mean(hw, 0, image.width, hh),
            mean(0, hh, hw, image.height), mean(hw, hh, image.width, image.height)
        )
        return values.max() - values.min()
    }
}
