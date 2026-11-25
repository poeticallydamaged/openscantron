package com.omrgrader.data.repository

import com.omrgrader.data.local.*
import com.omrgrader.domain.*
import com.omrgrader.omr.SheetLayout

fun ExaminationEntity.toDomain() = Examination(
    id, title, subject, description, date, totalQuestions, totalPoints,
    DomainJson.scoring(scoringConfiguration), activeTemplateId, activeAnswerKeyVersionId,
    isArchived, createdAt, updatedAt
)

fun Examination.toEntity() = ExaminationEntity(
    id, title, subject, description, date, totalQuestions, totalPoints,
    DomainJson.scoring(scoringConfiguration), activeTemplateId, activeAnswerKeyVersionId,
    isArchived, createdAt, updatedAt
)

fun StudentEntity.toDomain() = Student(id, studentNumber, fullName, section, additionalInformation)
fun Student.toEntity() = StudentEntity(id, studentNumber, fullName, section, additionalInformation)

fun AnswerSheetTemplateEntity.toDomain() = AnswerSheetTemplate(
    id, templateCode, examId, runCatching { PageSize.valueOf(pageSize) }.getOrDefault(PageSize.A4),
    questionCount, DomainJson.labels(answerChoices), layoutDefinition, version, createdAt
)

fun AnswerSheetTemplate.toEntity() = AnswerSheetTemplateEntity(
    id, templateCode, examinationId, pageSize.name, questionCount,
    DomainJson.labels(answerChoices), layoutDefinition, version, createdAt
)

fun AnswerKeyEntity.toDomain() = AnswerKey(
    id, examId, version, DomainJson.answers(answers), DomainJson.scoring(scoringRules), createdAt
)
fun AnswerKey.toEntity() = AnswerKeyEntity(
    id, examinationId, version, DomainJson.answers(answers), DomainJson.scoring(scoringRules), createdAt
)

fun ScanBatchEntity.toDomain() = ScanBatch(id, examId,
    runCatching { BatchStatus.valueOf(status) }.getOrDefault(BatchStatus.ACTIVE),
    totalScanned, totalProcessed, totalPending, createdAt, updatedAt)

fun ScanBatch.toEntity() = ScanBatchEntity(id, examinationId, status.name,
    totalScanned, totalProcessed, totalPending, createdAt, updatedAt)

fun SubmissionEntity.toDomain() = Submission(
    id, examId, studentId, templateId, answerKeyVersionId, batchId,
    DomainJson.answers(recognizedAnswers), DomainJson.answers(finalAnswers), score, percentage,
    runCatching { GradingStatus.valueOf(gradingStatus) }.getOrDefault(GradingStatus.PENDING_REVIEW),
    runCatching { ProcessingStatus.valueOf(processingStatus) }.getOrDefault(ProcessingStatus.CAPTURED),
    imageFingerprint, scannedAt
)

fun Submission.toEntity() = SubmissionEntity(
    id, examinationId, studentId, templateId, answerKeyVersionId, batchId,
    DomainJson.answers(recognizedAnswers), DomainJson.answers(finalAnswers), score, percentage,
    gradingStatus.name, processingStatus.name, imageFingerprint, scannedAt
)

fun QuestionRecognitionEntity.toDomain() = QuestionRecognition(
    id, submissionId, questionNumber, recognizedAnswer, finalAnswer, confidence,
    runCatching { Classification.valueOf(classification) }.getOrDefault(Classification.INVALID),
    manuallyCorrected, correctionNote, correctedAt, DomainJson.labels(alternatives)
)

fun QuestionRecognition.toEntity() = QuestionRecognitionEntity(
    id, submissionId, questionNumber, recognizedAnswer, finalAnswer, confidence,
    classification.name, manuallyCorrected, correctionNote, correctedAt,
    DomainJson.labels(alternatives)
)

fun AnswerSheetArchiveEntity.toDomain() = AnswerSheetArchive(
    id, submissionId, originalImagePath, pdfFilePath, pdfFileName, pdfFileSize, pdfChecksum,
    runCatching { GenerationStatus.valueOf(generationStatus) }.getOrDefault(GenerationStatus.RETRY_QUEUED),
    generatedAt
)

fun AnswerSheetArchive.toEntity() = AnswerSheetArchiveEntity(
    id, submissionId, originalImagePath, pdfFilePath, pdfFileName, pdfFileSize, pdfChecksum,
    generationStatus.name, generatedAt
)

fun DatasetArchiveEntity.toDomain() = DatasetArchive(
    id, examId, runCatching { DatasetType.valueOf(datasetType) }.getOrDefault(DatasetType.WIDE_ANSWERS),
    filePath, fileName, fileSize, generationVersion,
    runCatching { SynchronizationStatus.valueOf(synchronizationStatus) }
        .getOrDefault(SynchronizationStatus.INITIALIZED),
    recordCount, lastGeneratedAt, lastError
)

fun DatasetArchive.toEntity() = DatasetArchiveEntity(
    id, examinationId, datasetType.name, filePath, fileName, fileSize, generationVersion,
    synchronizationStatus.name, recordCount, lastGeneratedAt, lastError
)

fun AnswerSheetTemplate.layout(): SheetLayout = SheetLayout.fromJson(layoutDefinition)
