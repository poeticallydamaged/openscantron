package com.omrgrader.verify

import com.omrgrader.core.Json
import com.omrgrader.core.JsonValue
import java.io.File

class AssertionError(message: String) : RuntimeException(message)

object Assert {
    fun equals(expected: Any?, actual: Any?, label: String = "") {
        if (expected != actual) throw AssertionError("$label expected <$expected> but was <$actual>")
    }
    fun truth(value: Boolean, label: String) { if (!value) throw AssertionError(label) }
    fun notNull(value: Any?, label: String): Any = value ?: throw AssertionError("$label was null")
    fun close(expected: Double, actual: Double, tolerance: Double, label: String) {
        if (kotlin.math.abs(expected - actual) > tolerance)
            throw AssertionError("$label expected $expected +/- $tolerance but was $actual")
    }
    inline fun <reified T : Throwable> throws(crossinline block: () -> Unit): T {
        try { block() } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}: ${e.message}")
        }
        throw AssertionError("expected ${T::class.java.simpleName} but nothing was thrown")
    }
}

data class TestOutcome(val name: String, val passed: Boolean, val detail: String, val millis: Long)

object TestRunner {
    private val tests = ArrayList<Pair<String, () -> Unit>>()
    val outcomes = ArrayList<TestOutcome>()
    val metrics = LinkedHashMap<String, String>()

    fun test(name: String, block: () -> Unit) { tests += name to block }

    fun run(): Int {
        for ((name, block) in tests) {
            val start = System.currentTimeMillis()
            try {
                block()
                outcomes += TestOutcome(name, true, "ok", System.currentTimeMillis() - start)
                println("PASS  $name (${System.currentTimeMillis() - start} ms)")
            } catch (e: Throwable) {
                outcomes += TestOutcome(name, false, e::class.java.simpleName + ": " + (e.message ?: ""),
                    System.currentTimeMillis() - start)
                println("FAIL  $name -> ${e::class.java.simpleName}: ${e.message}")
            }
        }
        val failed = outcomes.count { !it.passed }
        val json = Json.stringify(Json.obj(
            "total" to Json.num(outcomes.size),
            "passed" to Json.num(outcomes.size - failed),
            "failed" to Json.num(failed),
            "metrics" to Json.obj(*metrics.map { (k, v) -> k to Json.str(v) }.toTypedArray()),
            "tests" to Json.arr(outcomes.map {
                Json.obj("name" to Json.str(it.name), "passed" to Json.bool(it.passed),
                    "detail" to Json.str(it.detail), "millis" to Json.num(it.millis.toDouble()))
            })
        ))
        File("verification-output").mkdirs()
        File("verification-output/jvm-results.json").writeText(json)
        println("\n${outcomes.size - failed}/${outcomes.size} tests passed; report: verification-output/jvm-results.json")
        return if (failed == 0) 0 else 1
    }
}
