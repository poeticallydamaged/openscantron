package com.omrgrader.domain

import com.omrgrader.core.AppError
import com.omrgrader.core.Ids

object ExamPresets {
    val questionCounts: List<Int> = listOf(10, 20, 30, 40, 50, 60, 75, 100)
    val choiceSets: List<List<String>> = listOf(
        listOf("A", "B", "C", "D"),
        listOf("A", "B", "C", "D", "E"),
        listOf("A", "B", "C", "D", "E", "F")
    )
    fun isCustom(count: Int): Boolean = count !in questionCounts
}

/** Examination administration as pure transformations; the Room repository persists the results. */
object ExamAdmin {
    data class ExamCreation(
        val examination: Examination,
        val datasetTypes: List<DatasetType> = DatasetType.entries
    )

    fun create(
        title: String,
        subject: String,
        description: String,
        date: Long,
        totalQuestions: Int,
        choices: List<String>,
        scoring: ScoringConfiguration,
        now: Long,
        id: String = Ids.newId("exam")
    ): ExamCreation {
        if (title.isBlank()) throw AppError.Validation("examination title is required")
        if (totalQuestions !in 1..200) throw AppError.Validation("question count must be 1..200")
        if (choices.isEmpty() || choices.size > 6 || choices.distinct().size != choices.size ||
            choices.any { it.length != 1 }) {
            throw AppError.Validation("answer choices must be 1..6 unique single-character labels")
        }
        val exam = Examination(
            id = id,
            title = title.trim(),
            subject = subject.trim(),
            description = description.trim(),
            date = date,
            totalQuestions = totalQuestions,
            totalPoints = scoring.maxScore(totalQuestions),
            scoringConfiguration = scoring,
            isArchived = false,
            createdAt = now,
            updatedAt = now
        )
        return ExamCreation(exam)
    }

    fun edit(exam: Examination, changes: Examination.() -> Examination, now: Long): Examination =
        changes(exam).copy(updatedAt = now)

    fun duplicate(exam: Examination, now: Long, newId: String = Ids.newId("exam")): Examination =
        exam.copy(
            id = newId,
            title = exam.title + " (copy)",
            activeTemplateId = null,
            activeAnswerKeyVersionId = null,
            isArchived = false,
            createdAt = now,
            updatedAt = now
        )

    fun archive(exam: Examination, now: Long): Examination =
        exam.copy(isArchived = true, updatedAt = now)

    fun restore(exam: Examination, now: Long): Examination =
        exam.copy(isArchived = false, updatedAt = now)
}

/** Numbered, versioned answer keys. An active key is never mutated after grading has begun. */
object AnswerKeyVersionService {
    data class KeyCreation(val key: AnswerKey, val missing: List<Int>)

    data class VersionEditResult(
        val newKey: AnswerKey,
        val examination: Examination,
        val regradeRequired: Boolean,
        val affectedSubmissionIds: List<String>,
        val regradeReason: String?
    )

    fun validate(answers: Map<Int, String>, totalQuestions: Int, choices: List<String>): List<Int> =
        (1..totalQuestions).filter { q ->
            val value = answers[q]
            value.isNullOrBlank() || value !in choices
        }

    fun create(
        exam: Examination,
        answers: Map<Int, String>,
        choices: List<String>,
        existing: List<AnswerKey>,
        now: Long,
        id: String = Ids.newId("key")
    ): KeyCreation {
        val missing = validate(answers, exam.totalQuestions, choices)
        if (missing.isNotEmpty()) throw AppError.Validation("answer key has missing/invalid items: ${missing.size}")
        val version = (existing.maxOfOrNull { it.version } ?: 0) + 1
        return KeyCreation(
            key = AnswerKey(
                id = id,
                examinationId = exam.id,
                version = version,
                answers = answers.filterKeys { it in 1..exam.totalQuestions },
                scoringRules = exam.scoringConfiguration,
                createdAt = now
            ),
            missing = missing
        )
    }

    fun activate(exam: Examination, key: AnswerKey, now: Long): Examination =
        exam.copy(activeAnswerKeyVersionId = key.id, updatedAt = now)

    /**
     * Editing an active key creates a new immutable version. If any submission was graded
     * against the old version, an explicit regrade request is emitted; historical scores are
     * left untouched until the teacher confirms the regrade workflow.
     */
    fun editActiveKey(
        exam: Examination,
        activeKey: AnswerKey,
        newAnswers: Map<Int, String>,
        choices: List<String>,
        existingKeys: List<AnswerKey>,
        gradedSubmissionIds: List<String>,
        now: Long,
        id: String = Ids.newId("key")
    ): VersionEditResult {
        val created = create(exam, newAnswers, choices, existingKeys, now, id).key
        return VersionEditResult(
            newKey = created,
            examination = activate(exam, created, now),
            regradeRequired = gradedSubmissionIds.isNotEmpty(),
            affectedSubmissionIds = gradedSubmissionIds.distinct(),
            regradeReason = if (gradedSubmissionIds.isEmpty()) null
            else "ACTIVE_KEY_CHANGED_AFTER_GRADING"
        )
    }
}

enum class DuplicateResolution { REPLACE, RETAIN, DISCARD }

data class DuplicateMatch(
    val existingSubmissionId: String,
    val reason: String,
    val byStudent: Boolean,
    val byFingerprint: Boolean
)

object DuplicateDetector {
    fun find(
        existing: List<Submission>,
        examId: String,
        studentId: String?,
        fingerprint: String
    ): DuplicateMatch? {
        val candidates = existing.filter { it.examinationId == examId && it.gradingStatus != GradingStatus.DISCARDED }
        val match = candidates.firstOrNull { sub ->
            (studentId != null && sub.studentId == studentId) || sub.imageFingerprint == fingerprint
        } ?: return null
        return DuplicateMatch(
            existingSubmissionId = match.id,
            reason = if (studentId != null && match.studentId == studentId) "SAME_STUDENT_IN_EXAM" else "SAME_IMAGE_FINGERPRINT",
            byStudent = studentId != null && match.studentId == studentId,
            byFingerprint = match.imageFingerprint == fingerprint
        )
    }

    /**
     * REPLACE is the only resolution that changes an existing record; it requires explicit
     * consent and the ID of the record the teacher chose to replace (never inferred from
     * the incoming submission, which has a different ID).
     */
    fun apply(
        resolution: DuplicateResolution,
        existing: List<Submission>,
        incoming: Submission,
        existingIdToReplace: String
    ): List<Submission> = when (resolution) {
        DuplicateResolution.REPLACE -> existing.filterNot { it.id == existingIdToReplace } + incoming
        DuplicateResolution.RETAIN -> existing + incoming
        DuplicateResolution.DISCARD -> existing
    }
}

data class BatchCounters(
    val scanned: Int = 0,
    val identified: Int = 0,
    val unrecognized: Int = 0,
    val needsReview: Int = 0,
    val failed: Int = 0,
    val confirmed: Int = 0,
    val pdfsArchived: Int = 0,
    val csvSynced: Int = 0,
    val pending: Int = 0
) {
    operator fun plus(other: BatchCounters) = BatchCounters(
        scanned + other.scanned, identified + other.identified,
        unrecognized + other.unrecognized, needsReview + other.needsReview,
        failed + other.failed, confirmed + other.confirmed,
        pdfsArchived + other.pdfsArchived, csvSynced + other.csvSynced,
        pending + other.pending
    )

    companion object {
        val CAPTURED = BatchCounters(scanned = 1, pending = 1)
        val IDENTIFIED = BatchCounters(identified = 1, pending = -1)
        val UNRECOGNIZED = BatchCounters(unrecognized = 1, pending = -1)
        val REVIEW = BatchCounters(needsReview = 1, pending = -1)
        val FAILED = BatchCounters(failed = 1, pending = -1)
        val CONFIRMED = BatchCounters(confirmed = 1, pending = -1)
        val ARCHIVED = BatchCounters(pdfsArchived = 1)
        val CSV_SYNCED = BatchCounters(csvSynced = 1)
    }
}
