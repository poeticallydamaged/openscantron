package com.omrgrader.export

import com.omrgrader.core.Csv
import com.omrgrader.domain.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** All CSV artifacts are derived, deterministic projections of a Room snapshot. */
object CsvDatasetEngine {
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private fun instant(value: Long) = iso.format(Instant.ofEpochMilli(value))
    private fun date(value: Long) = day.format(Instant.ofEpochMilli(value))
    private fun num(value: Double) = "%.4f".format(java.util.Locale.US, value)
    private fun q(n: Int) = "Q" + n.toString().padStart(3, '0')

    val wideFixedPrefix = listOf(
        "submission_id", "exam_id", "exam_title", "subject", "student_id", "student_name",
        "section", "exam_date", "scanned_at", "answer_key_version", "batch_id", "grading_status"
    )
    val wideFixedSuffix = listOf(
        "correct_count", "incorrect_count", "blank_count", "invalid_count",
        "pending_review_count", "score", "max_score", "percentage", "passing_status"
    )
    val auditHeader = listOf(
        "submission_id", "exam_id", "student_id", "question_number", "detected_answer",
        "final_answer", "confidence", "classification", "manually_corrected", "corrected_at"
    )

    fun wideHeader(questionCount: Int): List<String> =
        wideFixedPrefix + (1..questionCount).map { q(it) } + wideFixedSuffix

    private fun recognitionsFor(snapshot: DatasetSnapshot, submissionId: String): List<QuestionRecognition> =
        snapshot.recognitions.filter { it.submissionId == submissionId }.map {
            QuestionRecognition(
                id = "$submissionId-${it.questionNumber}",
                submissionId = submissionId,
                questionNumber = it.questionNumber,
                recognizedAnswer = it.detectedAnswer,
                finalAnswer = it.finalAnswer,
                confidence = it.confidence,
                classification = it.classification,
                manuallyCorrected = it.manuallyCorrected,
                correctionNote = "",
                correctedAt = it.correctedAt,
                alternatives = it.alternatives
            )
        }

    fun wideRows(snapshot: DatasetSnapshot): List<List<Any?>> {
        val exam = snapshot.examination
        val students = snapshot.students.associateBy { it.id }
        return snapshot.submissions.filter { it.gradingStatus != GradingStatus.DISCARDED }
            .sortedBy { it.scannedAt }
            .map { sub ->
                val student = sub.studentId?.let { students[it] }
                val grade = GradingEngine.grade(
                    snapshot.activeKey, recognitionsFor(snapshot, sub.id),
                    exam.totalQuestions, snapshot.activeKey.scoringRules
                )
                val answers = (1..exam.totalQuestions).map { q ->
                    sub.finalAnswers[q] ?: CellValue.PENDING_REVIEW
                }
                buildList {
                    addAll(listOf<Any?>(
                        sub.id, exam.id, exam.title, exam.subject,
                        student?.studentNumber ?: "UNASSIGNED",
                        student?.fullName ?: "UNASSIGNED",
                        student?.section ?: "", date(exam.date), instant(sub.scannedAt),
                        snapshot.activeKey.version, sub.batchId, sub.gradingStatus.name
                    ))
                    addAll(answers)
                    addAll(listOf<Any?>(
                        grade.correctCount, grade.incorrectCount, grade.blankCount,
                        grade.invalidCount, grade.pendingReviewCount,
                        num(sub.score ?: grade.score), num(sub.maxScore ?: grade.maxScore),
                        num(sub.percentage ?: grade.percentage), sub.passingStatus
                    ))
                }
            }
    }

    fun wideCsv(snapshot: DatasetSnapshot): ByteArray = Csv.write(
        wideHeader(snapshot.examination.totalQuestions), wideRows(snapshot)
    )

    fun auditRows(snapshot: DatasetSnapshot): List<List<Any?>> {
        val students = snapshot.students.associateBy { it.id }
        val validSubmissions = snapshot.submissions.filter { it.gradingStatus != GradingStatus.DISCARDED }
            .associateBy { it.id }
        return snapshot.recognitions.filter { validSubmissions.containsKey(it.submissionId) }
            .sortedWith(compareBy({ it.submissionId }, { it.questionNumber }))
            .map { rec ->
                val sub = validSubmissions.getValue(rec.submissionId)
                val number = sub.studentId?.let { students[it]?.studentNumber } ?: "UNASSIGNED"
                listOf<Any?>(
                    rec.submissionId, snapshot.examination.id, number, rec.questionNumber,
                    rec.detectedAnswer, rec.finalAnswer, num(rec.confidence),
                    rec.classification.name, rec.manuallyCorrected,
                    rec.correctedAt?.let { instant(it) } ?: ""
                )
            }
    }

    fun auditCsv(snapshot: DatasetSnapshot): ByteArray = Csv.write(auditHeader, auditRows(snapshot))

    /** Explicit teacher-triggered multi-exam export with heterogeneous question counts. */
    fun consolidatedCsv(multi: MultiExamSnapshot): ByteArray {
        val maxQuestions = multi.exams.maxOfOrNull { it.examination.totalQuestions } ?: 0
        val header = wideHeader(maxQuestions)
        val rows = multi.exams.flatMap { snapshot ->
            wideRows(snapshot).map { row ->
                // Each row already starts with the exam identifiers. Pad question columns
                // beyond this exam's count with an explicit N/A (never an empty string).
                val exam = snapshot.examination
                val qStart = wideFixedPrefix.size
                val qEnd = qStart + exam.totalQuestions
                row.take(qStart) + row.subList(qStart, qEnd) +
                    List(maxQuestions - exam.totalQuestions) { "N/A" } + row.drop(qEnd)
            }
        }
        return Csv.write(header, rows)
    }

    fun rosterCsv(students: List<Student>): ByteArray = Csv.write(
        listOf("student_number", "full_name", "section", "additional_information"),
        students.map { listOf(it.studentNumber, it.fullName, it.section, it.additionalInformation) }
    )

    fun summaryCsv(
        examTitle: String,
        stats: ExamStatistics,
        items: List<ItemStatistics>
    ): ByteArray {
        val header = listOf("section", "metric", "value")
        val rows = mutableListOf<List<Any?>>()
        fun add(metric: String, value: Any?) { rows += listOf("EXAM", metric, value) }
        add("exam_title", examTitle)
        add("submission_count", stats.submissionCount)
        add("max_points", num(stats.maxPoints))
        add("mean", num(stats.mean)); add("median", num(stats.median))
        add("high", num(stats.high)); add("low", num(stats.low))
        add("pass_count", stats.passCount); add("fail_count", stats.failCount)
        add("pending_reviews", stats.pendingCount)
        add("archived_pdf_count", stats.archivedPdfCount)
        add("synced_csv_count", stats.syncedCsvCount)
        items.forEach { item ->
            rows += listOf("ITEM_${q(item.questionNumber)}", "correct_percentage", num(item.correctPercentage))
            rows += listOf("ITEM_${q(item.questionNumber)}", "difficulty_index",
                item.difficultyIndex?.let { num(it) } ?: "n/a")
            rows += listOf("ITEM_${q(item.questionNumber)}", "most_chosen_distractor",
                item.mostChosenDistractor ?: "-")
        }
        return Csv.write(header, rows)
    }
}
