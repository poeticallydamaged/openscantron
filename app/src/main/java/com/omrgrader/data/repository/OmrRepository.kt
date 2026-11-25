package com.omrgrader.data.repository

import android.net.Uri
import com.omrgrader.archive.BackupBundle
import com.omrgrader.archive.RestoreResult
import com.omrgrader.core.OmrResult
import com.omrgrader.domain.*
import com.omrgrader.omr.OmrRecognitionResult
import com.omrgrader.omr.SheetLayout
import kotlinx.coroutines.flow.Flow

data class DashboardStats(
    val examCount: Int,
    val submissionCount: Int,
    val archivedPdfCount: Int,
    val syncedCsvCount: Int,
    val recentExams: List<Examination>,
    val recentSubmissions: List<Submission>
)

data class ScanCommitRequest(
    val examId: String,
    val batchId: String,
    val imageBytes: ByteArray,
    val exifRotationDegrees: Int = 0,
    val qrStudentNumber: String? = null,
    val duplicateResolution: DuplicateResolution? = null,
    val existingIdToReplace: String? = null,
    val submissionId: String = com.omrgrader.core.Ids.newId("submission")
)

sealed class ScanCommitResult {
    data class Success(
        val submissionId: String,
        val recognition: OmrRecognitionResult,
        val grading: GradingResult,
        val archiveStatus: GenerationStatus
    ) : ScanCommitResult()
    data class DuplicateRequired(val match: DuplicateMatch) : ScanCommitResult()
    data class Discarded(val reason: String) : ScanCommitResult()
}

interface OmrRepository {
    fun dashboard(): Flow<DashboardStats>
    fun exams(): Flow<List<Examination>>
    fun allExams(): Flow<List<Examination>>
    fun exam(id: String): Flow<Examination?>
    fun students(query: String = ""): Flow<List<Student>>
    fun templates(examId: String): Flow<List<AnswerSheetTemplate>>
    fun keys(examId: String): Flow<List<AnswerKey>>
    fun submissions(examId: String): Flow<List<Submission>>
    fun submission(id: String): Flow<Submission?>
    fun recognitions(submissionId: String): Flow<List<QuestionRecognition>>
    fun archives(): Flow<List<AnswerSheetArchive>>
    fun datasets(examId: String): Flow<List<DatasetArchive>>
    fun batches(examId: String): Flow<List<ScanBatch>>

    suspend fun createExam(
        title: String, subject: String, description: String, date: Long,
        totalQuestions: Int, choices: List<String>, scoring: ScoringConfiguration,
        pageSize: PageSize
    ): OmrResult<Examination>
    suspend fun updateExam(exam: Examination): OmrResult<Unit>
    suspend fun duplicateExam(id: String): OmrResult<Examination>
    suspend fun archiveExam(id: String, archive: Boolean): OmrResult<Unit>
    suspend fun createTemplate(examId: String, pageSize: PageSize, questionCount: Int,
                               choices: List<String>, idColumns: Int): OmrResult<AnswerSheetTemplate>
    suspend fun saveKey(examId: String, answers: Map<Int, String>, choices: List<String>): OmrResult<AnswerKey>
    suspend fun activateKey(examId: String, keyId: String): OmrResult<Unit>
    suspend fun editActiveKey(examId: String, newAnswers: Map<Int, String>, choices: List<String>): OmrResult<AnswerKeyVersionService.VersionEditResult>
    suspend fun saveStudent(student: Student): OmrResult<Unit>
    suspend fun importStudents(csvBytes: ByteArray): OmrResult<RosterImportResult>
    suspend fun removeStudent(studentId: String): OmrResult<Unit>
    suspend fun startBatch(examId: String): String
    suspend fun recoverableBatch(examId: String): ScanBatch?
    suspend fun processScan(request: ScanCommitRequest): OmrResult<ScanCommitResult>
    suspend fun correctQuestion(submissionId: String, questionNumber: Int, answer: String, note: String): OmrResult<Unit>
    suspend fun assignStudent(submissionId: String, studentId: String): OmrResult<Unit>
    suspend fun confirmGrading(submissionId: String): OmrResult<GradingResult>
    suspend fun regradeExam(examId: String, keyVersionId: String): OmrResult<Int>
    suspend fun discardSubmission(submissionId: String): OmrResult<Unit>
    suspend fun retryArchive(submissionId: String): OmrResult<AnswerSheetArchive>
    suspend fun generateBatchArchive(examId: String, batchId: String, sortByStudentId: Boolean): OmrResult<ByteArray>
    suspend fun snapshot(examId: String): DatasetSnapshot?
    suspend fun regenerateDatasets(examId: String): OmrResult<Int>
    suspend fun layoutFor(templateCode: String): SheetLayout?
    suspend fun blankSheetPdf(templateId: String, copies: Int): OmrResult<ByteArray>
    suspend fun createBackup(includeRawImages: Boolean): OmrResult<BackupBundle>
    suspend fun restoreBackup(uri: Uri): OmrResult<RestoreResult>
    suspend fun exportToSaf(fileName: String, bytes: ByteArray, mimeType: String): OmrResult<Uri>
}
