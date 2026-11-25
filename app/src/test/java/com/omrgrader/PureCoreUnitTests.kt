package com.omrgrader

import com.omrgrader.core.*
import com.omrgrader.domain.*
import com.omrgrader.export.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PureCoreUnitTests {
    @Test fun csvIsRfc4180AndUtf8Bom() {
        val bytes = Csv.write(listOf("id", "name"), listOf(listOf("1", "Doe, \"Jane\"")))
        assertTrue(bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        val rows = Csv.parseBytes(bytes)
        assertEquals("Doe, \"Jane\"", rows[1][1])
    }

    @Test fun atomicWriteLeavesNoTempFiles() {
        val dir = Files.createTempDirectory("omr-unit").toFile()
        val file = AtomicFiles.write(dir, "sample.csv", "a,b\n1,2".toByteArray())
        assertTrue(file.isFile)
        assertTrue(AtomicFiles.staleTempFiles(dir).isEmpty())
        assertEquals(Sha256.of(file), Sha256.of(file.readBytes()))
        dir.deleteRecursively()
    }

    @Test fun gradingGuardsZeroMaximumScore() {
        val key = AnswerKey("key", "exam", 1, mapOf(1 to "A"),
            ScoringConfiguration(weights = mapOf(1 to 0.0)), 1L)
        val result = GradingEngine.grade(key, listOf(
            QuestionRecognition("r", "sub", 1, "A", "A", 0.99, Classification.SINGLE_MARK)
        ), 1)
        assertEquals(0.0, result.percentage, 1e-9)
        assertTrue(result.warnings.contains("max_score_zero_percentage_forced_to_zero"))
    }

    @Test fun answerKeyVersioningRejectsMissingItems() {
        val exam = ExamAdmin.create("Final", "Math", "", 1L, 3,
            listOf("A", "B", "C", "D"), ScoringConfiguration(), 1L).examination
        val missing = AnswerKeyVersionService.validate(mapOf(1 to "A", 3 to "B"), 3, listOf("A", "B", "C", "D"))
        assertEquals(listOf(2), missing)
        val created = AnswerKeyVersionService.create(exam, mapOf(1 to "A", 2 to "B", 3 to "C"),
            listOf("A", "B", "C", "D"), emptyList(), 2L).key
        assertEquals(1, created.version)
    }

    @Test fun qrCodecRoundTripsRealMetadata() {
        val payload = "OMR1|TPL-A020-V1-UNIT|v1|exam-unit|20|ABCD"
        val matrix = PortableQrCodec.encode(payload)
        assertEquals(payload, PortableQrCodec.decode(matrix))
        assertTrue(matrix.countDark() > 100)
    }
}
