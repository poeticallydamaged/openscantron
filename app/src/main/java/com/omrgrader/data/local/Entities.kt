package com.omrgrader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "examinations")
data class ExaminationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subject: String,
    val description: String,
    val date: Long,
    @ColumnInfo(name = "total_questions") val totalQuestions: Int,
    @ColumnInfo(name = "total_points") val totalPoints: Double,
    @ColumnInfo(name = "scoring_configuration") val scoringConfiguration: String,
    @ColumnInfo(name = "active_template_id") val activeTemplateId: String?,
    @ColumnInfo(name = "active_answer_key_version_id") val activeAnswerKeyVersionId: String?,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "students",
    indices = [Index(value = ["student_number"], unique = true), Index(value = ["section"])]
)
data class StudentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "student_number") val studentNumber: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    val section: String,
    @ColumnInfo(name = "additional_information") val additionalInformation: String
)

@Entity(
    tableName = "answer_sheet_templates",
    foreignKeys = [ForeignKey(
        entity = ExaminationEntity::class, parentColumns = ["id"], childColumns = ["exam_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["template_code"], unique = true), Index(value = ["exam_id"])]
)
data class AnswerSheetTemplateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "template_code") val templateCode: String,
    @ColumnInfo(name = "exam_id") val examId: String,
    @ColumnInfo(name = "page_size") val pageSize: String,
    @ColumnInfo(name = "question_count") val questionCount: Int,
    @ColumnInfo(name = "answer_choices") val answerChoices: String,
    @ColumnInfo(name = "layout_definition") val layoutDefinition: String,
    val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "answer_keys",
    foreignKeys = [ForeignKey(
        entity = ExaminationEntity::class, parentColumns = ["id"], childColumns = ["exam_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["exam_id", "version"], unique = true)]
)
data class AnswerKeyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exam_id") val examId: String,
    val version: Int,
    val answers: String,
    @ColumnInfo(name = "scoring_rules") val scoringRules: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "scan_batches",
    foreignKeys = [ForeignKey(
        entity = ExaminationEntity::class, parentColumns = ["id"], childColumns = ["exam_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["exam_id"])]
)
data class ScanBatchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exam_id") val examId: String,
    val status: String,
    @ColumnInfo(name = "total_scanned") val totalScanned: Int,
    @ColumnInfo(name = "total_processed") val totalProcessed: Int,
    @ColumnInfo(name = "total_pending") val totalPending: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "submissions",
    foreignKeys = [
        ForeignKey(entity = ExaminationEntity::class, parentColumns = ["id"], childColumns = ["exam_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StudentEntity::class, parentColumns = ["id"], childColumns = ["student_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = AnswerSheetTemplateEntity::class, parentColumns = ["id"], childColumns = ["template_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AnswerKeyEntity::class, parentColumns = ["id"], childColumns = ["answer_key_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ScanBatchEntity::class, parentColumns = ["id"], childColumns = ["batch_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["exam_id"]), Index(value = ["student_id"]),
        Index(value = ["batch_id"]), Index(value = ["image_fingerprint"]),
        Index(value = ["exam_id", "student_id"], unique = false)]
)
data class SubmissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exam_id") val examId: String,
    @ColumnInfo(name = "student_id") val studentId: String?,
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "answer_key_version_id") val answerKeyVersionId: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "recognized_answers") val recognizedAnswers: String,
    @ColumnInfo(name = "final_answers") val finalAnswers: String,
    val score: Double?,
    val percentage: Double?,
    @ColumnInfo(name = "grading_status") val gradingStatus: String,
    @ColumnInfo(name = "processing_status") val processingStatus: String,
    @ColumnInfo(name = "image_fingerprint") val imageFingerprint: String,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long
)

@Entity(
    tableName = "question_recognitions",
    foreignKeys = [ForeignKey(
        entity = SubmissionEntity::class, parentColumns = ["id"], childColumns = ["submission_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["submission_id", "question_number"], unique = true)]
)
data class QuestionRecognitionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "submission_id") val submissionId: String,
    @ColumnInfo(name = "question_number") val questionNumber: Int,
    @ColumnInfo(name = "recognized_answer") val recognizedAnswer: String,
    @ColumnInfo(name = "final_answer") val finalAnswer: String,
    val confidence: Double,
    val classification: String,
    @ColumnInfo(name = "manually_corrected") val manuallyCorrected: Boolean,
    @ColumnInfo(name = "correction_note") val correctionNote: String,
    @ColumnInfo(name = "corrected_at") val correctedAt: Long?,
    val alternatives: String
)

@Entity(
    tableName = "answer_sheet_archives",
    foreignKeys = [ForeignKey(
        entity = SubmissionEntity::class, parentColumns = ["id"], childColumns = ["submission_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["submission_id"], unique = true)]
)
data class AnswerSheetArchiveEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "submission_id") val submissionId: String,
    @ColumnInfo(name = "original_image_path") val originalImagePath: String,
    @ColumnInfo(name = "pdf_file_path") val pdfFilePath: String,
    @ColumnInfo(name = "pdf_file_name") val pdfFileName: String,
    @ColumnInfo(name = "pdf_file_size") val pdfFileSize: Long,
    @ColumnInfo(name = "pdf_checksum") val pdfChecksum: String,
    @ColumnInfo(name = "generation_status") val generationStatus: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long?
)

@Entity(
    tableName = "dataset_archives",
    foreignKeys = [ForeignKey(
        entity = ExaminationEntity::class, parentColumns = ["id"], childColumns = ["exam_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["exam_id", "dataset_type"], unique = true)]
)
data class DatasetArchiveEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exam_id") val examId: String,
    @ColumnInfo(name = "dataset_type") val datasetType: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "generation_version") val generationVersion: Int,
    @ColumnInfo(name = "synchronization_status") val synchronizationStatus: String,
    @ColumnInfo(name = "record_count") val recordCount: Int,
    @ColumnInfo(name = "last_generated_at") val lastGeneratedAt: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?
)
