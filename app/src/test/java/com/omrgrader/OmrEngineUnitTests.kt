package com.omrgrader

import com.omrgrader.core.OmrResult
import com.omrgrader.domain.*
import com.omrgrader.omr.*
import org.junit.Assert.*
import org.junit.Test

class OmrEngineUnitTests {
    @Test fun cleanSyntheticSheetRegistersAndRecognizesEveryQuestion() {
        val layout = LayoutFactory.build("exam-unit", PageSize.A4, 20,
            listOf("A", "B", "C", "D"), 8)
        val case = SyntheticSheetGenerator.generate(2026, SyntheticCondition.CLEAN, layout)
        val roster = InMemoryRosterLookup(listOf(
            Student("student-unit", case.studentNumber, "Test Student", "A")))
        val result = OmrEngine().process(OmrRequest(case.image,
            InMemoryLayoutRegistry(listOf(layout)), roster))
        assertTrue(result is OmrResult.Success)
        val recognition = (result as OmrResult.Success).value
        assertEquals(layout.templateCode, recognition.templateCode)
        assertEquals(case.studentNumber, recognition.recognizedStudentNumber)
        assertEquals(20, recognition.questions.size)
        case.expectedClassification.forEach { (q, expected) ->
            val actual = recognition.questions.first { it.questionNumber == q }.classification
            when (expected) {
                Classification.BLANK -> assertEquals("Q$q should be blank", Classification.BLANK, actual)
                Classification.SINGLE_MARK -> assertEquals("Q$q should be single", Classification.SINGLE_MARK, actual)
                else -> Unit
            }
        }
    }
}
