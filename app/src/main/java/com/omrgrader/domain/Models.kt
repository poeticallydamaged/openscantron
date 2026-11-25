package com.omrgrader.domain

/** Domain models: pure Kotlin, no Android/Room/Compose imports. Room entities mirror these. */

enum class PageSize { A4, LETTER }

enum class Classification { BLANK, SINGLE_MARK, MULTIPLE_MARKS, AMBIGUOUS, INVALID }

enum class GradingStatus { PENDING_REVIEW, PROVISIONAL, GRADED, REGRADE_REQUIRED, DISCARDED }

enum class ProcessingStatus { CAPTURED, PREPROCESSING, REGISTERED, RECOGNIZED, VERIFIED, FAILED, ARCHIVED }

enum class GenerationStatus { PENDING, GENERATED, FAILED, RETRY_QUEUED }

enum class SynchronizationStatus { INITIALIZED, SYNCING, SYNCED, STALE, FAILED }

enum class DatasetType { WIDE_ANSWERS, RECOGNITION_AUDIT }

enum class BatchStatus { ACTIVE, PAUSED, COMPLETED, RECOVERABLE }

enum class ItemOutcome { CORRECT, INCORRECT, UNANSWERED, INVALID, PARTIAL_CREDIT, PENDING_REVIEW }

enum class MultipleMarkPolicy { INVALID_AND_REVIEW, AWARD_ZERO, PARTIAL_CREDIT }

enum class IdentificationMode { BUBBLE_GRID, QR_CODE, MANUAL }

data class Examination(
    val id: String,
    val title: String,
    val subject: String,
    val description: String = "",
    val date: Long,
    val totalQuestions: Int,
    val totalPoints: Double,
    val scoringConfiguration: ScoringConfiguration,
    val activeTemplateId: String? = null,
    val activeAnswerKeyVersionId: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

data class Student(
    val id: String,
    val studentNumber: String,
    val fullName: String,
    val section: String = "",
    val additionalInformation: String = ""
)

data class AnswerSheetTemplate(
    val id: String,
    val templateCode: String,
    val examinationId: String,
    val pageSize: PageSize,
    val questionCount: Int,
    val answerChoices: List<String>,
    val layoutDefinition: String,
    val version: Int,
    val createdAt: Long
)

data class AnswerKey(
    val id: String,
    val examinationId: String,
    val version: Int,
    val answers: Map<Int, String>,
    val scoringRules: ScoringConfiguration,
    val createdAt: Long
) {
    fun missingQuestions(totalQuestions: Int): List<Int> =
        (1..totalQuestions).filter { answers[it].isNullOrBlank() }

    fun isComplete(totalQuestions: Int, choices: List<String>): Boolean =
        missingQuestions(totalQuestions).isEmpty() && answers.values.all { it in choices }
}

data class Submission(
    val id: String,
    val examinationId: String,
    val studentId: String?,
    val templateId: String,
    val answerKeyVersionId: String,
    val batchId: String,
    val recognizedAnswers: Map<Int, String>,
    val finalAnswers: Map<Int, String>,
    val score: Double?,
    val percentage: Double?,
    val gradingStatus: GradingStatus,
    val processingStatus: ProcessingStatus,
    val imageFingerprint: String,
    val scannedAt: Long
)

data class QuestionRecognition(
    val id: String,
    val submissionId: String,
    val questionNumber: Int,
    val recognizedAnswer: String,
    val finalAnswer: String,
    val confidence: Double,
    val classification: Classification,
    val manuallyCorrected: Boolean = false,
    val correctionNote: String = "",
    val correctedAt: Long? = null,
    val alternatives: List<String> = emptyList()
) {
    /** Non-destructive override: recognizedAnswer is preserved verbatim. */
    fun correctedTo(answer: String, note: String, at: Long): QuestionRecognition = copy(
        finalAnswer = answer,
        manuallyCorrected = true,
        correctionNote = note,
        correctedAt = at
    )
}

data class ScanBatch(
    val id: String,
    val examinationId: String,
    val status: BatchStatus,
    val totalScanned: Int,
    val totalProcessed: Int,
    val totalPending: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class AnswerSheetArchive(
    val id: String,
    val submissionId: String,
    val originalImagePath: String,
    val pdfFilePath: String,
    val pdfFileName: String,
    val pdfFileSize: Long,
    val pdfChecksum: String,
    val generationStatus: GenerationStatus,
    val generatedAt: Long?
)

data class DatasetArchive(
    val id: String,
    val examinationId: String,
    val datasetType: DatasetType,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val generationVersion: Int,
    val synchronizationStatus: SynchronizationStatus,
    val recordCount: Int,
    val lastGeneratedAt: Long?,
    val lastError: String? = null
)

/** Standardized CSV / grading cell values. Uncertain states are never collapsed to "". */
object CellValue {
    const val BLANK = "BLANK"
    const val MULTIPLE = "MULTIPLE"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val INVALID = "INVALID"
    const val PENDING_REVIEW = "PENDING_REVIEW"

    fun isUncertain(value: String): Boolean = value in setOf(
        BLANK, MULTIPLE, AMBIGUOUS, INVALID, PENDING_REVIEW
    ) || value.isEmpty()

    fun isPending(value: String): Boolean = value in setOf(AMBIGUOUS, INVALID, PENDING_REVIEW, MULTIPLE)
}
