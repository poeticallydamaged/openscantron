package com.omrgrader.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit migrations only. The production database never uses
 * fallbackToDestructiveMigration; a missing migration is a hard startup error.
 * v1 shipped the original nine tables; v2 added correction provenance, retry state,
 * dataset error reporting and two composite indices.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE question_recognitions ADD COLUMN correction_note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE question_recognitions ADD COLUMN alternatives TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE answer_sheet_archives ADD COLUMN generation_status TEXT NOT NULL DEFAULT 'GENERATED'")
        db.execSQL("ALTER TABLE dataset_archives ADD COLUMN last_error TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_submissions_exam_id_student_id ON submissions (exam_id, student_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_students_section ON students (section)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
