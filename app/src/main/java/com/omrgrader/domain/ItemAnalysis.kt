package com.omrgrader.domain

/** KPI and per-item statistics. Difficulty index = correct / (correct + incorrect). */
data class ExamStatistics(
    val submissionCount: Int,
    val maxPoints: Double,
    val mean: Double,
    val median: Double,
    val high: Double,
    val low: Double,
    val passCount: Int,
    val failCount: Int,
    val pendingCount: Int,
    val passPercentage: Double,
    val failPercentage: Double,
    val archivedPdfCount: Int,
    val syncedCsvCount: Int,
    val meanPercentage: Double
)

data class ItemStatistics(
    val questionNumber: Int,
    val correctPercentage: Double,
    val incorrectPercentage: Double,
    val blankPercentage: Double,
    val invalidPercentage: Double,
    val pendingPercentage: Double,
    val distractorFrequency: Map<String, Int>,
    val mostChosenDistractor: String?,
    /** Classical difficulty index p = correct / attempted. Null when no attempts. */
    val difficultyIndex: Double?,
    val keyAnswer: String
)

object ItemAnalyzer {
    fun statistics(
        results: List<GradingResult>,
        archivedPdfCount: Int = 0,
        syncedCsvCount: Int = 0
    ): ExamStatistics {
        val scores = results.map { it.score }.sorted()
        val n = scores.size
        val mean = if (n == 0) 0.0 else scores.average()
        val median = when {
            n == 0 -> 0.0
            n % 2 == 1 -> scores[n / 2]
            else -> (scores[n / 2 - 1] + scores[n / 2]) / 2.0
        }
        val pass = results.count { it.passingStatus == GradingEngine.PASS }
        val fail = results.count { it.passingStatus == GradingEngine.FAIL }
        val pending = results.count { it.passingStatus == GradingEngine.PENDING }
        val maxPoints = results.firstOrNull()?.maxScore ?: 0.0
        return ExamStatistics(
            submissionCount = n,
            maxPoints = maxPoints,
            mean = mean,
            median = median,
            high = scores.lastOrNull() ?: 0.0,
            low = scores.firstOrNull() ?: 0.0,
            passCount = pass,
            failCount = fail,
            pendingCount = pending,
            passPercentage = percent(pass, n),
            failPercentage = percent(fail, n),
            archivedPdfCount = archivedPdfCount,
            syncedCsvCount = syncedCsvCount,
            meanPercentage = if (maxPoints > 1e-9) mean / maxPoints * 100.0 else 0.0
        )
    }

    fun perItem(results: List<GradingResult>, key: AnswerKey): List<ItemStatistics> {
        if (results.isEmpty()) return emptyList()
        val count = results.first().items.size
        return (1..count).map { q ->
            val items = results.mapNotNull { it.items.getOrNull(q - 1) }
            val distractors = LinkedHashMap<String, Int>()
            items.filter { it.outcome == ItemOutcome.INCORRECT }
                .forEach { distractors[it.finalAnswer] = (distractors[it.finalAnswer] ?: 0) + 1 }
            val attempted = items.count { it.outcome == ItemOutcome.CORRECT || it.outcome == ItemOutcome.INCORRECT }
            val correct = items.count { it.outcome == ItemOutcome.CORRECT }
            ItemStatistics(
                questionNumber = q,
                correctPercentage = percent(correct, items.size),
                incorrectPercentage = percent(attempted - correct, items.size),
                blankPercentage = percent(items.count { it.outcome == ItemOutcome.UNANSWERED }, items.size),
                invalidPercentage = percent(items.count { it.outcome == ItemOutcome.INVALID }, items.size),
                pendingPercentage = percent(items.count { it.outcome == ItemOutcome.PENDING_REVIEW }, items.size),
                distractorFrequency = distractors,
                mostChosenDistractor = distractors.maxByOrNull { it.value }?.key,
                difficultyIndex = if (attempted == 0) null else correct.toDouble() / attempted,
                keyAnswer = key.answers[q] ?: ""
            )
        }
    }

    private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else part * 100.0 / total
}
