package com.omrgrader.domain

/** Configurable thresholds persisted in DataStore and applied by both OMR and verification UI. */
data class OmrThresholds(
    val blankFillRatio: Double = 0.20,
    val markFillRatio: Double = 0.46,
    val minimumMargin: Double = 0.10,
    val ambiguousMargin: Double = 0.16,
    val relativeContrastMinimum: Double = 0.12,
    val minimumRegistrationScore: Double = 0.60,
    val minimumAlignmentScore: Double = 0.55,
    val reviewConfidenceBelow: Double = 0.72,
    val minimumLaplacianVariance: Double = 45.0,
    val minimumShortSidePixels: Int = 720,
    val studentIdColumns: Int = 8,
    val maxDarknessRatio: Double = 0.35
) {
    init {
        require(blankFillRatio in 0.0..1.0)
        require(markFillRatio in blankFillRatio..1.0)
        require(minimumMargin >= 0.0)
        require(reviewConfidenceBelow in 0.0..1.0)
    }
}

data class VerificationFlag(
    val questionNumber: Int,
    val reason: String,
    val confidence: Double
)

object ConfidencePolicy {
    fun needsReview(
        recognition: QuestionRecognition,
        thresholds: OmrThresholds
    ): Boolean = when (recognition.classification) {
        Classification.SINGLE_MARK -> recognition.confidence < thresholds.reviewConfidenceBelow ||
            CellValue.isPending(recognition.finalAnswer)
        Classification.BLANK -> recognition.confidence < thresholds.reviewConfidenceBelow
        else -> true
    }

    fun flagsFor(
        recognitions: List<QuestionRecognition>,
        thresholds: OmrThresholds
    ): List<VerificationFlag> = recognitions.filter { needsReview(it, thresholds) }.map {
        VerificationFlag(
            questionNumber = it.questionNumber,
            reason = when (it.classification) {
                Classification.SINGLE_MARK -> "LOW_CONFIDENCE"
                Classification.BLANK -> "LOW_CONFIDENCE_BLANK"
                Classification.MULTIPLE_MARKS -> "MULTIPLE_MARKS"
                Classification.AMBIGUOUS -> "AMBIGUOUS_MARK"
                Classification.INVALID -> "INVALID_ROI"
            },
            confidence = it.confidence
        )
    }

    /** Bounded monotone confidence from margin/separation; never returns a fabricated constant. */
    fun fromMargins(topFill: Double, secondFill: Double, contrast: Double, thresholds: OmrThresholds): Double {
        val separation = (topFill - secondFill).coerceAtLeast(0.0)
        val thresholdDistance = (topFill - thresholds.markFillRatio).coerceAtLeast(0.0)
        val raw = 0.42 + 2.0 * separation + 1.4 * thresholdDistance + 0.8 * contrast.coerceAtLeast(0.0)
        return raw.coerceIn(0.0, 1.0)
    }
}
