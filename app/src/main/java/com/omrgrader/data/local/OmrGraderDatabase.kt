package com.omrgrader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ExaminationEntity::class, StudentEntity::class, AnswerSheetTemplateEntity::class,
        AnswerKeyEntity::class, ScanBatchEntity::class, SubmissionEntity::class,
        QuestionRecognitionEntity::class, AnswerSheetArchiveEntity::class,
        DatasetArchiveEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class OmrGraderDatabase : RoomDatabase() {
    abstract fun examinationDao(): ExaminationDao
    abstract fun studentDao(): StudentDao
    abstract fun templateDao(): TemplateDao
    abstract fun answerKeyDao(): AnswerKeyDao
    abstract fun scanBatchDao(): ScanBatchDao
    abstract fun submissionDao(): SubmissionDao
    abstract fun questionRecognitionDao(): QuestionRecognitionDao
    abstract fun archiveDao(): ArchiveDao
    abstract fun datasetDao(): DatasetDao
    abstract fun examCreationDao(): ExamCreationDao
    abstract fun submissionWriteDao(): SubmissionWriteDao
}
