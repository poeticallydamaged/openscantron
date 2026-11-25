package com.omrgrader.domain

import com.omrgrader.core.AppError

data class ScoringConfiguration(
    val pointsPerCorrect: Double = 1.0,
    val incorrectDeduction: Double = 0.0,
    val unansweredPoints: Double = 0.0,
    val multipleMarkPolicy: MultipleMarkPolicy = MultipleMarkPolicy.INVALID_AND_REVIEW,
    /** Per-question weights; a missing question defaults to 1.0. */
    val weights: Map<Int, Double> = emptyMap(),
    val partialCredit: Boolean = false,
    val passingThreshold: Double = 60.0,
    val passingThresholdIsPercentage: Boolean = true,
    val clampScoreAtZero: Boolean = false
) {
    init {
        require(pointsPerCorrect > 0) { "pointsPerCorrect must be positive" }
        require(incorrectDeduction >= 0) { "incorrectDeduction must not be negative" }
        require(weights.values.all { it >= 0 }) { "question weights must not be negative" }
    }

    fun weightFor(question: Int): Double = weights[question] ?: 1.0

    fun maxScore(totalQuestions: Int): Double =
        (1..totalQuestions).sumOf { weightFor(it) * pointsPerCorrect }
}

data class ItemGrade(
    val questionNumber: Int,
    val finalAnswer: String,
    val keyAnswer: String,
    val outcome: ItemOutcome,
    val points: Double,
    val weight: Double,
    val requiresReview: Boolean
)

data class GradingResult(
    val items: List<ItemGrade>,
    val correctCount: Int,
    val incorrectCount: Int,
    val blankCount: Int,
    val invalidCount: Int,
    val pendingReviewCount: Int,
    val partialCreditCount: Int,
    val score: Double,
    val maxScore: Double,
    val percentage: Double,
    val passingStatus: String,
    val hasPendingItems: Boolean,
    val warnings: List<String>
)

/** Pure grading engine. It never mutates its inputs and never treats a pending item as correct. */
object GradingEngine {
    const val PASS = "PASS"
    const val FAIL = "FAIL"
    const val PENDING = "PENDING_REVIEW"

    fun grade(
        key: AnswerKey,
        recognitions: List<QuestionRecognition>,
        totalQuestions: Int,
        config: ScoringConfiguration = key.scoringRules
    ): GradingResult {
        if (totalQuestions <= 0) throw AppError.Validation("totalQuestions must be positive")
        val byNumber = recognitions.associateBy { it.questionNumber }
        val items = ArrayList<ItemGrade>(totalQuestions)
        val warnings = ArrayList<String>()

        for (q in 1..totalQuestions) {
            val rec = byNumber[q]
            val finalAnswer = rec?.finalAnswer ?: CellValue.BLANK
            val keyAnswer = key.answers[q] ?: ""
            val weight = config.weightFor(q)
            val outcome: ItemOutcome
            val points: Double
            val needsReview: Boolean

            when {
                keyAnswer.isBlank() -> {
                    outcome = ItemOutcome.PENDING_REVIEW; points = 0.0; needsReview = true
                    warnings.add("missing_key_for_item")
                }
                finalAnswer == CellValue.PENDING_REVIEW || finalAnswer.isEmpty() -> {
                    outcome = ItemOutcome.PENDING_REVIEW; points = 0.0; needsReview = true
                }
                finalAnswer == CellValue.BLANK -> {
                    outcome = ItemOutcome.UNANSWERED
                    points = config.unansweredPoints * weight
                    needsReview = false
                }
                finalAnswer == CellValue.MULTIPLE || finalAnswer == CellValue.AMBIGUOUS ||
                    finalAnswer == CellValue.INVALID -> {
                    val allowPartial = (config.partialCredit ||
                        config.multipleMarkPolicy == MultipleMarkPolicy.PARTIAL_CREDIT) &&
                        keyAnswer in (rec?.alternatives ?: emptyList())
                    when {
                        allowPartial && rec != null && rec.alternatives.isNotEmpty() -> {
                            outcome = ItemOutcome.PARTIAL_CREDIT
                            points = weight * config.pointsPerCorrect / rec.alternatives.size
                            needsReview = false
                        }
                        config.multipleMarkPolicy == MultipleMarkPolicy.AWARD_ZERO -> {
                            outcome = ItemOutcome.INCORRECT
                            points = -config.incorrectDeduction * weight
                            needsReview = false
                        }
                        else -> {
                            outcome = ItemOutcome.INVALID
                            points = 0.0
                            needsReview = true
                        }
                    }
                }
                finalAnswer == keyAnswer -> {
                    outcome = ItemOutcome.CORRECT
                    points = weight * config.pointsPerCorrect
                    needsReview = false
                }
                else -> {
                    outcome = ItemOutcome.INCORRECT
                    points = -config.incorrectDeduction * weight
                    needsReview = false
                }
            }
            items += ItemGrade(q, finalAnswer, keyAnswer, outcome, points, weight, needsReview)
        }

        val maxScore = config.maxScore(totalQuestions)
        val rawScore = items.sumOf { it.points }
        val score = if (config.clampScoreAtZero) rawScore.coerceAtLeast(0.0) else rawScore
        // Guard against division by zero and against degenerate (zero-weight) exams.
        val percentage = if (maxScore > 1e-9) (score / maxScore * 100.0).coerceIn(-100.0, 100.0) else 0.0
        if (maxScore <= 1e-9) warnings.add("max_score_zero_percentage_forced_to_zero")

        val pending = items.count { it.outcome == ItemOutcome.PENDING_REVIEW || it.requiresReview }
        val passingStatus = when {
            pending > 0 -> PENDING
            if (config.passingThresholdIsPercentage) percentage >= config.passingThreshold
            else score >= config.passingThreshold -> PASS
            else -> FAIL
        }
        return GradingResult(
            items = items,
            correctCount = items.count { it.outcome == ItemOutcome.CORRECT },
            incorrectCount = items.count { it.outcome == ItemOutcome.INCORRECT },
            blankCount = items.count { it.outcome == ItemOutcome.UNANSWERED },
            invalidCount = items.count { it.outcome == ItemOutcome.INVALID },
            pendingReviewCount = pending,
            partialCreditCount = items.count { it.outcome == ItemOutcome.PARTIAL_CREDIT },
            score = score,
            maxScore = maxScore,
            percentage = percentage,
            passingStatus = passingStatus,
            hasPendingItems = pending > 0,
            warnings = warnings.distinct()
        )
    }

    /** Provisional grade: pending items are reported, never guessed. */
    fun statusFor(result: GradingResult): GradingStatus = when {
        result.hasPendingItems -> GradingStatus.PENDING_REVIEW
        else -> GradingStatus.GRADED
    }
}
