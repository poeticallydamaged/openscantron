package com.omrgrader.core

/** Small, strict, dependency-free JSON codec (objects, arrays, strings, numbers, bool, null). */
sealed class JsonValue {
    data class Obj(val values: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = values[key]
        fun string(key: String, default: String = ""): String = (values[key] as? Str)?.value ?: default
        fun int(key: String, default: Int = 0): Int = (values[key] as? Num)?.value?.toInt() ?: default
        fun long(key: String, default: Long = 0L): Long = (values[key] as? Num)?.value?.toLong() ?: default
        fun double(key: String, default: Double = 0.0): Double = (values[key] as? Num)?.value ?: default
        fun bool(key: String, default: Boolean = false): Boolean = (values[key] as? Bool)?.value ?: default
        fun array(key: String): List<JsonValue> = (values[key] as? Arr)?.values ?: emptyList()
        fun obj(key: String): Obj? = values[key] as? Obj
    }
    data class Arr(val values: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
}

object Json {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw AppError.Validation("trailing JSON content")
        return value
    }

    fun stringify(value: JsonValue): String {
        val out = StringBuilder()
        write(value, out)
        return out.toString()
    }

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(pairs.toMap())
    fun arr(values: Iterable<JsonValue>): JsonValue.Arr = JsonValue.Arr(values.toList())
    fun str(value: String): JsonValue.Str = JsonValue.Str(value)
    fun num(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble())
    fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.values.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(k, out); out.append(':'); write(v, out)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.values.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> {
                val d = value.value
                if (d == d.toLong().toDouble()) out.append(d.toLong()) else out.append(d)
            }
            is JsonValue.Bool -> out.append(value.value)
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0
        fun atEnd(): Boolean = pos >= text.length
        fun skipWhitespace() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw AppError.Validation("unexpected end of JSON")
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> readNumber()
            }
        }
        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) throw AppError.Validation("invalid JSON literal")
            pos += literal.length
        }
        private fun readObject(): JsonValue.Obj {
            pos++; skipWhitespace()
            val map = LinkedHashMap<String, JsonValue>()
            if (!atEnd() && text[pos] == '}') { pos++; return JsonValue.Obj(map) }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw AppError.Validation("expected object key")
                val key = readString(); skipWhitespace()
                if (atEnd() || text[pos] != ':') throw AppError.Validation("expected ':'")
                pos++; map[key] = readValue(); skipWhitespace()
                if (atEnd()) throw AppError.Validation("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(map) }
                    else -> throw AppError.Validation("expected ',' or '}'")
                }
            }
        }
        private fun readArray(): JsonValue.Arr {
            pos++; skipWhitespace()
            val list = ArrayList<JsonValue>()
            if (!atEnd() && text[pos] == ']') { pos++; return JsonValue.Arr(list) }
            while (true) {
                list.add(readValue()); skipWhitespace()
                if (atEnd()) throw AppError.Validation("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(list) }
                    else -> throw AppError.Validation("expected ',' or ']'")
                }
            }
        }
        private fun readString(): String {
            pos++
            val out = StringBuilder()
            while (!atEnd()) {
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw AppError.Validation("bad escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw AppError.Validation("bad unicode escape")
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw AppError.Validation("bad escape")
                        }
                    }
                    c.code < 0x20 -> throw AppError.Validation("control character in string")
                    else -> out.append(c)
                }
            }
            throw AppError.Validation("unterminated string")
        }
        private fun readNumber(): JsonValue.Num {
            val start = pos
            while (!atEnd() && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            if (start == pos) throw AppError.Validation("invalid JSON value")
            val raw = text.substring(start, pos)
            val d = raw.toDoubleOrNull() ?: throw AppError.Validation("invalid number")
            return JsonValue.Num(d)
        }
    }
}
