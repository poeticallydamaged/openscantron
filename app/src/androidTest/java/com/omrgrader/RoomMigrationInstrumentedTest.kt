package com.omrgrader

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omrgrader.data.local.MIGRATION_1_2
import com.omrgrader.data.local.OmrGraderDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
    private val dbName = "migration-test"
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OmrGraderDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrate1To2PreservesRowsAndAddsProvenanceColumns() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO examinations (id,title,subject,description,date,total_questions,total_points,scoring_configuration,is_archived,created_at,updated_at) VALUES ('exam-1','Midterm','Math','',1,20,20,'{}',0,1,1)")
            execSQL("INSERT INTO students (id,student_number,full_name,section,additional_information) VALUES ('student-1','2026-001','Test Student','A','')")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='examinations'")
        assertTrue(cursor.count == 1)
        cursor.close()
        val columns = db.query("PRAGMA table_info(question_recognitions)")
        val names = mutableSetOf<String>()
        while (columns.moveToNext()) names += columns.getString(1)
        assertTrue(names.contains("correction_note"))
        assertTrue(names.contains("alternatives"))
        columns.close()
    }
}
