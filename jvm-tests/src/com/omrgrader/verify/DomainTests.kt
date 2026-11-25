package com.omrgrader.verify

import com.omrgrader.core.*
import com.omrgrader.domain.*

fun registerDomainTests() {
    val now = 1_760_000_000_000L
    fun exam(q: Int = 5, scoring: ScoringConfiguration = ScoringConfiguration()) = ExamAdmin.create(
        "Midterm", "Math", "", now, q, listOf("A", "B", "C", "D"), scoring, now, "exam-1"
    ).examination

    TestRunner.test("domain: exam presets, custom counts, duplicate and archive") {
        Assert.equals(listOf(10, 20, 30, 40, 50, 60, 75, 100), ExamPresets.questionCounts)
        val original = exam(37)
        Assert.equals(37, original.totalQuestions)
        Assert.equals(37.0, original.totalPoints)
        val copy = ExamAdmin.duplicate(original, now + 1, "exam-2")
        Assert.equals("Midterm (copy)", copy.title)
        Assert.equals(null, copy.activeAnswerKeyVersionId)
        Assert.truth(!copy.isArchived, "copy is active")
        Assert.truth(ExamAdmin.archive(original, now + 2).isArchived, "archive flag")
        Assert.truth(ExamPresets.isCustom(37), "custom count detected")
        Assert.throws<AppError.Validation> { ExamAdmin.create("", "M", "", now, 10, listOf("A"), ScoringConfiguration(), now) }
    }

    TestRunner.test("domain: answer key versioning and controlled regrade request") {
        val e = exam(5)
        val answers = (1..5).associateWith { "A" }
        val first = AnswerKeyVersionService.create(e, answers, listOf("A", "B", "C", "D"), emptyList(), now, "key-1").key
        Assert.equals(1, first.version)
        Assert.throws<AppError.Validation> {
            AnswerKeyVersionService.create(e, answers - 3, listOf("A", "B", "C", "D"), listOf(first), now)
        }
        val edited = AnswerKeyVersionService.editActiveKey(
            e, first, answers + (3 to "B"), listOf("A", "B", "C", "D"),
            listOf(first), listOf("sub-1", "sub-2"), now + 1, "key-2"
        )
        Assert.equals(2, edited.newKey.version)
        Assert.equals("key-2", edited.examination.activeAnswerKeyVersionId)
        Assert.truth(edited.regradeRequired, "regrade required")
        Assert.equals(listOf("sub-1", "sub-2"), edited.affectedSubmissionIds)
        Assert.equals("A", first.answers[3], "historical key is immutable")
    }

    TestRunner.test("domain: grading equal, weighted, deductions, partial credit, pending") {
        val key = AnswerKey("key", "exam-1", 1, (1..4).associateWith { "A" }, ScoringConfiguration(), now)
        val recs = listOf("A", "B", "BLANK", "MULTIPLE").mapIndexed { i, answer ->
            QuestionRecognition("r$i", "sub", i + 1, answer, answer, 0.9,
                when (answer) { "BLANK" -> Classification.BLANK; "MULTIPLE" -> Classification.MULTIPLE_MARKS; else -> Classification.SINGLE_MARK },
                alternatives = if (answer == "MULTIPLE") listOf("A", "B") else emptyList())
        }
        val result = GradingEngine.grade(key, recs, 4)
        Assert.equals(1, result.correctCount)
        Assert.equals(1, result.incorrectCount)
        Assert.equals(1, result.blankCount)
        Assert.equals(1, result.invalidCount)
        Assert.equals(1, result.pendingReviewCount)
        Assert.equals(GradingEngine.PENDING, result.passingStatus)
        Assert.equals(GradingStatus.PENDING_REVIEW, GradingEngine.statusFor(result))

        val weighted = ScoringConfiguration(pointsPerCorrect = 2.0, incorrectDeduction = 0.5,
            weights = mapOf(1 to 3.0, 2 to 1.0), multipleMarkPolicy = MultipleMarkPolicy.AWARD_ZERO)
        val weightedResult = GradingEngine.grade(key, recs, 4, weighted)
        Assert.equals(6.0, weightedResult.items[0].points)
        Assert.equals(12.0, weightedResult.maxScore)
        Assert.equals(5.0, weightedResult.score)

        val partial = ScoringConfiguration(partialCredit = true)
        val partialResult = GradingEngine.grade(key, recs, 4, partial)
        Assert.equals(1, partialResult.partialCreditCount)
        Assert.equals(0.5, partialResult.items[3].points)

        val zero = ScoringConfiguration(pointsPerCorrect = 0.0000001, weights = (1..4).associateWith { 0.0 })
        val zeroResult = GradingEngine.grade(key, recs, 4, zero)
        Assert.equals(0.0, zeroResult.percentage)
        Assert.truth(zeroResult.warnings.contains("max_score_zero_percentage_forced_to_zero"), "division by zero guarded")
    }

    TestRunner.test("domain: item analysis KPIs and difficulty index") {
        val key = AnswerKey("key", "exam-1", 1, (1..2).associateWith { "A" }, ScoringConfiguration(), now)
        fun result(a: String, b: String): GradingResult = GradingEngine.grade(key, listOf(
            QuestionRecognition("1", "s", 1, a, a, 1.0, Classification.SINGLE_MARK),
            QuestionRecognition("2", "s", 2, b, b, 1.0, Classification.SINGLE_MARK)
        ), 2)
        val results = listOf(result("A", "B"), result("A", "A"), result("C", "A"))
        val stats = ItemAnalyzer.statistics(results, 3, 2)
        Assert.equals(3, stats.submissionCount)
        Assert.equals(2.0, stats.high)
        Assert.equals(1.0, stats.low)
        Assert.equals(1, stats.passCount)
        Assert.equals(3, stats.archivedPdfCount)
        val items = ItemAnalyzer.perItem(results, key)
        Assert.equals(2, items.size)
        Assert.equals("C", items[0].mostChosenDistractor)
        Assert.equals(2.0 / 3.0, items[0].difficultyIndex!!)
    }

    TestRunner.test("domain: duplicate detection requires an explicit decision") {
        val existing = listOf(Submission("sub-1", "exam-1", "student-1", "tpl", "key", "batch", emptyMap(), emptyMap(),
            0.0, 0.0, GradingStatus.GRADED, ProcessingStatus.ARCHIVED, "fingerprint", now))
        val incoming = existing[0].copy(id = "sub-2", imageFingerprint = "fingerprint")
        val match = DuplicateDetector.find(existing, "exam-1", "student-1", "fingerprint")
        Assert.truth(match != null, "duplicate found")
        Assert.truth(match!!.byStudent && match.byFingerprint, "both signals")
        val id = match.existingSubmissionId
        Assert.equals(1, DuplicateDetector.apply(DuplicateResolution.DISCARD, existing, incoming, id).size)
        Assert.equals(1, DuplicateDetector.apply(DuplicateResolution.REPLACE, existing, incoming, id).size)
        Assert.equals("sub-2", DuplicateDetector.apply(DuplicateResolution.REPLACE, existing, incoming, id).first().id)
        Assert.equals(2, DuplicateDetector.apply(DuplicateResolution.RETAIN, existing, incoming, id).size)
    }

    TestRunner.test("domain: roster CSV import detects duplicates and invalid rows") {
        val csv = ("student_number,full_name,section,additional_information\r\n" +
            "2026-001,Juan Dela Cruz,Section A,note\r\n" +
            "2026-001,Duplicate,Section B,\r\n" +
            ",Missing number,Section A,\r\n" +
            "2026-002,Ana,Section C,\r\n").toByteArray(Charsets.UTF_8)
        val existing = listOf(Student("s", "2026-002", "Existing Ana", "Section C", ""))
        val result = RosterImport.parse(csv, existing)
        Assert.truth(result.headerRecognized, "header")
        Assert.equals(1, result.valid.size)
        Assert.equals("Juan Dela Cruz", result.valid.first().fullName)
        Assert.equals(listOf("2026-001", "2026-002"), result.duplicates)
        Assert.equals(listOf(4), result.invalidRows)
    }

    TestRunner.test("domain: batch counters compose without loss") {
        val confirmed = BatchCounters.CAPTURED + BatchCounters.IDENTIFIED +
            BatchCounters.ARCHIVED + BatchCounters.CSV_SYNCED
        Assert.equals(1, confirmed.scanned)
        Assert.equals(1, confirmed.identified)
        Assert.equals(1, confirmed.pdfsArchived)
        Assert.equals(1, confirmed.csvSynced)
        Assert.equals(0, confirmed.pending)
        val review = BatchCounters.CAPTURED + BatchCounters.REVIEW
        Assert.equals(1, review.needsReview)
        Assert.equals(0, review.pending)
        val unrecognized = BatchCounters.CAPTURED + BatchCounters.UNRECOGNIZED
        Assert.equals(1, unrecognized.unrecognized)
        Assert.equals(0, unrecognized.pending)
    }
}
