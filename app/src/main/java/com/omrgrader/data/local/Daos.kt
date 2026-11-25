package com.omrgrader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExaminationDao {
    @Query("SELECT * FROM examinations WHERE is_archived = 0 ORDER BY updated_at DESC")
    fun activeExams(): Flow<List<ExaminationEntity>>
    @Query("SELECT * FROM examinations ORDER BY updated_at DESC")
    fun allExams(): Flow<List<ExaminationEntity>>
    @Query("SELECT * FROM examinations WHERE id = :id")
    suspend fun byId(id: String): ExaminationEntity?
    @Query("SELECT * FROM examinations WHERE id = :id")
    fun observeById(id: String): Flow<ExaminationEntity?>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: ExaminationEntity)
    @Update suspend fun update(entity: ExaminationEntity)
    @Query("SELECT COUNT(*) FROM examinations") fun count(): Flow<Int>
}

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY full_name") fun all(): Flow<List<StudentEntity>>
    @Query("SELECT * FROM students ORDER BY full_name") suspend fun allOnce(): List<StudentEntity>
    @Query("SELECT * FROM students WHERE section LIKE '%' || :query || '%' OR full_name LIKE '%' || :query || '%' OR student_number LIKE '%' || :query || '%' ORDER BY full_name")
    fun search(query: String): Flow<List<StudentEntity>>
    @Query("SELECT * FROM students WHERE student_number = :number LIMIT 1")
    suspend fun byNumber(number: String): StudentEntity?
    @Query("SELECT * FROM students WHERE id = :id") suspend fun byId(id: String): StudentEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(students: List<StudentEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(student: StudentEntity)
    @Update suspend fun update(student: StudentEntity)
    @Query("DELETE FROM students WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM answer_sheet_templates WHERE exam_id = :examId ORDER BY version DESC")
    fun forExam(examId: String): Flow<List<AnswerSheetTemplateEntity>>
    @Query("SELECT * FROM answer_sheet_templates WHERE id = :id") suspend fun byId(id: String): AnswerSheetTemplateEntity?
    @Query("SELECT * FROM answer_sheet_templates WHERE template_code = :code LIMIT 1")
    suspend fun byCode(code: String): AnswerSheetTemplateEntity?
    @Query("SELECT * FROM answer_sheet_templates") suspend fun all(): List<AnswerSheetTemplateEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: AnswerSheetTemplateEntity)
    @Update suspend fun update(entity: AnswerSheetTemplateEntity)
}

@Dao
interface AnswerKeyDao {
    @Query("SELECT * FROM answer_keys WHERE exam_id = :examId ORDER BY version DESC")
    fun forExam(examId: String): Flow<List<AnswerKeyEntity>>
    @Query("SELECT * FROM answer_keys WHERE id = :id") suspend fun byId(id: String): AnswerKeyEntity?
    @Query("SELECT * FROM answer_keys WHERE exam_id = :examId ORDER BY version DESC LIMIT 1")
    suspend fun latest(examId: String): AnswerKeyEntity?
    @Query("SELECT * FROM answer_keys") suspend fun all(): List<AnswerKeyEntity>
    @Query("SELECT * FROM answer_keys WHERE exam_id = :examId ORDER BY version")
    suspend fun allForExam(examId: String): List<AnswerKeyEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: AnswerKeyEntity)
}

@Dao
interface ScanBatchDao {
    @Query("SELECT * FROM scan_batches WHERE exam_id = :examId ORDER BY created_at DESC")
    fun forExam(examId: String): Flow<List<ScanBatchEntity>>
    @Query("SELECT * FROM scan_batches WHERE status IN ('ACTIVE','RECOVERABLE') ORDER BY updated_at DESC")
    suspend fun recoverable(): List<ScanBatchEntity>
    @Query("SELECT * FROM scan_batches WHERE id = :id") suspend fun byId(id: String): ScanBatchEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: ScanBatchEntity)
    @Update suspend fun update(entity: ScanBatchEntity)
}

@Dao
interface SubmissionDao {
    @Query("SELECT * FROM submissions WHERE exam_id = :examId AND grading_status != 'DISCARDED' ORDER BY scanned_at DESC")
    fun forExam(examId: String): Flow<List<SubmissionEntity>>
    @Query("SELECT * FROM submissions WHERE id = :id") fun observeById(id: String): Flow<SubmissionEntity?>
    @Query("SELECT * FROM submissions WHERE id = :id") suspend fun byId(id: String): SubmissionEntity?
    @Query("SELECT * FROM submissions WHERE exam_id = :examId AND grading_status != 'DISCARDED'")
    suspend fun allForExam(examId: String): List<SubmissionEntity>
    @Query("SELECT * FROM submissions WHERE exam_id = :examId AND student_id = :studentId AND grading_status != 'DISCARDED'")
    suspend fun byStudent(examId: String, studentId: String): List<SubmissionEntity>
    @Query("SELECT * FROM submissions WHERE image_fingerprint = :fingerprint AND exam_id = :examId AND grading_status != 'DISCARDED'")
    suspend fun byFingerprint(examId: String, fingerprint: String): List<SubmissionEntity>
    @Query("SELECT * FROM submissions WHERE batch_id = :batchId ORDER BY scanned_at")
    fun forBatch(batchId: String): Flow<List<SubmissionEntity>>
    @Query("SELECT COUNT(*) FROM submissions WHERE grading_status != 'DISCARDED'") fun totalCount(): Flow<Int>
    @Query("SELECT * FROM submissions WHERE grading_status != 'DISCARDED' ORDER BY scanned_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SubmissionEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: SubmissionEntity)
    @Update suspend fun update(entity: SubmissionEntity)
    @Query("UPDATE submissions SET grading_status = 'DISCARDED' WHERE id = :id") suspend fun discard(id: String)
}

@Dao
interface QuestionRecognitionDao {
    @Query("SELECT * FROM question_recognitions WHERE submission_id = :submissionId ORDER BY question_number")
    fun forSubmission(submissionId: String): Flow<List<QuestionRecognitionEntity>>
    @Query("SELECT * FROM question_recognitions WHERE submission_id = :submissionId ORDER BY question_number")
    suspend fun forSubmissionOnce(submissionId: String): List<QuestionRecognitionEntity>
    @Query("SELECT * FROM question_recognitions WHERE submission_id IN (:submissionIds) ORDER BY submission_id, question_number")
    suspend fun forSubmissions(submissionIds: List<String>): List<QuestionRecognitionEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(items: List<QuestionRecognitionEntity>)
    @Update suspend fun update(item: QuestionRecognitionEntity)
    @Query("DELETE FROM question_recognitions WHERE submission_id = :submissionId")
    suspend fun deleteForSubmission(submissionId: String)
}

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM answer_sheet_archives ORDER BY generated_at DESC")
    fun all(): Flow<List<AnswerSheetArchiveEntity>>
    @Query("SELECT * FROM answer_sheet_archives") suspend fun allOnce(): List<AnswerSheetArchiveEntity>
    @Query("SELECT * FROM answer_sheet_archives WHERE submission_id = :submissionId")
    suspend fun forSubmission(submissionId: String): AnswerSheetArchiveEntity?
    @Query("SELECT * FROM answer_sheet_archives WHERE generation_status != 'GENERATED'")
    suspend fun retryQueue(): List<AnswerSheetArchiveEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: AnswerSheetArchiveEntity)
    @Query("SELECT COUNT(*) FROM answer_sheet_archives WHERE generation_status = 'GENERATED'")
    fun generatedCount(): Flow<Int>
}

@Dao
interface DatasetDao {
    @Query("SELECT * FROM dataset_archives WHERE exam_id = :examId ORDER BY dataset_type")
    fun forExam(examId: String): Flow<List<DatasetArchiveEntity>>
    @Query("SELECT * FROM dataset_archives WHERE exam_id = :examId")
    suspend fun forExamOnce(examId: String): List<DatasetArchiveEntity>
    @Query("SELECT * FROM dataset_archives") suspend fun all(): List<DatasetArchiveEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<DatasetArchiveEntity>)
    @Query("SELECT COUNT(*) FROM dataset_archives WHERE synchronization_status = 'SYNCED'")
    fun syncedCount(): Flow<Int>
}

/** Cross-DAO transaction used when an exam is created with its template, key and datasets. */
@Dao
abstract class ExamCreationDao {
    @Transaction
    open suspend fun createExamBundle(
        exam: ExaminationEntity,
        template: AnswerSheetTemplateEntity,
        key: AnswerKeyEntity?,
        datasets: List<DatasetArchiveEntity>
    ) {
        insertExam(exam); insertTemplate(template)
        if (key != null) insertKey(key)
        insertDatasets(datasets)
    }
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertExam(e: ExaminationEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertTemplate(e: AnswerSheetTemplateEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertKey(e: AnswerKeyEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertDatasets(e: List<DatasetArchiveEntity>)
}

/** Transactional submission write: the row and all per-question rows commit together. */
@Dao
abstract class SubmissionWriteDao {
    @Transaction
    open suspend fun insertBundle(submission: SubmissionEntity, recognitions: List<QuestionRecognitionEntity>) {
        insertSubmission(submission)
        insertRecognitions(recognitions)
    }
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSubmission(s: SubmissionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertRecognitions(items: List<QuestionRecognitionEntity>)
}
