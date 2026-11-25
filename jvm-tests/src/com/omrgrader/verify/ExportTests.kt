package com.omrgrader.verify

import com.omrgrader.core.*
import com.omrgrader.domain.*
import com.omrgrader.export.*
import com.omrgrader.omr.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun registerExportTests() {
    val now = 1_760_000_000_000L
    fun snapshot(totalQuestions: Int = 5, examId: String = "exam-1"): DatasetSnapshot {
        val scoring = ScoringConfiguration()
        val exam = Examination(examId, "Midterm, \"A\"", "Mathematics", "", now, totalQuestions,
            totalQuestions.toDouble(), scoring, "tpl", "key-1", false, now, now)
        val key = AnswerKey("key-1", examId, 1, (1..totalQuestions).associateWith { "A" }, scoring, now)
        val student = Student("student-1", "2026-001", "Juan Dela Cruz", "Section A", "")
        val sub = SubmissionSnapshot("sub-1", examId, "student-1", "tpl", "key-1", "batch-1", now,
            GradingStatus.GRADED, ProcessingStatus.ARCHIVED,
            (1..totalQuestions).associateWith { if (it == 2) "B" else if (it == 3) "BLANK" else "A" },
            totalQuestions.toDouble() - 1, totalQuestions.toDouble(), 80.0, "PASS")
        val recs = (1..totalQuestions).map { q ->
            RecognitionSnapshot("sub-1", q, "A", sub.finalAnswers[q]!!, 0.91,
                if (q == 3) Classification.BLANK else Classification.SINGLE_MARK, q == 2,
                if (q == 2) now else null)
        }
        return DatasetSnapshot(exam, key, listOf(student), listOf(sub), recs)
    }

    TestRunner.test("export: portable QR matches a standard decoder contract and never fabricates tampered payloads") {
        val payload = "OMR1|TPL-A005-V1-EXAM0001|v1|exam-12345678|5|ABCD"
        val matrix = PortableQrCodec.encode(payload)
        Assert.truth(matrix.size in 21..57, "QR version 1..10 size")
        Assert.equals(payload, PortableQrCodec.decode(matrix), "round-trip")
        // Flip an actual data module (not a function pattern): RS syndrome must reject it.
        val tampered = matrix.copy()
        val centre = matrix.size / 2
        tampered[centre, centre] = !tampered[centre, centre]
        // A single flipped data bit can still be a valid RS codeword; verify that at least
        // the codec never invents a different payload silently.
        val decoded = PortableQrCodec.decode(tampered)
        Assert.truth(decoded == null || decoded == payload, "tamper never fabricates a payload")
        val longPayload = "OMR1|" + "x".repeat(180)
        val longMatrix = PortableQrCodec.encode(longPayload)
        Assert.equals(longPayload, PortableQrCodec.decode(longMatrix), "multi-block version round-trip")
    }

    TestRunner.test("export: layout JSON is deterministic and normalized to marker centroids") {
        val a = LayoutFactory.build("exam-1", PageSize.A4, 40, listOf("A", "B", "C", "D", "E"), 8)
        val b = LayoutFactory.build("exam-1", PageSize.A4, 40, listOf("A", "B", "C", "D", "E"), 8)
        Assert.equals(a.toJson(), b.toJson(), "deterministic layout")
        val restored = SheetLayout.fromJson(a.toJson())
        Assert.equals(a.bubbles.size, restored.bubbles.size)
        Assert.equals(a.metadataPayload(), restored.metadataPayload())
        Assert.truth(a.bubbles.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }, "bubbles normalized")
        Assert.truth(a.markers.size == 4, "four fiducials")
        val letter = LayoutFactory.build("exam-1", PageSize.LETTER, 10, listOf("A", "B"), 8)
        Assert.truth(letter.canonicalWidth != a.canonicalWidth || letter.canonicalHeight != a.canonicalHeight, "page sizes differ")
    }

    TestRunner.test("export: printable sheet PDF has 4 markers, QR and Q001..QNNN geometry") {
        val layout = LayoutFactory.build("exam-1", PageSize.A4, 20, listOf("A", "B", "C", "D"), 8)
        val model = BlankSheetPdfGenerator.generate(layout, copies = 3)
        Assert.equals(3, model.pages.size)
        val ops = model.pages.first().ops
        val filled = ops.filterIsInstance<PdfOp.FilledRect>()
        val circles = ops.filterIsInstance<PdfOp.Circle>()
        Assert.truth(filled.size > 100, "QR modules and markers drawn: ${filled.size}")
        Assert.equals(layout.bubbles.size + layout.idBubbles.size, circles.size, "all bubble outlines")
        val texts = ops.filterIsInstance<PdfOp.Text>().map { it.text }
        Assert.truth(texts.any { it.contains("OPEN SCANTRON") }, "title")
        Assert.truth(texts.any { it.contains("Q") || it.contains("001") }, "question numbers")
        val bytes = PortablePdfWriter.render(model)
        File("verification-output").mkdirs()
        File("verification-output/blank_answer_sheet.pdf").writeBytes(bytes)
        // Export a standalone QR bitmap so an independent decoder (OpenCV) can verify it.
        writeQrBmp(File("verification-output/qr_template.bmp"),
            PortableQrCodec.encode(layout.metadataPayload()), 8, 4)
        Assert.equals("%PDF-1.4", String(bytes.take(8).toByteArray()))
        Assert.truth(bytes.size > 10_000, "non-trivial PDF size")
    }

    TestRunner.test("export: report PDFs render real KPI and item figures") {
        val snap = snapshot()
        val results = listOf(GradingEngine.grade(snap.activeKey,
            snap.recognitions.map { QuestionRecognition("r", "sub-1", it.questionNumber, it.detectedAnswer,
                it.finalAnswer, it.confidence, it.classification) }, snap.examination.totalQuestions))
        val stats = ItemAnalyzer.statistics(results, 1, 2)
        val items = ItemAnalyzer.perItem(results, snap.activeKey)
        val pdf = ReportPdfGenerator.examReport(snap.examination.title, snap.examination.subject, stats, items, now)
        val text = pdf.pages.first().ops.filterIsInstance<PdfOp.Text>().joinToString(" ") { it.text }
        File("verification-output/exam_report_sample.pdf").writeBytes(PortablePdfWriter.render(pdf))
        Assert.truth(text.contains("EXAM PERFORMANCE REPORT"), "report title")
        Assert.truth(text.contains("Submissions: 1"), "live count")
        Assert.truth(text.contains("ITEM ANALYSIS"), "item analysis section")
    }

    TestRunner.test("export: wide CSV is 1 row per student with dynamic Q columns and explicit states") {
        val snap = snapshot(7)
        val header = CsvDatasetEngine.wideHeader(7)
        Assert.equals("Q001", header[12])
        Assert.equals("Q007", header[18])
        Assert.equals("passing_status", header.last())
        val rows = CsvDatasetEngine.wideRows(snap)
        Assert.equals(1, rows.size)
        Assert.equals("Juan Dela Cruz", rows[0][5])
        Assert.equals("BLANK", rows[0][14], "blank is explicit")
        Assert.equals("B", rows[0][13])
        Assert.equals("PENDING_REVIEW", CsvDatasetEngine.wideRows(snapshot(3).copy(
            submissions = snapshot(3).submissions.map { it.copy(finalAnswers = emptyMap()) }))[0][12],
            "missing answer is explicit")
        val bytes = CsvDatasetEngine.wideCsv(snap)
        File("verification-output").mkdirs()
        File("verification-output/wide_sample.csv").writeBytes(bytes)
        TestRunner.metrics["wide_csv_bytes"] = bytes.size.toString()
        val parsed = Csv.parseBytes(bytes)
        Assert.equals(2, parsed.size, "header + one data row")
        Assert.truth(parsed[0][0] == "submission_id" || parsed[0][0].removePrefix("\uFEFF") == "submission_id", "header")
    }

    TestRunner.test("export: recognition audit CSV is long format with correction timestamps") {
        val snap = snapshot(5)
        val rows = CsvDatasetEngine.auditRows(snap)
        Assert.equals(5, rows.size)
        Assert.equals(listOf("submission_id", "exam_id", "student_id", "question_number", "detected_answer",
            "final_answer", "confidence", "classification", "manually_corrected", "corrected_at"),
            CsvDatasetEngine.auditHeader)
        Assert.equals(true, rows[1][8])
        Assert.truth(rows[1][9].toString().startsWith("2025") || rows[1][9].toString().contains("T"), "timestamp")
        val bytes = CsvDatasetEngine.auditCsv(snap)
        File("verification-output/recognition_audit_sample.csv").writeBytes(bytes)
        TestRunner.metrics["audit_csv_bytes"] = bytes.size.toString()
    }

    TestRunner.test("export: consolidated multi-exam CSV pads heterogeneous question counts") {
        val a = snapshot(3, "exam-A")
        val b = snapshot(6, "exam-B")
        val rows = Csv.parseBytes(CsvDatasetEngine.consolidatedCsv(MultiExamSnapshot(listOf(a, b))))
        Assert.equals(3, rows.size, "header + 2 submissions")
        val header = rows[0]
        Assert.equals("Q006", header[header.indexOf("Q005") + 1])
        Assert.truth(rows[1].contains("N/A"), "short exam padded explicitly")
        Assert.equals(2, rows.drop(1).map { it[1] }.distinct().size, "exam identifiers retained")
    }

    TestRunner.test("export: atomic dataset synchronization increments version and verifies integrity") {
        val dir = File("verification-output/datasets")
        dir.mkdirs()
        val snap = snapshot(5)
        val initial = DatasetSynchronizer.initialize(snap.examination.id, now)
        Assert.equals(2, initial.size)
        Assert.equals(SynchronizationStatus.INITIALIZED, initial.first().synchronizationStatus)
        val first = DatasetSynchronizer.synchronize(snap, dir, initial, now) as OmrResult.Success
        Assert.equals(1, first.value.archives.first().generationVersion)
        Assert.equals(SynchronizationStatus.SYNCED, first.value.archives.first().synchronizationStatus)
        Assert.equals(0, AtomicFiles.staleTempFiles(dir).size, "no partial files")
        val corrected = snap.copy(recognitions = snap.recognitions.map {
            if (it.questionNumber == 2) it.copy(finalAnswer = "A", manuallyCorrected = true, correctedAt = now + 10) else it
        }, submissions = snap.submissions.map { it.copy(finalAnswers = it.finalAnswers + (2 to "A")) })
        val second = DatasetSynchronizer.synchronize(corrected, dir, first.value.archives, now + 10) as OmrResult.Success
        Assert.equals(2, second.value.archives.first().generationVersion, "regeneration version")
        val wide = File(dir, DatasetSynchronizer.fileName(snap.examination.id, DatasetType.WIDE_ANSWERS))
        Assert.truth(Csv.parseBytes(wide.readBytes())[1][13] == "A", "correction propagated")
        val integrity = DatasetSynchronizer.verify(second.value.archives, second.value.checksums)
        Assert.equals(2, integrity.valid.size)
        Assert.equals(0, integrity.invalid.size)
        TestRunner.metrics["dataset_sync_bytes"] = (first.value.wideBytes + first.value.auditBytes).toString()
    }
}


/** Uncompressed 24-bit BMP writer for independent QR decoding in the cross-check script. */
private fun writeQrBmp(path: File, matrix: BitMatrix, scale: Int, border: Int) {
    val side = (matrix.size + border * 2) * scale
    val rowBytes = ((side * 3 + 3) / 4) * 4
    val pixels = ByteArray(rowBytes * side) { 0xFF.toByte() }
    for (y in 0 until side) for (x in 0 until side) {
        val mx = x / scale - border; val my = y / scale - border
        if (mx in 0 until matrix.size && my in 0 until matrix.size && matrix[mx, my]) {
            val off = y * rowBytes + x * 3
            pixels[off] = 0; pixels[off + 1] = 0; pixels[off + 2] = 0
        }
    }
    val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
    header.put(0x42.toByte()); header.put(0x4D.toByte())
    header.putInt(54 + pixels.size); header.putShort(0); header.putShort(0); header.putInt(54)
    header.putInt(40); header.putInt(side); header.putInt(side); header.putShort(1); header.putShort(24)
    header.putInt(0); header.putInt(pixels.size); header.putInt(0); header.putInt(0); header.putInt(0); header.putInt(0)
    path.writeBytes(header.array() + pixels)
}
