package com.omrgrader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omrgrader.data.local.*
import com.omrgrader.data.repository.toEntity
import com.omrgrader.domain.DomainJson
import com.omrgrader.domain.PageSize
import com.omrgrader.domain.ScoringConfiguration
import com.omrgrader.export.DatasetSynchronizer
import com.omrgrader.omr.LayoutFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTransactionInstrumentedTest {
    private lateinit var db: OmrGraderDatabase

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OmrGraderDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() { db.close() }

    @Test fun examCreationBundleCommitsExamTemplateAndDatasets() = runBlocking {
        val exam = ExaminationEntity("exam-1", "Midterm", "Math", "", 1L, 20, 20.0,
            DomainJson.scoring(ScoringConfiguration()), null, null, false, 1L, 1L)
        val layout = LayoutFactory.build("exam-1", PageSize.A4, 20,
            listOf("A", "B", "C", "D"), 8)
        val template = AnswerSheetTemplateEntity("tpl-1", layout.templateCode, "exam-1",
            PageSize.A4.name, 20, DomainJson.labels(listOf("A", "B", "C", "D")),
            layout.toJson(), 1, 1L)
        val datasets = DatasetSynchronizer.initialize("exam-1", 1L).map { it.toEntity() }
        db.examCreationDao().createExamBundle(exam, template, null, datasets)
        val cursor = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM examinations")
        assertTrue(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        cursor.close()
        assertEquals("exam-1", db.examinationDao().byId("exam-1")?.id)
        assertEquals(2, db.datasetDao().forExamOnce("exam-1").size)
        assertEquals("tpl-1", db.templateDao().byId("tpl-1")?.id)
    }
}
