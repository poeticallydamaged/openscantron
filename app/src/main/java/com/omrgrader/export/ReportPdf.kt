package com.omrgrader.export

import com.omrgrader.domain.ExamStatistics
import com.omrgrader.domain.ItemStatistics
import com.omrgrader.domain.Student
import com.omrgrader.domain.SubmissionSnapshot
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Performance reports; all figures come from Room-derived domain objects. */
object ReportPdfGenerator {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    private fun instant(value: Long) = dateFmt.format(Instant.ofEpochMilli(value))
    private fun pct(v: Double) = "%.1f%%".format(java.util.Locale.US, v)

    fun examReport(
        examTitle: String,
        subject: String,
        stats: ExamStatistics,
        items: List<ItemStatistics>,
        generatedAt: Long
    ): PdfDocumentModel {
        val ops = mutableListOf<PdfOp>()
        var y = 46.0
        fun line(text: String, size: Double = 10.0, font: PdfFont = PdfFont.REGULAR, step: Double = 15.0) {
            ops += PdfOp.Text(46.0, y, size, text, font); y += step
        }
        line("EXAM PERFORMANCE REPORT", 17.0, PdfFont.BOLD, 24.0)
        line("Examination: $examTitle    Subject: $subject", 10.0)
        line("Generated: ${instant(generatedAt)} UTC", 9.0, PdfFont.MONO, 20.0)
        line("Submissions: ${stats.submissionCount}   Max points: ${"%.2f".format(stats.maxPoints)}", 10.0)
        line("Mean: ${"%.2f".format(stats.mean)}   Median: ${"%.2f".format(stats.median)}   High: ${"%.2f".format(stats.high)}   Low: ${"%.2f".format(stats.low)}", 10.0)
        line("Pass: ${stats.passCount} (${pct(stats.passPercentage)})   Fail: ${stats.failCount} (${pct(stats.failPercentage)})   Pending review: ${stats.pendingCount}", 10.0)
        line("Archived PDFs: ${stats.archivedPdfCount}   Synced CSV datasets: ${stats.syncedCsvCount}", 10.0, step = 24.0)
        line("ITEM ANALYSIS  (difficulty index p = correct / attempted)", 12.0, PdfFont.BOLD, 18.0)
        line("Q    Key   Correct   Incorrect   Blank   Invalid   Pending   Top distractor   p", 9.0, PdfFont.MONO, 14.0)
        for (item in items) {
            if (y > 790) break
            line(
                "%-4d %-5s %-8s %-10s %-7s %-9s %-9s %-15s %s".format(
                    java.util.Locale.US,
                    item.questionNumber, item.keyAnswer, pct(item.correctPercentage),
                    pct(item.incorrectPercentage), pct(item.blankPercentage),
                    pct(item.invalidPercentage), pct(item.pendingPercentage),
                    item.mostChosenDistractor ?: "-",
                    item.difficultyIndex?.let { "%.2f".format(it) } ?: "n/a"
                ), 8.0, PdfFont.MONO, 12.0
            )
        }
        return PdfDocumentModel(listOf(PdfPage(595.2756, 841.8898, ops)), title = "Exam report: $examTitle")
    }

    fun classReport(
        section: String,
        students: List<Student>,
        submissions: List<SubmissionSnapshot>,
        generatedAt: Long
    ): PdfDocumentModel {
        val ops = mutableListOf<PdfOp>()
        var y = 46.0
        fun line(text: String, size: Double = 9.0, font: PdfFont = PdfFont.MONO, step: Double = 13.0) {
            ops += PdfOp.Text(46.0, y, size, text, font); y += step
        }
        line("CLASS PERFORMANCE REPORT - $section", 15.0, PdfFont.BOLD, 24.0)
        line("Generated: ${instant(generatedAt)} UTC    Students: ${students.size}", 9.0, step = 20.0)
        line("Student no.     Name                         Score      %       Status", 9.0, PdfFont.BOLD, 15.0)
        val byStudent = submissions.associateBy { it.studentId }
        for (student in students) {
            val sub = byStudent[student.id]
            line("%-15s %-28s %-10s %-8s %s".format(
                java.util.Locale.US,
                student.studentNumber, student.fullName.take(27),
                sub?.score?.let { "%.2f".format(it) } ?: "n/a",
                sub?.percentage?.let { "%.1f".format(it) } ?: "n/a",
                sub?.passingStatus ?: "NO_SUBMISSION"
            ))
            if (y > 800) break
        }
        return PdfDocumentModel(listOf(PdfPage(595.2756, 841.8898, ops)), title = "Class report: $section")
    }

    fun studentReport(
        student: Student,
        examTitle: String,
        submission: SubmissionSnapshot,
        gradingItems: List<com.omrgrader.domain.ItemGrade>,
        generatedAt: Long
    ): PdfDocumentModel {
        val ops = mutableListOf<PdfOp>()
        var y = 46.0
        fun line(text: String, size: Double = 10.0, font: PdfFont = PdfFont.REGULAR, step: Double = 15.0) {
            ops += PdfOp.Text(46.0, y, size, text, font); y += step
        }
        line("STUDENT RESULT REPORT", 17.0, PdfFont.BOLD, 24.0)
        line("Examination: $examTitle", 10.0)
        line("Student: ${student.fullName} (${student.studentNumber})   Section: ${student.section}", 10.0)
        line("Scanned: ${instant(submission.scannedAt)} UTC   Submission: ${submission.id}", 9.0, PdfFont.MONO, 20.0)
        line("Score: ${"%.2f".format(submission.score ?: 0.0)} / ${"%.2f".format(submission.maxScore ?: 0.0)}   " +
            "Percentage: ${"%.1f".format(submission.percentage ?: 0.0)}%   Status: ${submission.passingStatus}", 10.0, step = 24.0)
        line("Q     Your answer     Key       Outcome          Points", 9.0, PdfFont.BOLD, 15.0)
        for (item in gradingItems) {
            line("%-5d %-16s %-9s %-15s %s".format(java.util.Locale.US,
                item.questionNumber, item.finalAnswer, item.keyAnswer, item.outcome.name,
                "%.2f".format(item.points)), 8.0, PdfFont.MONO, 12.0)
            if (y > 800) break
        }
        return PdfDocumentModel(listOf(PdfPage(595.2756, 841.8898, ops)), title = "Student report")
    }
}
