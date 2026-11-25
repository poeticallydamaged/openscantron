package com.omrgrader.core

/** RFC 4180 compliant UTF-8 CSV reader/writer implemented in Kotlin. */
object Csv {
    const val EOL = "\r\n"

    /** Quote only when required; embedded quotes are doubled. */
    fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
            value.startsWith(' ') || value.endsWith(' ')
        if (!needsQuote) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    fun row(values: Iterable<Any?>): String = values.joinToString(",") { v ->
        escape(v?.toString() ?: "")
    } + EOL

    fun write(header: List<String>, rows: Iterable<List<Any?>>, excelBom: Boolean = true): ByteArray {
        val sb = StringBuilder()
        if (excelBom) sb.append('\uFEFF')
        sb.append(row(header))
        rows.forEach { sb.append(row(it)) }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun writeBytes(header: List<String>, rows: Iterable<List<Any?>>, excelBom: Boolean = true): ByteArray =
        write(header, rows, excelBom)

    /** Parses RFC 4180 text; supports quoted commas, embedded newlines and doubled quotes. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var fieldWasQuoted = false
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when (c) {
                    '"' -> if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                    else -> field.append(c)
                }
                c == '"' && field.isEmpty() && !fieldWasQuoted -> { inQuotes = true; fieldWasQuoted = true }
                c == ',' -> { row.add(field.toString()); field.setLength(0); fieldWasQuoted = false }
                c == '\r' -> { /* handled with \n below */ }
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0); fieldWasQuoted = false
                    rows.add(row.toList()); row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldWasQuoted) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        if (inQuotes) throw AppError.Validation("unterminated quoted CSV field")
        return rows
    }

    fun parseBytes(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        return parse(text)
    }
}
