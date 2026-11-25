package com.omrgrader.export

import com.omrgrader.omr.RasterImage
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Draw-command model shared by the portable writer and the Android PdfDocument adapter. */
enum class PdfFont { REGULAR, BOLD, MONO }

sealed class PdfOp {
    data class Text(val x: Double, val y: Double, val size: Double, val text: String,
                    val font: PdfFont = PdfFont.REGULAR, val gray: Double = 0.0) : PdfOp()
    data class Line(val x0: Double, val y0: Double, val x1: Double, val y1: Double,
                    val width: Double = 0.8, val gray: Double = 0.0) : PdfOp()
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double,
                    val width: Double = 0.8, val gray: Double = 0.0) : PdfOp()
    data class FilledRect(val x: Double, val y: Double, val w: Double, val h: Double,
                          val gray: Double = 0.0) : PdfOp()
    data class Circle(val x: Double, val y: Double, val r: Double,
                      val width: Double = 0.8, val gray: Double = 0.0) : PdfOp()
    data class FilledCircle(val x: Double, val y: Double, val r: Double,
                            val gray: Double = 0.0) : PdfOp()
    /** Lossless RGB raster from a decoded luminance image. */
    data class Image(val x: Double, val y: Double, val w: Double, val h: Double,
                     val raster: RasterImage) : PdfOp()
    /**
     * Original JPEG bytes embedded with DCTDecode. Used for archival page 1 so the
     * captured image is embedded without recompression; /Rotate handles EXIF orientation.
     */
    data class Jpeg(val x: Double, val y: Double, val w: Double, val h: Double,
                    val bytes: ByteArray, val sourceWidth: Int, val sourceHeight: Int,
                    val rotationDegrees: Int = 0) : PdfOp()
}

data class PdfPage(
    val widthPt: Double,
    val heightPt: Double,
    val ops: List<PdfOp>,
    /** PDF /Rotate, clockwise; used with embedded original JPEG + EXIF orientation. */
    val rotation: Int = 0
)

data class PdfDocumentModel(
    val pages: List<PdfPage>,
    val title: String = "OpenScantron",
    val author: String = "OpenScantron offline grader"
)

interface PdfRenderer {
    fun render(model: PdfDocumentModel): ByteArray
}

/**
 * Self-contained PDF 1.4 writer: real objects, xref table, FlateDecode raster XObjects,
 * DCTDecode JPEG XObjects, vector circles via Bezier curves, WinAnsi text. No network.
 */
object PortablePdfWriter : PdfRenderer {
    private const val KAPPA = 0.5522847498

    override fun render(model: PdfDocumentModel): ByteArray = build(model)

    fun build(model: PdfDocumentModel): ByteArray {
        require(model.pages.isNotEmpty()) { "a PDF needs at least one page" }
        val pageCount = model.pages.size
        var next = 3
        val pageObj = IntArray(pageCount) { next++ }
        val contentObj = IntArray(pageCount) { next++ }
        val fontRegular = next++
        val fontBold = next++
        val fontMono = next++
        val imageObjects = ArrayList<Triple<Int, PdfOp, Int>>() // object number, image op, page index
        model.pages.forEachIndexed { pageIndex, page ->
            page.ops.filter { it is PdfOp.Image || it is PdfOp.Jpeg }.forEach {
                imageObjects += Triple(next++, it, pageIndex)
            }
        }
        val infoObj = next++
        val totalObjects = next - 1

        val out = ByteArrayOutputStream()
        val offsets = LongArray(totalObjects + 1)
        fun ascii(text: String) { out.write(text.toByteArray(Charsets.US_ASCII)) }
        fun begin(obj: Int) {
            offsets[obj] = out.size().toLong()
            ascii("$obj 0 obj\n")
        }
        fun end() { ascii("endobj\n") }
        fun streamObject(obj: Int, payload: ByteArray, dictionary: String) {
            begin(obj)
            ascii(dictionary + "\nstream\n")
            out.write(payload)
            ascii("\nendstream\n")
            end()
        }
        fun objectBytes(obj: Int, dictionary: String) {
            begin(obj); ascii(dictionary + "\n"); end()
        }

        ascii("%PDF-1.4\n%OpenScantron-1\n")
        objectBytes(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = pageObj.joinToString(" ") { "$it 0 R" }
        objectBytes(2, "<< /Type /Pages /Count $pageCount /Kids [$kids] >>")

        val contents = Array(pageCount) { pageIndex ->
            val page = model.pages[pageIndex]
            val sb = StringBuilder()
            for (op in page.ops) appendOp(sb, op, page.heightPt, imageObjects, pageIndex)
            sb.toString().toByteArray(Charsets.ISO_8859_1)
        }
        model.pages.forEachIndexed { i, page ->
            val xobjects = imageObjects.withIndex().filter { it.value.third == i }
                .joinToString(" ") { "/Im${it.index} ${it.value.first} 0 R" }
            val resources = "<< /Font << /F1 $fontRegular 0 R /F2 $fontBold 0 R /F3 $fontMono 0 R >>" +
                (if (xobjects.isEmpty()) "" else " /XObject << $xobjects >>") + " >>"
            val rotate = if (page.rotation != 0) "/Rotate ${page.rotation} " else ""
            objectBytes(pageObj[i], "<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 ${fmt(page.widthPt)} ${fmt(page.heightPt)}] " +
                "$rotate/Resources $resources /Contents ${contentObj[i]} 0 R >>")
            streamObject(contentObj[i], contents[i], "<< /Length ${contents[i].size} >>")
        }
        objectBytes(fontRegular, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        objectBytes(fontBold, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        objectBytes(fontMono, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
        imageObjects.forEach { (obj, op, _) ->
            when (op) {
                is PdfOp.Image -> {
                    val data = rgbFlate(op.raster)
                    streamObject(obj, data,
                        "<< /Type /XObject /Subtype /Image /Width ${op.raster.width} " +
                            "/Height ${op.raster.height} /ColorSpace /DeviceRGB " +
                            "/BitsPerComponent 8 /Filter /FlateDecode /Length ${data.size} >>")
                }
                is PdfOp.Jpeg -> {
                    val jpegDimensions = jpegSize(op.bytes) ?: (op.sourceWidth to op.sourceHeight)
                    streamObject(obj, op.bytes,
                        "<< /Type /XObject /Subtype /Image /Width ${jpegDimensions.first} " +
                            "/Height ${jpegDimensions.second} /ColorSpace /DeviceRGB " +
                            "/BitsPerComponent 8 /Filter /DCTDecode /Length ${op.bytes.size} >>")
                }
                else -> Unit
            }
        }
        objectBytes(infoObj, "<< /Title (${escapeText(model.title)}) /Author (${escapeText(model.author)}) " +
            "/Producer (OpenScantron PortablePdfWriter) >>")

        val xref = out.size().toLong()
        ascii("xref\n0 ${totalObjects + 1}\n")
        ascii("0000000000 65535 f \n")
        for (i in 1..totalObjects) ascii("%010d 00000 n \n".format(offsets[i]))
        ascii("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R /Info $infoObj 0 R >>\n" +
            "startxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun appendOp(
        sb: StringBuilder, op: PdfOp, pageHeight: Double,
        images: List<Triple<Int, PdfOp, Int>>, pageIndex: Int
    ) {
        val f = { v: Double -> fmt(v) }
        val y = { v: Double -> f(pageHeight - v) }
        when (op) {
            is PdfOp.Text -> {
                val font = when (op.font) { PdfFont.REGULAR -> "F1"; PdfFont.BOLD -> "F2"; PdfFont.MONO -> "F3" }
                sb.append("BT /$font ${f(op.size)} Tf ${f(op.gray)} g ${f(op.x)} ${y(op.y)} Td (${escapeText(op.text)}) Tj ET\n")
            }
            is PdfOp.Line -> sb.append("${f(op.gray)} G ${f(op.width)} w ${f(op.x0)} ${y(op.y0)} m ${f(op.x1)} ${y(op.y1)} l S\n")
            is PdfOp.Rect -> sb.append("${f(op.gray)} G ${f(op.width)} w ${f(op.x)} ${y(op.y + op.h)} ${f(op.w)} ${f(-op.h)} re S\n")
            is PdfOp.FilledRect -> sb.append("${f(op.gray)} g ${f(op.x)} ${y(op.y + op.h)} ${f(op.w)} ${f(-op.h)} re f\n")
            is PdfOp.Circle -> {
                sb.append("${f(op.gray)} G ${f(op.width)} w\n")
                circlePath(sb, op.x, op.y, op.r, f, y)
                sb.append("S\n")
            }
            is PdfOp.FilledCircle -> {
                sb.append("${f(op.gray)} g\n")
                circlePath(sb, op.x, op.y, op.r, f, y)
                sb.append("f\n")
            }
            is PdfOp.Image -> {
                val stable = images.indexOfFirst { it.second === op && it.third == pageIndex }
                if (stable >= 0) sb.append("q ${f(op.w)} 0 0 ${f(op.h)} ${f(op.x)} ${y(op.y + op.h)} cm /Im$stable Do Q\n")
            }
            is PdfOp.Jpeg -> {
                val stable = images.indexOfFirst { it.second === op && it.third == pageIndex }
                if (stable >= 0) sb.append("q ${f(op.w)} 0 0 ${f(op.h)} ${f(op.x)} ${y(op.y + op.h)} cm /Im$stable Do Q\n")
            }
        }
    }

    private fun circlePath(sb: StringBuilder, cx: Double, cy: Double, r: Double,
                           f: (Double) -> String, y: (Double) -> String) {
        val k = r * KAPPA
        sb.append("${f(cx + r)} ${y(cy)} m ")
        sb.append("${f(cx + r)} ${y(cy + k)} ${f(cx + k)} ${y(cy + r)} ${f(cx)} ${y(cy + r)} c ")
        sb.append("${f(cx - k)} ${y(cy + r)} ${f(cx - r)} ${y(cy + k)} ${f(cx - r)} ${y(cy)} c ")
        sb.append("${f(cx - r)} ${y(cy - k)} ${f(cx - k)} ${y(cy - r)} ${f(cx)} ${y(cy - r)} c ")
        sb.append("${f(cx + k)} ${y(cy - r)} ${f(cx + r)} ${y(cy - k)} ${f(cx + r)} ${y(cy)} c h\n")
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF ||
            (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or
                    (bytes[i + 8].toInt() and 0xFF)
                if (width > 0 && height > 0) return width to height
            }
            i += 2 + length
        }
        return null
    }

    private fun rgbFlate(image: RasterImage): ByteArray {
        val raw = ByteArray(image.width * image.height * 3)
        var i = 0
        for (py in 0 until image.height) for (px in 0 until image.width) {
            val v = image.at(px, py).coerceIn(0, 255)
            raw[i++] = v.toByte(); raw[i++] = v.toByte(); raw[i++] = v.toByte()
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw); deflater.finish()
        val buffer = ByteArray(65536)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun fmt(v: Double): String = "%.3f".format(java.util.Locale.US, v)

    private fun escapeText(value: String): String {
        val sb = StringBuilder()
        for (c in value) when (c) {
            '(' -> sb.append("\\(")
            ')' -> sb.append("\\)")
            '\\' -> sb.append("\\\\")
            '\n', '\r' -> sb.append(' ')
            else -> {
                val code = c.code
                if (code in 32..126) sb.append(c)
                else if (code in 160..255) sb.append('\\').append("%03o".format(code))
                else sb.append('?')
            }
        }
        return sb.toString()
    }
}
