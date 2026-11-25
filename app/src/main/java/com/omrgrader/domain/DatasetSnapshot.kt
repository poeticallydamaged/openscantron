package com.omrgrader.domain

/** Immutable projection of Room state consumed by the derived CSV/PDF artifact generators. */
data class DatasetSnapshot(
    val examination: Examination,
    val activeKey: AnswerKey,
    val students: List<Student>,
    val submissions: List<SubmissionSnapshot>,
    val recognitions: List<RecognitionSnapshot>,
    val archives: List<AnswerSheetArchive> = emptyList(),
    val datasets: List<DatasetArchive> = emptyList()
)

data class SubmissionSnapshot(
    val id: String,
    val examinationId: String,
    val studentId: String?,
    val templateId: String,
    val answerKeyVersionId: String,
    val batchId: String,
    val scannedAt: Long,
    val gradingStatus: GradingStatus,
    val processingStatus: ProcessingStatus,
    val finalAnswers: Map<Int, String>,
    val score: Double?,
    val maxScore: Double?,
    val percentage: Double?,
    val passingStatus: String
)

data class RecognitionSnapshot(
    val submissionId: String,
    val questionNumber: Int,
    val detectedAnswer: String,
    val finalAnswer: String,
    val confidence: Double,
    val classification: Classification,
    val manuallyCorrected: Boolean,
    val correctedAt: Long?,
    val alternatives: List<String> = emptyList()
)

/** Multi-exam consolidated export projection. */
data class MultiExamSnapshot(val exams: List<DatasetSnapshot>)
