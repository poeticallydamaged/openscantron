package com.omrgrader.export

import com.omrgrader.core.AppError

/** Compact square boolean matrix; x = column, y = row. */
class BitMatrix(val size: Int) {
    private val data = BooleanArray(size * size)
    operator fun get(x: Int, y: Int): Boolean = data[y * size + x]
    operator fun set(x: Int, y: Int, value: Boolean) { data[y * size + x] = value }
    fun inBounds(x: Int, y: Int) = x in 0 until size && y in 0 until size
    fun countDark(): Int = data.count { it }
    fun copy(): BitMatrix = BitMatrix(size).also { System.arraycopy(data, 0, it.data, 0, data.size) }
    fun rendered(scale: Int = 1): String = buildString {
        for (y in 0 until size) {
            for (x in 0 until size) append(if (get(x, y)) '#' else '.')
            append('\n')
        }
    }
}

interface QrCodec {
    fun encode(payload: String): BitMatrix
    /** Null when the matrix cannot be decoded; never throws a fabricated result. */
    fun decode(matrix: BitMatrix): String?
}

/**
 * Real QR encoder/decoder, byte mode, ECC level L, versions 1..10.
 * Implements ISO/IEC 18004 bit stream, Reed-Solomon GF(256) codewords, BCH format
 * information, function patterns, mask selection and zig-zag data placement.
 * The Android layer additionally supplies a ZXing-backed codec for camera scans.
 */
object PortableQrCodec : QrCodec {
    private const val ECC_L_ORDINAL = 1 // qrcode/ZXing level-L format ordinal
    private const val G15 = (1 shl 10) or (1 shl 8) or (1 shl 5) or (1 shl 4) or (1 shl 2) or (1 shl 1) or 1
    private const val G18 = (1 shl 12) or (1 shl 11) or (1 shl 10) or (1 shl 9) or (1 shl 8) or (1 shl 5) or (1 shl 2) or 1
    private const val G15_MASK = (1 shl 14) or (1 shl 12) or (1 shl 10) or (1 shl 4) or (1 shl 1)
    private const val PAD0 = 0xEC
    private const val PAD1 = 0x11

    private data class RsBlock(val count: Int, val total: Int, val data: Int)
    /** (count, total, data) groups for ECC-L, versions 1..10. */
    private val RS_L = arrayOf(
        arrayOf(RsBlock(1, 26, 19)),
        arrayOf(RsBlock(1, 44, 34)),
        arrayOf(RsBlock(1, 70, 55)),
        arrayOf(RsBlock(1, 100, 80)),
        arrayOf(RsBlock(1, 134, 108)),
        arrayOf(RsBlock(2, 86, 68)),
        arrayOf(RsBlock(2, 98, 78)),
        arrayOf(RsBlock(2, 121, 97)),
        arrayOf(RsBlock(2, 146, 116)),
        arrayOf(RsBlock(2, 86, 68), RsBlock(2, 87, 69))
    )
    private val ALIGN_POSITIONS = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26),
        intArrayOf(6, 30), intArrayOf(6, 34), intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50)
    )
    private val GF_EXP = IntArray(256)
    private val GF_LOG = IntArray(256)

    init {
        for (i in 0..7) GF_EXP[i] = 1 shl i
        for (i in 8 until 256) GF_EXP[i] = GF_EXP[i - 4] xor GF_EXP[i - 5] xor GF_EXP[i - 6] xor GF_EXP[i - 8]
        for (i in 0 until 255) GF_LOG[GF_EXP[i]] = i
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0
    else GF_EXP[(GF_LOG[a and 0xFF] + GF_LOG[b and 0xFF]) % 255]

    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) out[i + j] = out[i + j] xor gfMul(a[i], b[j])
        return out
    }

    private fun generator(ec: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until ec) poly = polyMul(poly, intArrayOf(1, GF_EXP[i]))
        return poly
    }

    private fun remainder(data: IntArray, ec: Int): IntArray {
        val gen = generator(ec)
        val work = IntArray(data.size + ec)
        data.copyInto(work)
        for (i in data.indices) {
            val coef = work[i]
            if (coef != 0) for (j in gen.indices) work[i + j] = work[i + j] xor gfMul(gen[j], coef)
        }
        return work.copyOfRange(data.size, work.size)
    }

    /** Syndrome of a complete (data + EC) codeword; all zero means RS-valid. */
    private fun syndrome(codeword: IntArray, ec: Int): IntArray {
        val gen = generator(ec)
        val work = codeword.copyOf()
        for (i in 0 until codeword.size - ec) {
            val coef = work[i]
            if (coef != 0) for (j in gen.indices) work[i + j] = work[i + j] xor gfMul(gen[j], coef)
        }
        return work.copyOfRange(codeword.size - ec, codeword.size)
    }

    private fun blocks(version: Int): List<RsBlock> = RS_L[version - 1].flatMap { group ->
        (0 until group.count).map { RsBlock(1, group.total, group.data) }
    }

    private fun dataCapacity(version: Int) = blocks(version).sumOf { it.data }

    private fun bchDigit(d: Int): Int {
        var value = d; var digit = 0
        while (value != 0) { digit++; value = value shr 1 }
        return digit
    }

    private fun bchTypeInfo(data: Int): Int {
        var d = data shl 10
        while (bchDigit(d) - bchDigit(G15) >= 0) d = d xor (G15 shl (bchDigit(d) - bchDigit(G15)))
        return ((data shl 10) or d) xor G15_MASK
    }

    private fun bchTypeNumber(data: Int): Int {
        var d = data shl 12
        while (bchDigit(d) - bchDigit(G18) >= 0) d = d xor (G18 shl (bchDigit(d) - bchDigit(G18)))
        return (data shl 12) or d
    }

    private class BitBuffer {
        val bytes = ArrayList<Int>()
        var length = 0
            private set
        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) putBit(((value shr i) and 1) == 1)
        }
        fun putBit(bit: Boolean) {
            val index = length / 8
            if (bytes.size <= index) bytes.add(0)
            if (bit) bytes[index] = bytes[index] or (0x80 shr (length % 8))
            length++
        }
    }

    private fun codewords(payload: String, version: Int): IntArray {
        val data = payload.toByteArray(Charsets.UTF_8)
        val buffer = BitBuffer()
        buffer.put(4, 4) // byte mode
        buffer.put(data.size, if (version < 10) 8 else 16)
        data.forEach { buffer.put(it.toInt() and 0xFF, 8) }
        val limit = dataCapacity(version) * 8
        if (buffer.length > limit) throw AppError.Validation("QR payload exceeds version capacity")
        repeat(minOf(limit - buffer.length, 4)) { buffer.putBit(false) }
        while (buffer.length % 8 != 0) buffer.putBit(false)
        var padIndex = 0
        while (buffer.length < limit) {
            buffer.put(if (padIndex % 2 == 0) PAD0 else PAD1, 8)
            padIndex++
        }
        val raw = buffer.bytes.toIntArray()
        val rsBlocks = blocks(version)
        var offset = 0
        val dc = ArrayList<IntArray>()
        val ec = ArrayList<IntArray>()
        for (block in rsBlocks) {
            val current = raw.copyOfRange(offset, offset + block.data)
            offset += block.data
            val ecCount = block.total - block.data
            dc.add(current)
            ec.add(remainder(current, ecCount))
        }
        val out = ArrayList<Int>(rsBlocks.sumOf { it.total })
        for (i in 0 until rsBlocks.maxOf { it.data }) for (block in dc) if (i < block.size) out.add(block[i])
        for (i in 0 until rsBlocks.maxOf { it.total - it.data }) for (block in ec) if (i < block.size) out.add(block[i])
        return out.toIntArray()
    }

    /** -1 = data module, 0 = light function, 1 = dark function. */
    /** -1 = data module, 0 = light function, 1 = dark function. */
    private fun blank(version: Int): Array<IntArray> {
        val n = 17 + 4 * version
        val m = Array(n) { IntArray(n) { -1 } }
        fun probe(row: Int, col: Int) {
            for (r in -1..7) for (c in -1..7) {
                val y = row + r; val x = col + c
                if (y !in 0 until n || x !in 0 until n) continue
                val dark = (r in 0..6 && (c == 0 || c == 6)) ||
                    (c in 0..6 && (r == 0 || r == 6)) ||
                    (r in 2..4 && c in 2..4)
                m[y][x] = if (dark) 1 else 0
            }
        }
        probe(0, 0); probe(n - 7, 0); probe(0, n - 7)
        val pos = ALIGN_POSITIONS[version - 1]
        for (row in pos) for (col in pos) {
            if (m[row][col] != -1) continue
            for (r in -2..2) for (c in -2..2) {
                m[row + r][col + c] = if (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0)) 1 else 0
            }
        }
        for (i in 8 until n - 8) {
            if (m[i][6] == -1) m[i][6] = if (i % 2 == 0) 1 else 0
            if (m[6][i] == -1) m[6][i] = if (i % 2 == 0) 1 else 0
        }
        // Format information positions are filled by setFormat() before data placement,
        // exactly as ISO/IEC 18004 specifies (no broad reservation of neighbouring cells).
        m[n - 8][8] = 1 // fixed dark module
        if (version >= 7) {
            val bits = bchTypeNumber(version)
            for (i in 0 until 18) {
                val dark = ((bits shr i) and 1) == 1
                m[i / 3][i % 3 + n - 11] = if (dark) 1 else 0
                m[i % 3 + n - 11][i / 3] = if (dark) 1 else 0
            }
        }
        return m
    }

    private fun maskBit(pattern: Int, row: Int, col: Int): Boolean = when (pattern) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        7 -> ((row * col) % 3 + (row + col) % 2) % 2 == 0
        else -> false
    }

    private fun formatBits(mask: Int): Int = bchTypeInfo((ECC_L_ORDINAL shl 3) or mask)

    private fun setFormat(m: Array<IntArray>, mask: Int, test: Boolean = false) {
        val n = m.size
        val bits = formatBits(mask)
        for (i in 0 until 15) {
            val dark = !test && ((bits shr i) and 1) == 1
            val value = if (dark) 1 else 0
            when {
                i < 6 -> m[i][8] = value
                i < 8 -> m[i + 1][8] = value
                else -> m[n - 15 + i][8] = value
            }
            when {
                i < 8 -> m[8][n - i - 1] = value
                i < 9 -> m[8][15 - i] = value
                else -> m[8][15 - i - 1] = value
            }
        }
        m[n - 8][8] = if (test) 0 else 1
    }

    /** Iterates the data-module coordinates in the exact QR zig-zag order. */
    private inline fun forEachDataModule(m: Array<IntArray>, action: (row: Int, col: Int) -> Unit) {
        val n = m.size
        var inc = -1
        var row = n - 1
        var baseCol = n - 1
        while (baseCol > 0) {
            // ISO zig-zag: iterate columns in steps of two; when the column reaches the
            // left finder area, shift left by one. The derived `col` never becomes the
            // basis for the next step (that would skip column pairs).
            var col = baseCol
            if (col <= 6) col--
            while (true) {
                for (c in intArrayOf(col, col - 1)) if (m[row][c] == -1) action(row, c)
                row += inc
                if (row < 0 || row >= n) { row -= inc; inc = -inc; break }
            }
            baseCol -= 2
        }
    }

    private fun buildMatrix(version: Int, words: IntArray, mask: Int): Array<IntArray> {
        val m = blank(version)
        setFormat(m, mask)
        var byteIndex = 0
        var bitIndex = 7
        forEachDataModule(m) { row, col ->
            var dark = if (byteIndex < words.size) ((words[byteIndex] shr bitIndex) and 1) == 1 else false
            if (maskBit(mask, row, col)) dark = !dark
            m[row][col] = if (dark) 1 else 0
            bitIndex--
            if (bitIndex < 0) { byteIndex++; bitIndex = 7 }
        }
        return m
    }

    private fun penalty(m: Array<IntArray>): Int {
        val n = m.size
        var score = 0
        // Rule 1: runs of five or more identical modules.
        for (axis in 0..1) for (i in 0 until n) {
            var run = 1
            for (j in 1 until n) {
                val a = if (axis == 0) m[i][j - 1] else m[j - 1][i]
                val b = if (axis == 0) m[i][j] else m[j][i]
                if (a == b) run++ else { if (run >= 5) score += run - 2; run = 1 }
            }
            if (run >= 5) score += run - 2
        }
        // Rule 2: 2x2 blocks of the same colour.
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val v = m[y][x]
            if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) score += 3
        }
        // Rule 4: deviation from 50% dark.
        val dark = m.sumOf { row -> row.count { it == 1 } }
        score += (kotlin.math.abs(dark * 100.0 / (n * n) - 50.0) / 5.0).toInt() * 10
        return score
    }

    override fun encode(payload: String): BitMatrix {
        if (payload.isEmpty()) throw AppError.Validation("QR payload is empty")
        val capacityBits = { v: Int -> dataCapacity(v) * 8 }
        var version = 1
        while (version <= 10) {
            val overhead = 4 + if (version < 10) 8 else 16
            val needed = overhead + payload.toByteArray(Charsets.UTF_8).size * 8
            if (needed <= capacityBits(version)) break
            version++
        }
        if (version > 10) throw AppError.Validation("QR payload too large for versions 1..10")
        val words = codewords(payload, version)
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        var best: Array<IntArray>? = null
        for (mask in 0..7) {
            val m = buildMatrix(version, words, mask)
            val score = penalty(m)
            if (score < bestScore) { bestScore = score; bestMask = mask; best = m }
        }
        val matrix = BitMatrix(17 + 4 * version)
        val chosen = best ?: throw AppError.Validation("QR mask selection failed")
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) matrix[x, y] = chosen[y][x] == 1
        // bestMask is retained implicitly by the format bits; expose it for tests.
        lastMask = bestMask
        return matrix
    }

    @Volatile
    var lastMask: Int = 0
        private set

    override fun decode(matrix: BitMatrix): String? {
        for (version in 1..10) {
            val expected = 17 + 4 * version
            if (matrix.size != expected) continue
            val rsBlocks = blocks(version)
            val totalWords = rsBlocks.sumOf { it.total }
            for (mask in 0..7) {
                val blank = blank(version)
                setFormat(blank, mask, test = true)
                val bits = ArrayList<Boolean>(totalWords * 8)
                forEachDataModule(blank) { row, col ->
                    var dark = matrix[col, row]
                    if (maskBit(mask, row, col)) dark = !dark
                    bits.add(dark)
                }
                if (bits.size < totalWords * 8) continue
                val words = IntArray(totalWords) { i ->
                    var value = 0
                    for (b in 0..7) value = (value shl 1) or if (bits[i * 8 + b]) 1 else 0
                    value
                }
                val decoded = decodeCodewords(words, version, rsBlocks) ?: continue
                return decoded
            }
        }
        return null
    }

    private fun decodeCodewords(words: IntArray, version: Int, rsBlocks: List<RsBlock>): String? {
        // De-interleave data and EC codewords, then verify every RS block.
        val dataBlocks = Array(rsBlocks.size) { IntArray(rsBlocks[it].data) }
        val ecBlocks = Array(rsBlocks.size) { IntArray(rsBlocks[it].total - rsBlocks[it].data) }
        var offset = 0
        for (i in 0 until rsBlocks.maxOf { it.data }) for (b in rsBlocks.indices) {
            if (i < dataBlocks[b].size) dataBlocks[b][i] = words[offset++]
        }
        for (i in 0 until rsBlocks.maxOf { it.total - it.data }) for (b in rsBlocks.indices) {
            if (i < ecBlocks[b].size) ecBlocks[b][i] = words[offset++]
        }
        for (b in rsBlocks.indices) {
            val full = dataBlocks[b] + ecBlocks[b]
            if (syndrome(full, ecBlocks[b].size).any { it != 0 }) return null
        }
        val stream = dataBlocks.flatMap { it.toList() }
        var bitPos = 0
        fun read(bits: Int): Int {
            var value = 0
            repeat(bits) {
                val byte = stream.getOrNull(bitPos / 8) ?: return -1
                value = (value shl 1) or ((byte shr (7 - bitPos % 8)) and 1)
                bitPos++
            }
            return value
        }
        if (read(4) != 4) return null // byte mode
        val lengthBits = if (version < 10) 8 else 16
        val length = read(lengthBits)
        if (length <= 0 || length > stream.size * 8 / 8) return null
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            val b = read(8)
            if (b < 0) return null
            bytes[i] = b.toByte()
        }
        return try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { null }
    }
}
