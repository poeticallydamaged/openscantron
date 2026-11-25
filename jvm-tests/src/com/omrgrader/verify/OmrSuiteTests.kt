package com.omrgrader.verify

import com.omrgrader.core.Json
import com.omrgrader.core.OmrResult
import com.omrgrader.domain.*
import com.omrgrader.omr.*
import java.io.File

fun registerOmrSuiteTests() {
    TestRunner.test("omr: homography, marker geometry and image quality math") {
        val canonical = listOf(PointD(10.0, 10.0), PointD(200.0, 10.0),
            PointD(200.0, 300.0), PointD(10.0, 300.0))
        val shifted = canonical.map { PointD(it.x * 1.1 + 7, it.y * 0.9 + 3) }
        val h = Homography.solve(canonical, shifted)
        Assert.truth(h != null, "homography solvable")
        canonical.forEachIndexed { i, p ->
            val mapped = h!!.map(p.x, p.y)
            Assert.close(shifted[i].x, mapped.x, 1e-6, "x mapping $i")
            Assert.close(shifted[i].y, mapped.y, 1e-6, "y mapping $i")
        }
        val quad = Quadrilateral(canonical)
        Assert.equals(190.0 * 290.0, quad.area())
        val blank = RasterImage(100, 100).apply { fill(255) }
        val noisy = blank.copy(); noisy[50, 50] = 0
        Assert.truth(PortableImageOps.laplacianVariance(noisy) > PortableImageOps.laplacianVariance(blank),
            "sharpness metric responds to edges")
        val stats = PortableImageOps.brightness(blank)
        Assert.equals(255.0, stats.mean)
    }


    TestRunner.test("omr: threshold calibration derives bounds from measured ROI fills") {
        val layout = LayoutFactory.build("exam-calibration", PageSize.A4, 10,
            listOf("A", "B", "C", "D"), 8)
        val registry = InMemoryLayoutRegistry(listOf(layout))
        val cases = (1..4).map { SyntheticSheetGenerator.generate(it * 101, SyntheticCondition.CLEAN, layout) }
        val roster = InMemoryRosterLookup(cases.mapIndexed { i, c -> Student("s$i", c.studentNumber, "S", "A") })
        val report = ThresholdCalibration.recommend(cases, registry, roster)
        Assert.equals(4, report.samples)
        Assert.truth(report.markedFillMin > report.blankFillMax, "marks separate from blanks")
        Assert.truth(report.thresholds.markFillRatio > report.thresholds.blankFillRatio, "ordered thresholds")
        Assert.truth(report.thresholds.markFillRatio in 0.30..0.62, "calibrated mark threshold")
        TestRunner.metrics["calibration_separation"] = report.estimatedSeparation.toString()
    }

    TestRunner.test("omr: synthetic accuracy suite over 16 adverse conditions") {
        val layout = LayoutFactory.build("exam-accuracy", PageSize.A4, 20,
            listOf("A", "B", "C", "D"), 8)
        val registry = InMemoryLayoutRegistry(listOf(layout))
        val engine = OmrEngine()
        val seedsPerCondition = 8
        var sheets = 0; var registration = 0
        var singleTotal = 0; var singleOk = 0
        var blankTotal = 0; var blankOk = 0
        var multiTotal = 0; var multiOk = 0
        var ambiguousTotal = 0; var ambiguousFlagged = 0
        var idOk = 0
        val perCondition = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
        for (condition in SyntheticCondition.entries) {
            var conditionSheets = 0; var conditionRegistration = 0
            var conditionItems = 0; var conditionItemOk = 0
            for (seed in 1..seedsPerCondition) {
                sheets++
                val case = SyntheticSheetGenerator.generate(seed * 7919 + condition.ordinal, condition, layout)
                val roster = InMemoryRosterLookup(listOf(
                    Student("student-$seed", case.studentNumber, "Synthetic", "Section A")
                ))
                val outcome = engine.process(OmrRequest(case.image, registry, roster, nowMillis = 1_760_000_000_000L + sheets))
                if (outcome is OmrResult.Failure) {
                    failures += "${condition.name}/$seed: ${outcome.error.message}"
                    continue
                }
                val result = (outcome as OmrResult.Success).value
                registration++; conditionRegistration++
                if (result.recognizedStudentNumber == case.studentNumber) idOk++
                Assert.equals(layout.templateCode, result.templateCode, "template decoded")
                for (q in 1..layout.questionCount) {
                    val prediction = result.questions.first { it.questionNumber == q }
                    val expectedState = case.expectedClassification[q]!!
                    when (expectedState) {
                        Classification.SINGLE_MARK -> {
                            singleTotal++; conditionItems++
                            val good = prediction.classification == Classification.SINGLE_MARK &&
                                prediction.finalAnswer == case.expectedAnswers[q]
                            if (good) { singleOk++; conditionItemOk++ }
                        }
                        Classification.BLANK -> {
                            blankTotal++; conditionItems++
                            if (prediction.classification == Classification.BLANK) { blankOk++; conditionItemOk++ }
                        }
                        Classification.MULTIPLE_MARKS -> {
                            multiTotal++; conditionItems++
                            if (prediction.classification == Classification.MULTIPLE_MARKS) { multiOk++; conditionItemOk++ }
                        }
                        Classification.AMBIGUOUS -> {
                            ambiguousTotal++
                            if (prediction.classification != Classification.SINGLE_MARK &&
                                prediction.confidence < OmrThresholds().reviewConfidenceBelow) ambiguousFlagged++
                        }
                        Classification.INVALID -> Unit
                    }
                }
                conditionSheets++
            }
            perCondition[condition.name] =
                "registered=$conditionRegistration/$conditionSheets items=$conditionItemOk/$conditionItems"
        }
        fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
        val metrics = mapOf(
            "sheets" to sheets, "registration_success" to registration,
            "registration_rate" to ratio(registration, sheets),
            "single_mark_total" to singleTotal, "single_mark_correct" to singleOk,
            "single_mark_accuracy" to ratio(singleOk, singleTotal),
            "blank_total" to blankTotal, "blank_correct" to blankOk,
            "blank_accuracy" to ratio(blankOk, blankTotal),
            "multiple_total" to multiTotal, "multiple_correct" to multiOk,
            "multiple_accuracy" to ratio(multiOk, multiTotal),
            "ambiguous_total" to ambiguousTotal, "ambiguous_flagged" to ambiguousFlagged,
            "ambiguous_flag_rate" to ratio(ambiguousFlagged, ambiguousTotal),
            "student_id_correct" to idOk, "student_id_accuracy" to ratio(idOk, sheets),
            "failures" to failures.size
        )
        metrics.forEach { (k, v) -> TestRunner.metrics[k] = v.toString() }
        val accuracyJson = Json.stringify(Json.obj(
            "conditions" to Json.arr(SyntheticCondition.entries.map { Json.str(it.name) }),
            "seedsPerCondition" to Json.num(seedsPerCondition),
            "questionCount" to Json.num(layout.questionCount),
            "perCondition" to Json.obj(*perCondition.map { (k, v) -> k to Json.str(v) }.toTypedArray()),
            "metrics" to Json.obj(*metrics.map { (k, v) ->
                k to Json.num((v as Number).toDouble())
            }.toTypedArray()),
            "failures" to Json.arr(failures.map { Json.str(it) })
        ))
        File("verification-output").mkdirs()
        File("verification-output/omr-accuracy.json").writeText(accuracyJson)
        println("OMR metrics: " + metrics.entries.joinToString { "${it.key}=${it.value}" })
        if (failures.isNotEmpty()) println("OMR failures: " + failures.take(8).joinToString("; "))
        Assert.truth(sheets == SyntheticCondition.entries.size * seedsPerCondition, "all sheets generated")
        Assert.truth(ratio(registration, sheets) >= 0.98, "registration >= 98%")
        Assert.truth(ratio(singleOk, singleTotal) >= 0.99, "single-mark >= 99%")
        Assert.truth(ratio(blankOk, blankTotal) >= 0.99, "blank >= 99%")
        Assert.truth(ratio(multiOk, multiTotal) >= 0.98, "multiple-mark >= 98%")
        Assert.truth(ratio(idOk, sheets) >= 0.99, "student ID >= 99%")
        Assert.truth(ratio(ambiguousFlagged, ambiguousTotal) >= 0.95, "ambiguous marks flagged")
    }
}
