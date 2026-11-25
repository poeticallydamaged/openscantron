package com.omrgrader.verify

import com.omrgrader.core.*
import java.io.File

fun registerCoreTests() {
    TestRunner.test("core: JSON codec round-trip with escapes and unicode") {
        val value = Json.obj(
            "name" to Json.str("A, \"B\"\nC"),
            "count" to Json.num(42),
            "items" to Json.arr(listOf(Json.bool(true), JsonValue.Null, Json.str("é")))
        )
        val text = Json.stringify(value)
        val parsed = Json.parse(text) as JsonValue.Obj
        Assert.equals("A, \"B\"\nC", parsed.string("name"))
        Assert.equals(42, parsed.int("count"))
        Assert.equals(3, parsed.array("items").size)
        Assert.equals(JsonValue.Null, parsed.array("items")[1])
    }

    TestRunner.test("core: RFC 4180 CSV escaping, embedded newlines and BOM") {
        val header = listOf("a", "b", "c")
        val rows = listOf(listOf("plain", "with,comma", "line1\nline2"), listOf("\"quoted\"", "", "tail"))
        val bytes = Csv.write(header, rows)
        Assert.truth(bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())), "UTF-8 BOM")
        val parsed = Csv.parseBytes(bytes)
        Assert.equals(3, parsed.size)
        Assert.equals(listOf("a", "b", "c"), parsed[0])
        Assert.equals("with,comma", parsed[1][1])
        Assert.equals("line1\nline2", parsed[1][2])
        Assert.equals("\"quoted\"", parsed[2][0])
        Assert.truth(String(bytes, Charsets.UTF_8).contains("\r\n"), "CRLF line endings")
    }

    TestRunner.test("core: atomic write leaves no temp file and SHA-256 is stable") {
        val dir = File("verification-output/atomic")
        dir.mkdirs()
        val payload = "alpha,beta\n1,2\n".toByteArray(Charsets.UTF_8)
        val target = AtomicFiles.write(dir, "dataset.csv", payload, FixedClock(1000))
        Assert.equals(payload.size.toLong(), target.length())
        Assert.equals(Sha256.of(payload), Sha256.of(target))
        Assert.equals(0, AtomicFiles.staleTempFiles(dir).size)
        Assert.truth(Sha256.verify(payload, Sha256.of(payload)), "checksum verify")
    }

    TestRunner.test("core: OmrResult maps errors without swallowing AppError types") {
        val ok = OmrResult.runCatching { 7 }.map { it * 2 }
        Assert.equals(14, (ok as OmrResult.Success).value)
        val bad = OmrResult.runCatching { throw AppError.Validation("bad") }
        Assert.truth(bad is OmrResult.Failure, "failure preserved")
        Assert.truth((bad as OmrResult.Failure).error is AppError.Validation, "typed error")
    }
}
