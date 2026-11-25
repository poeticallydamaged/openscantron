package com.omrgrader.verify

import com.omrgrader.archive.*
import com.omrgrader.core.Json
import com.omrgrader.core.JsonValue
import com.omrgrader.domain.GenerationStatus
import com.omrgrader.export.PdfDocumentModel
import com.omrgrader.export.PortablePdfWriter
import com.omrgrader.omr.RasterImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun registerArchiveTests() {
    fun image(w: Int = 200, h: Int = 280, seed: Int = 1): RasterImage {
        val img = RasterImage(w, h)
        for (y in 0 until h) for (x in 0 until w) img[x, y] = ((x + y + seed) % 200) + 40
        return img
    }

    TestRunner.test("archive: individual PDF preserves original on page 1 with checksum and UNASSIGNED naming") {
        val dir = File("verification-output/archives"); dir.mkdirs()
        // A real 16x24 JPEG, embedded losslessly as DCTDecode on archival page 1.
        val jpeg = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAYABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD71ooooAKKKKAP/9k=")
        val result = ArchiveService.generateIndividual(
            examId = "exam-1", studentNumber = null, submissionId = "sub-1",
            originalImage = image(), processingImage = image(200, 280, 7),
            originalImagePath = "captures/original.jpg", directory = dir,
            originalJpegBytes = jpeg
        )
        Assert.truth(result.succeeded, "archive generated: ${result.error}")
        Assert.equals("EXAM_exam-1_STUDENT_UNASSIGNED_SUBMISSION_sub-1.pdf", result.archive.pdfFileName)
        Assert.equals(GenerationStatus.GENERATED, result.archive.generationStatus)
        Assert.truth(result.archive.pdfChecksum.length == 64, "SHA-256 hex")
        Assert.truth(ArchiveService.verify(result.archive), "checksum integrity")
        val bytes = result.pdfBytes!!
        File("verification-output/individual_archive_sample.pdf").writeBytes(bytes)
        TestRunner.metrics["individual_archive_pdf_bytes"] = bytes.size.toString()
        Assert.equals("%PDF-1.4", String(bytes.take(8).toByteArray()))
        Assert.truth(String(bytes, Charsets.ISO_8859_1).contains(String(jpeg, Charsets.ISO_8859_1)),
            "original JPEG bytes are embedded without recompression")
    }

    TestRunner.test("archive: PDF generation failure is retryable and never drops the scan") {
        val blocker = File("verification-output/not-a-directory")
        blocker.writeText("blocking file")
        val result = ArchiveService.generateIndividual(
            examId = "exam-1", studentNumber = "2026-001", submissionId = "sub-2",
            originalImage = image(), processingImage = null,
            originalImagePath = "captures/original.jpg", directory = blocker
        )
        Assert.truth(!result.succeeded, "failure recorded")
        Assert.equals(GenerationStatus.RETRY_QUEUED, result.archive.generationStatus)
        Assert.equals("captures/original.jpg", result.archive.originalImagePath, "original retained")
        val retryDir = File("verification-output/retry"); retryDir.mkdirs()
        val retry = ArchiveService.retry(IndividualArchiveRequest(
            "exam-1", "2026-001", "sub-2", image(), null, "captures/original.jpg", retryDir
        ))
        Assert.truth(retry.succeeded, "one-tap retry succeeds")
    }

    TestRunner.test("archive: consolidated batch PDF is one student per page with page metadata") {
        val entries = listOf(
            BatchEntry("sub-3", "2026-003", image(seed = 3), 300),
            BatchEntry("sub-1", "2026-001", image(seed = 1), 100),
            BatchEntry("sub-2", null, image(seed = 2), 200, discarded = true),
            BatchEntry("sub-4", "2026-002", image(seed = 4), 250)
        )
        val result = ArchiveService.generateBatch("exam-1", "batch-1", entries, sortByStudentId = true,
            directory = File("verification-output/archives"))
        Assert.equals(3, result.pages.size, "discarded excluded")
        Assert.equals(listOf("2026-001", "2026-002", "2026-003"), result.pages.map { it.studentNumber })
        Assert.equals("EXAM_exam-1_BATCH_batch-1_ANSWER_ARCHIVE.pdf", result.fileName)
        File("verification-output/batch_archive_sample.pdf").writeBytes(result.bytes)
        TestRunner.metrics["batch_archive_pdf_bytes"] = result.bytes.size.toString()
    }

    TestRunner.test("archive: storage usage and explicit raw-image deletion") {
        val dir = File("verification-output/storage"); dir.mkdirs()
        val pdf = File(dir, "a.pdf"); pdf.writeBytes(ByteArray(120))
        val img = File(dir, "a.jpg"); img.writeBytes(ByteArray(80))
        val csv = File(dir, "a.csv"); csv.writeBytes(ByteArray(40))
        val archiveDir = File("verification-output/storage-archives"); archiveDir.mkdirs()
        val arch = ArchiveService.generateIndividual("exam-1", "2026-001", "sub-9", image(), null,
            img.absolutePath, archiveDir).archive
        val usage = ArchiveService.storageUsage(listOf(arch), listOf(img), listOf(csv))
        Assert.equals(120L + arch.pdfFileSize, usage.totalBytes)
        Assert.equals(1, usage.archivePdfCount)
        Assert.equals(1, usage.rawImageCount)
        Assert.throws<IllegalArgumentException> { ArchiveService.deleteRawImages(listOf(img), "NO") }
        Assert.equals(1, ArchiveService.deleteRawImages(listOf(img), "DELETE_RAW_IMAGES_CONFIRMED"))
        Assert.truth(!img.exists(), "image explicitly deleted")
    }

    TestRunner.test("backup: create, restore, checksum tamper detection and relational validation") {
        val payload = BackupPayload(
            database = "SQLite format 3\u0000".toByteArray(),
            layoutsJson = "{\"layouts\":[]}", keysJson = "{\"keys\":[]}",
            submissionsJson = "{\"submissions\":[]}",
            artifacts = listOf(
                BackupFile("archives/a.pdf", "PDF".toByteArray(), BackupFileKind.ARCHIVE_PDF),
                BackupFile("datasets/a.csv", "CSV".toByteArray(), BackupFileKind.DATASET_CSV),
                BackupFile("captures/a.jpg", "JPG".toByteArray(), BackupFileKind.RAW_IMAGE)
            ),
            relationalCounts = mapOf("exams" to 1, "students" to 2, "submissions" to 1,
                "recognitions" to 5, "archives" to 1, "datasets" to 2),
            submissionIds = listOf("sub-1"), recognitionSubmissionIds = listOf("sub-1"),
            includeRawImages = true
        )
        val bundle = BackupService.create(payload, 1_760_000_000_000L)
        Assert.equals(7, bundle.manifest.files.size, "3 base + 3 artifacts + submissions")
        File("verification-output/backup_sample.zip").writeBytes(bundle.bytes)
        TestRunner.metrics["backup_zip_bytes"] = bundle.bytes.size.toString()
        val dest = File("verification-output/restore")
        val restored = BackupService.restore(bundle.bytes, dest)
        Assert.truth(restored.success, "restore succeeds: ${restored.errors}")
        Assert.truth(File(dest, "database/omrgrader.db").isFile, "database restored")
        Assert.truth(File(dest, "archives/a.pdf").isFile, "PDF restored")

        // Tamper with one archived file while keeping the manifest intact.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bundle.bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
                while (true) { val n = zip.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
                entries[e.name] = out.toByteArray()
            }
        }
        entries["archives/a.pdf"] = "CORRUPT".toByteArray()
        val tamperedOut = ByteArrayOutputStream()
        ZipOutputStream(tamperedOut).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        val tampered = BackupService.restore(tamperedOut.toByteArray(), File("verification-output/restore-tampered"))
        Assert.truth(!tampered.success, "tamper rejected")
        Assert.truth(tampered.errors.any { it.contains("CHECKSUM_MISMATCH") || it.contains("SIZE_MISMATCH") },
            "checksum/size mismatch reported")

        val broken = payload.copy(relationalCounts = payload.relationalCounts + ("archives" to 9))
        val brokenRestore = BackupService.restore(BackupService.create(broken).bytes,
            File("verification-output/restore-broken"))
        Assert.truth(!brokenRestore.success, "relational validation rejects orphan archives")
        Assert.truth(brokenRestore.errors.contains("ARCHIVE_COUNT_EXCEEDS_SUBMISSIONS"), "relation error")
    }
}
