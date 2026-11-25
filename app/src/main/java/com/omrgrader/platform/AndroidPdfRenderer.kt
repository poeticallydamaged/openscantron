package com.omrgrader.platform

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import com.omrgrader.export.PdfDocumentModel
import com.omrgrader.export.PdfOp
import com.omrgrader.export.PdfRenderer
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android native PdfDocument adapter consuming the same draw-command list as
 * PortablePdfWriter. Used for on-device preview; archive PDFs use the portable
 * writer so their bytes and checksums are identical on every device.
 */
@Singleton
class AndroidPdfRenderer @Inject constructor() : PdfRenderer {
    override fun render(model: PdfDocumentModel): ByteArray {
        val document = PdfDocument()
        try {
            model.pages.forEachIndexed { index, page ->
                val pageInfo = PdfDocument.PageInfo.Builder(page.widthPt.toInt(), page.heightPt.toInt(), index + 1).create()
                val pdfPage = document.startPage(pageInfo)
                val canvas = pdfPage.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                if (page.rotation != 0) canvas.rotate(page.rotation.toFloat(),
                    page.widthPt.toFloat() / 2f, page.heightPt.toFloat() / 2f)
                for (op in page.ops) draw(canvas, paint, op)
                document.finishPage(pdfPage)
            }
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun draw(canvas: Canvas, paint: Paint, op: PdfOp) {
        paint.reset()
        paint.isAntiAlias = true
        when (op) {
            is PdfOp.Text -> {
                paint.color = gray(op.gray); paint.textSize = op.size.toFloat()
                paint.typeface = when (op.font) {
                    com.omrgrader.export.PdfFont.BOLD -> android.graphics.Typeface.DEFAULT_BOLD
                    com.omrgrader.export.PdfFont.MONO -> android.graphics.Typeface.MONOSPACE
                    else -> android.graphics.Typeface.DEFAULT
                }
                canvas.drawText(op.text, op.x.toFloat(), op.y.toFloat(), paint)
            }
            is PdfOp.Line -> {
                paint.color = gray(op.gray); paint.strokeWidth = op.width.toFloat()
                canvas.drawLine(op.x0.toFloat(), op.y0.toFloat(), op.x1.toFloat(), op.y1.toFloat(), paint)
            }
            is PdfOp.Rect -> {
                paint.color = gray(op.gray); paint.style = Paint.Style.STROKE; paint.strokeWidth = op.width.toFloat()
                canvas.drawRect(op.x.toFloat(), op.y.toFloat(), (op.x + op.w).toFloat(), (op.y + op.h).toFloat(), paint)
            }
            is PdfOp.FilledRect -> {
                paint.color = gray(op.gray); paint.style = Paint.Style.FILL
                canvas.drawRect(op.x.toFloat(), op.y.toFloat(), (op.x + op.w).toFloat(), (op.y + op.h).toFloat(), paint)
            }
            is PdfOp.Circle -> {
                paint.color = gray(op.gray); paint.style = Paint.Style.STROKE; paint.strokeWidth = op.width.toFloat()
                canvas.drawCircle(op.x.toFloat(), op.y.toFloat(), op.r.toFloat(), paint)
            }
            is PdfOp.FilledCircle -> {
                paint.color = gray(op.gray); paint.style = Paint.Style.FILL
                canvas.drawCircle(op.x.toFloat(), op.y.toFloat(), op.r.toFloat(), paint)
            }
            is PdfOp.Jpeg -> {
                val bitmap = BitmapFactory.decodeByteArray(op.bytes, 0, op.bytes.size)
                    ?: return
                val scale = op.w / bitmap.width
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(op.x.toFloat(), op.y.toFloat())
                }
                // The page-level canvas rotation already applies EXIF /Rotate.
                paint.style = Paint.Style.FILL
                canvas.drawBitmap(bitmap, matrix, paint)
                bitmap.recycle()
            }
            is PdfOp.Image -> {
                val w = op.raster.width; val h = op.raster.height
                val pixels = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val v = op.raster.at(x, y).coerceIn(0, 255)
                    pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                val bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h,
                    android.graphics.Bitmap.Config.ARGB_8888)
                val rect = android.graphics.Rect(op.x.toInt(), op.y.toInt(),
                    (op.x + op.w).toInt(), (op.y + op.h).toInt())
                canvas.drawBitmap(bitmap, null, rect, paint)
                bitmap.recycle()
            }
        }
    }

    private fun gray(value: Double): Int {
        val v = (255 * (1.0 - value.coerceIn(0.0, 1.0))).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}
