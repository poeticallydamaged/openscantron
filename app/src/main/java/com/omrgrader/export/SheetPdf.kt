package com.omrgrader.export

import com.omrgrader.omr.LayoutFactory
import com.omrgrader.omr.QrSheet
import com.omrgrader.omr.SheetLayout
import kotlin.math.min

/** Generates printable A4 / US Letter answer sheets from the persisted normalized layout. */
object BlankSheetPdfGenerator {
    fun pageDimensions(layout: SheetLayout): Pair<Double, Double> = when (layout.pageSize) {
        com.omrgrader.domain.PageSize.A4 -> 595.2756 to 841.8898
        com.omrgrader.domain.PageSize.LETTER -> 612.0 to 792.0
    }

    fun generate(
        layout: SheetLayout,
        copies: Int = 1,
        qrCodec: QrCodec = PortableQrCodec,
        includeInstructions: Boolean = true
    ): PdfDocumentModel {
        require(copies in 1..200) { "copies must be 1..200" }
        val page = buildPage(layout, qrCodec, includeInstructions)
        return PdfDocumentModel(List(copies) { page }, title = "Answer sheet ${layout.templateCode}")
    }

    fun preview(layout: SheetLayout, qrCodec: QrCodec = PortableQrCodec): PdfDocumentModel =
        generate(layout, 1, qrCodec)

    private fun buildPage(layout: SheetLayout, qrCodec: QrCodec, instructions: Boolean): PdfPage {
        val (pageW, pageH) = pageDimensions(layout)
        val sx = pageW / layout.canonicalWidth
        val sy = pageH / layout.canonicalHeight
        val scale = min(sx, sy)
        val ops = ArrayList<PdfOp>()
        fun x(nx: Double) = layout.canonicalX(nx) * sx
        fun y(ny: Double) = layout.canonicalY(ny) * sy
        fun text(nx: Double, ny: Double, size: Double, value: String, font: PdfFont = PdfFont.REGULAR) {
            ops += PdfOp.Text(x(nx), y(ny), size, value, font)
        }

        // Four corner geometric registration markers (primary alignment mechanism).
        for (marker in layout.markers) {
            val cx = x(marker.x); val cy = y(marker.y); val s = marker.sizePx * scale
            ops += PdfOp.FilledRect(cx - s / 2, cy - s / 2, s, s, 0.0)
            ops += PdfOp.FilledRect(cx - s * 0.27, cy - s * 0.27, s * 0.54, s * 0.54, 1.0)
        }
        val tab = layout.indexTab
        ops += PdfOp.FilledRect(x(tab.x), y(tab.y), tab.w * layout.innerWidth * sx,
            tab.h * layout.innerHeight * sy, 0.0)

        // Header, identification fields and instructions.
        text(0.02, 0.012, 12.0, "OPEN SCANTRON ANSWER SHEET", PdfFont.BOLD)
        text(0.02, 0.042, 8.0, "Student Name: __________________________________________", PdfFont.REGULAR)
        text(0.02, 0.072, 8.0, "Class / Section: __________________   Date: ____________", PdfFont.REGULAR)
        text(0.02, 0.102, 8.0, "Exam Code: ${layout.templateCode}", PdfFont.MONO)
        text(0.52, 0.042, 8.0, "Use a #2 pencil. Fill the circle completely.", PdfFont.REGULAR)
        text(0.52, 0.067, 8.0, "Erase cleanly to change an answer.", PdfFont.REGULAR)
        text(0.52, 0.092, 8.0, "One mark per question. Do not fold or staple.", PdfFont.REGULAR)
        if (instructions) {
            text(0.02, 0.145, 8.0, "STUDENT ID: fill one digit per column (0-9)", PdfFont.BOLD)
            text(0.52, 0.145, 8.0, "Answer choices: ${layout.answerChoices.joinToString(" ")}", PdfFont.BOLD)
        }

        // Template QR metadata (no student PII).
        val qrRect = layout.qrPixelRect()
        val qrSide = min(qrRect.w * sx, qrRect.h * sy)
        val qrX = qrRect.x * sx + (qrRect.w * sx - qrSide) / 2
        val qrY = qrRect.y * sy + (qrRect.h * sy - qrSide) / 2
        val matrix = qrCodec.encode(layout.metadataPayload())
        val cell = qrSide / (matrix.size + QrSheet.QUIET_MODULES * 2)
        ops += PdfOp.FilledRect(qrX, qrY, qrSide, qrSide, 1.0)
        for (my in 0 until matrix.size) for (mx in 0 until matrix.size) {
            if (!matrix[mx, my]) continue
            ops += PdfOp.FilledRect(
                qrX + (mx + QrSheet.QUIET_MODULES) * cell,
                qrY + (my + QrSheet.QUIET_MODULES) * cell,
                cell, cell, 0.0
            )
        }

        // Student number 0-9 bubble grid.
        for (spec in layout.idBubbles) {
            ops += PdfOp.Circle(x(spec.x), y(spec.y), spec.radiusPx * scale, 0.7)
            if (spec.column == 0) text(spec.x - 0.018, spec.y + 0.004, 6.0, spec.digit.toString(), PdfFont.MONO)
        }
        for (col in 0 until layout.idColumns) {
            val spec = layout.idBubbles.firstOrNull { it.column == col } ?: continue
            text(spec.x - 0.006, 0.325, 6.0, (col + 1).toString(), PdfFont.MONO)
        }

        // Uniformly spaced, numbered answer bubbles.
        val groups = layout.bubbles.groupBy { it.question }
        for ((q, specs) in groups) {
            val first = specs.first()
            text(first.x - 0.028, first.y + 0.004, 6.5, q.toString().padStart(3, '0'), PdfFont.MONO)
            for (spec in specs) ops += PdfOp.Circle(x(spec.x), y(spec.y), spec.radiusPx * scale, 0.7)
            if (q <= layout.questionCount && q <= 25) {
                for (spec in specs) text(spec.x - 0.004, 0.335, 6.0, spec.label, PdfFont.BOLD)
            }
        }
        ops += PdfOp.Line(x(0.02), y(0.335), x(0.98), y(0.335), 0.6)
        text(0.02, 0.985, 6.0, "${layout.pageSize.name} | ${layout.templateCode} | v${layout.version} | ${layout.questionCount} items", PdfFont.MONO)
        return PdfPage(pageW, pageH, ops)
    }
}
