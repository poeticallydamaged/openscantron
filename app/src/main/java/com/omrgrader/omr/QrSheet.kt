package com.omrgrader.omr

import com.omrgrader.export.BitMatrix
import com.omrgrader.export.QrCodec
import kotlin.math.min

/** Draws and samples a QR code with a four-module quiet zone inside a square sheet region. */
object QrSheet {
    const val QUIET_MODULES = 4

    fun draw(image: RasterImage, rectPx: NormRect, matrix: BitMatrix) {
        val side = min(rectPx.w, rectPx.h)
        val cell = side / (matrix.size + QUIET_MODULES * 2)
        val originX = rectPx.x + (rectPx.w - side) / 2
        val originY = rectPx.y + (rectPx.h - side) / 2
        image.drawFilledRect(originX, originY, originX + side, originY + side, 255)
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (!matrix[x, y]) continue
            val px = originX + (x + QUIET_MODULES) * cell
            val py = originY + (y + QUIET_MODULES) * cell
            image.drawFilledRect(px, py, px + cell, py + cell, 0)
        }
    }

    /** Generic top-right QR region used before the template has been identified. */
    fun genericRect(width: Int, height: Int): NormRect {
        // Same normalized position as LayoutFactory: marker-centroid coordinates
        // (0.6975, 0.04255) with a 0.205 x 0.1449 inner-rect footprint.
        val mx = width * 0.045; val my = height * 0.045
        val innerW = width - 2 * mx; val innerH = height - 2 * my
        return NormRect(mx + 0.6975 * innerW, my + 0.04255 * innerH,
            0.205 * innerW, 0.1449 * innerH)
    }

    fun sample(binary: GrayImage, rectPx: NormRect, version: Int): BitMatrix {
        val size = 17 + 4 * version
        val side = min(rectPx.w, rectPx.h)
        val cell = side / (size + QUIET_MODULES * 2)
        val originX = rectPx.x + (rectPx.w - side) / 2
        val originY = rectPx.y + (rectPx.h - side) / 2
        val matrix = BitMatrix(size)
        for (y in 0 until size) for (x in 0 until size) {
            val cx = (originX + (x + QUIET_MODULES + 0.5) * cell).toInt()
            val cy = (originY + (y + QUIET_MODULES + 0.5) * cell).toInt()
            var dark = 0; var total = 0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = cx + dx; val sy = cy + dy
                if (sx < 0 || sy < 0 || sx >= binary.width || sy >= binary.height) continue
                total++
                if (binary.at(sx, sy) < 128) dark++
            }
            matrix[x, y] = total > 0 && dark.toDouble() / total > 0.5
        }
        return matrix
    }

    fun decode(binary: GrayImage, rectPx: NormRect, codec: QrCodec): String? {
        for (version in 1..10) {
            val matrix = sample(binary, rectPx, version)
            val text = codec.decode(matrix) ?: continue
            if (text.startsWith("OMR1|")) return text
        }
        return null
    }
}
