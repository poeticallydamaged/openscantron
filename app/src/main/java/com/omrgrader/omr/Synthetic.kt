package com.omrgrader.omr

import com.omrgrader.domain.Classification
import com.omrgrader.export.PortableQrCodec
import com.omrgrader.export.QrCodec
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class MarkStyle { BLANK, DARK_PENCIL, LIGHT_PENCIL, PEN, PARTIAL, ERASED, MULTIPLE, STRAY }

enum class SyntheticCondition {
    CLEAN, DARK_PENCIL, LIGHT_PENCIL, PEN, PARTIAL_SHADING, ERASED_MARK,
    MULTIPLE_MARKS, STRAY_MARKS, ROTATION_90, ROTATION_180, ROTATION_270,
    PERSPECTIVE_SKEW, UNEVEN_LIGHTING, SHADOW, BLUR, LOW_RESOLUTION
}

data class SyntheticCase(
    val image: RasterImage,
    val layout: SheetLayout,
    val expectedAnswers: Map<Int, String>,
    val expectedClassification: Map<Int, Classification>,
    val studentNumber: String,
    val condition: SyntheticCondition,
    val rotationDegrees: Int
)

/** Draws a physically plausible printed sheet; used by both preview tools and the test harness. */
object SheetRenderer {
    fun render(
        layout: SheetLayout,
        answers: Map<Int, String>,
        studentNumber: String,
        style: MarkStyle = MarkStyle.DARK_PENCIL,
        qrCodec: QrCodec = PortableQrCodec,
        random: Random = Random(17),
        straySeed: Int = 0
    ): RasterImage {
        val image = RasterImage(layout.canonicalWidth, layout.canonicalHeight)
        image.fill(255)
        // Four geometric registration markers: dark square with a light centre (hole).
        for (marker in layout.markers) {
            val cx = layout.canonicalX(marker.x); val cy = layout.canonicalY(marker.y)
            val s = marker.sizePx
            image.drawFilledRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, 0)
            image.drawFilledRect(cx - s * 0.27, cy - s * 0.27, cx + s * 0.27, cy + s * 0.27, 255)
        }
        // Orientation index tab near the sheet's true top-left marker.
        val tab = layout.indexTab
        image.drawFilledRect(
            layout.canonicalX(tab.x), layout.canonicalY(tab.y),
            layout.canonicalX(tab.x) + tab.w * layout.innerWidth,
            layout.canonicalY(tab.y) + tab.h * layout.innerHeight, 0
        )
        // Header fields, ID-grid labels and instruction strokes (visual only, no OCR used).
        for (i in 0..3) image.drawFilledRect(
            layout.canonicalX(0.05), layout.canonicalY(0.035 + i * 0.022),
            layout.canonicalX(0.52 - i * 0.04), layout.canonicalY(0.038 + i * 0.022), 40
        )
        image.drawFilledRect(layout.canonicalX(0.05), layout.canonicalY(0.135),
            layout.canonicalX(0.48), layout.canonicalY(0.14), 100)
        for (i in 0..2) image.drawFilledRect(
            layout.canonicalX(0.52), layout.canonicalY(0.05 + i * 0.02),
            layout.canonicalX(0.68), layout.canonicalY(0.055 + i * 0.02), 150
        )

        // QR metadata (non-sensitive): templateCode, version, examId, questionCount, choices.
        QrSheet.draw(image, layout.qrPixelRect(), qrCodec.encode(layout.metadataPayload()))

        // Student number 0-9 bubble grid.
        val byColumn = layout.idBubbles.groupBy { it.column }
        for ((col, specs) in byColumn) {
            val digit = studentNumber.getOrNull(col)?.digitToIntOrNull() ?: -1
            for (spec in specs) {
                val cx = layout.canonicalX(spec.x); val cy = layout.canonicalY(spec.y)
                image.drawCircle(cx, cy, spec.radiusPx, 0, outline = true, thickness = 2.2)
                if (spec.digit == digit) image.drawCircle(cx, cy, spec.radiusPx * 0.72, 20)
            }
        }

        // Answer bubbles.
        val groups = layout.bubbles.groupBy { it.question }
        for ((q, specs) in groups) {
            val expected = answers[q]
            val marked = specs.filter { it.label == expected }
            for (spec in specs) {
                val cx = layout.canonicalX(spec.x); val cy = layout.canonicalY(spec.y)
                image.drawCircle(cx, cy, spec.radiusPx, 0, outline = true, thickness = 2.2)
            }
            when (style) {
                MarkStyle.BLANK -> Unit
                MarkStyle.STRAY -> {
                    marked.firstOrNull()?.let { fill(image, layout, it, MarkStyle.DARK_PENCIL, random) }
                    val stray = specs.random(random)
                    val cx = layout.canonicalX(stray.x) + stray.radiusPx * 1.6
                    val cy = layout.canonicalY(stray.y) - stray.radiusPx * 1.4
                    image.drawCircle(cx, cy, stray.radiusPx * 0.28, 30)
                }
                MarkStyle.MULTIPLE -> {
                    if (marked.isNotEmpty()) {
                        fill(image, layout, marked.first(), MarkStyle.DARK_PENCIL, random)
                        val other = specs.firstOrNull { it.label != expected }
                        if (other != null) fill(image, layout, other, MarkStyle.DARK_PENCIL, random)
                    }
                }
                else -> marked.firstOrNull()?.let { fill(image, layout, it, style, random) }
            }
        }
        if (straySeed > 0) {
            val r = Random(straySeed)
            repeat(straySeed * 3) {
                val x = r.nextDouble() * image.width
                val y = r.nextDouble() * image.height
                image.drawCircle(x, y, 1.5, 110)
            }
        }
        return image
    }

    private fun fill(image: RasterImage, layout: SheetLayout, spec: BubbleSpec, style: MarkStyle, random: Random) {
        val cx = layout.canonicalX(spec.x); val cy = layout.canonicalY(spec.y)
        val r = spec.radiusPx * 0.74
        val value = when (style) {
            MarkStyle.DARK_PENCIL -> 35 + random.nextInt(0, 25)
            MarkStyle.LIGHT_PENCIL -> 120 + random.nextInt(0, 25)
            MarkStyle.PEN -> 5
            MarkStyle.PARTIAL -> 75
            MarkStyle.ERASED -> 195
            else -> 30
        }
        when (style) {
            MarkStyle.PARTIAL -> {
                for (y in (cy - r).toInt()..cy.toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) image[x, y] = value
                }
            }
            MarkStyle.ERASED -> {
                image.drawCircle(cx, cy, r, value)
                repeat(9) {
                    val a = random.nextDouble() * Math.PI * 2
                    val d = random.nextDouble() * r
                    image.drawCircle(cx + d * cos(a), cy + d * sin(a), 1.2, 90)
                }
            }
            else -> image.drawCircle(cx, cy, r, value)
        }
    }
}

/** Generates synthetic sheets covering the adverse conditions required by the test plan. */
object SyntheticSheetGenerator {
    fun generate(
        seed: Int,
        condition: SyntheticCondition,
        layout: SheetLayout,
        qrCodec: QrCodec = PortableQrCodec,
        questionCount: Int = layout.questionCount,
        idColumns: Int = layout.idColumns
    ): SyntheticCase {
        val random = Random(seed)
        val numbers = (0 until idColumns).map { random.nextInt(0, 10) }.joinToString("")
        val answers = LinkedHashMap<Int, String>()
        val expected = LinkedHashMap<Int, Classification>()
        for (q in 1..questionCount) {
            val blank = random.nextDouble() < 0.22
            if (blank) {
                answers[q] = ""
                expected[q] = Classification.BLANK
            } else {
                val label = layout.answerChoices[random.nextInt(layout.answerChoices.size)]
                answers[q] = label
                expected[q] = when (condition) {
                    SyntheticCondition.MULTIPLE_MARKS -> Classification.MULTIPLE_MARKS
                    SyntheticCondition.PARTIAL_SHADING, SyntheticCondition.ERASED_MARK,
                    SyntheticCondition.LIGHT_PENCIL -> Classification.AMBIGUOUS
                    else -> Classification.SINGLE_MARK
                }
            }
        }
        val style = when (condition) {
            SyntheticCondition.CLEAN, SyntheticCondition.ROTATION_90, SyntheticCondition.ROTATION_180,
            SyntheticCondition.ROTATION_270, SyntheticCondition.PERSPECTIVE_SKEW,
            SyntheticCondition.UNEVEN_LIGHTING, SyntheticCondition.SHADOW, SyntheticCondition.BLUR,
            SyntheticCondition.LOW_RESOLUTION -> MarkStyle.DARK_PENCIL
            SyntheticCondition.DARK_PENCIL -> MarkStyle.DARK_PENCIL
            SyntheticCondition.LIGHT_PENCIL -> MarkStyle.LIGHT_PENCIL
            SyntheticCondition.PEN -> MarkStyle.PEN
            SyntheticCondition.PARTIAL_SHADING -> MarkStyle.PARTIAL
            SyntheticCondition.ERASED_MARK -> MarkStyle.ERASED
            SyntheticCondition.MULTIPLE_MARKS -> MarkStyle.MULTIPLE
            SyntheticCondition.STRAY_MARKS -> MarkStyle.STRAY
        }
        var image = SheetRenderer.render(layout, answers, numbers, style, qrCodec, Random(seed + 91))
        var rotation = 0
        when (condition) {
            SyntheticCondition.ROTATION_90 -> { image = rotate(image, 90); rotation = 90 }
            SyntheticCondition.ROTATION_180 -> { image = rotate(image, 180); rotation = 180 }
            SyntheticCondition.ROTATION_270 -> { image = rotate(image, 270); rotation = 270 }
            SyntheticCondition.PERSPECTIVE_SKEW -> image = perspective(image, seed)
            else -> Unit
        }
        when (condition) {
            SyntheticCondition.UNEVEN_LIGHTING -> image = lighting(image, gradient = true, shadow = false)
            SyntheticCondition.SHADOW -> image = lighting(image, gradient = false, shadow = true)
            SyntheticCondition.LOW_RESOLUTION -> image = lowResolution(image)
            else -> Unit
        }
        if (condition == SyntheticCondition.BLUR) image = blur(image, 2)
        image = noise(image, Random(seed + 313), if (condition == SyntheticCondition.BLUR) 500 else 120)
        return SyntheticCase(image, layout, answers, expected, numbers, condition, rotation)
    }

    fun rotate(image: RasterImage, degrees: Int): RasterImage {
        val swap = degrees == 90 || degrees == 270
        val w = if (swap) image.height else image.width
        val h = if (swap) image.width else image.height
        val out = RasterImage(w, h)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val (nx, ny) = when (degrees) {
                90 -> (image.height - 1 - y) to x
                180 -> (image.width - 1 - x) to (image.height - 1 - y)
                270 -> y to (image.width - 1 - x)
                else -> x to y
            }
            out[nx, ny] = image.at(x, y)
        }
        return out
    }

    private fun perspective(image: RasterImage, seed: Int): RasterImage {
        val outW = (image.width * 1.16).toInt(); val outH = (image.height * 1.16).toInt()
        val r = Random(seed)
        val jitter = { (r.nextDouble() - 0.5) * outW * 0.035 }
        val mx = image.width * 0.045; val my = image.height * 0.045
        val canonical = listOf(
            PointD(mx, my), PointD(image.width - mx, my),
            PointD(image.width - mx, image.height - my), PointD(mx, image.height - my)
        )
        val quad = listOf(
            PointD(outW * 0.10 + jitter(), outH * 0.07 + jitter()),
            PointD(outW * 0.90 + jitter(), outH * 0.11 + jitter()),
            PointD(outW * 0.86 + jitter(), outH * 0.93 + jitter()),
            PointD(outW * 0.13 + jitter(), outH * 0.89 + jitter())
        )
        val h = Homography.solve(quad, canonical) ?: return image
        return PortableImageOps.warp(image, h, outW, outH)
    }

    private fun lighting(image: RasterImage, gradient: Boolean, shadow: Boolean): RasterImage {
        val out = image.copy()
        for (y in 0 until out.height) for (x in 0 until out.width) {
            var factor = 1.0
            if (gradient) factor -= 0.30 * (x.toDouble() / out.width) + 0.12 * (y.toDouble() / out.height)
            if (shadow) {
                val band = x.toDouble() / out.width
                if (band in 0.55..0.78) factor -= 0.40 * sin((band - 0.55) / 0.23 * Math.PI)
            }
            val v = (out.at(x, y) * factor).toInt().coerceIn(0, 255)
            out[x, y] = v
        }
        return out
    }

    private fun blur(image: RasterImage, radius: Int): RasterImage {
        var current: GrayImage = image
        repeat(2) { current = PortableImageOps.denoise(current) }
        return (current as? RasterImage) ?: image
    }

    private fun noise(image: RasterImage, random: Random, count: Int): RasterImage {
        val out = image.copy()
        repeat(count) {
            val x = random.nextInt(out.width); val y = random.nextInt(out.height)
            val v = out.at(x, y)
            out[x, y] = if (random.nextBoolean()) (v - 45).coerceAtLeast(0) else (v + 25).coerceAtMost(255)
        }
        return out
    }

    private fun lowResolution(image: RasterImage): RasterImage {
        val factor = 2
        val smallW = image.width / factor; val smallH = image.height / factor
        val small = RasterImage(smallW, smallH)
        for (y in 0 until smallH) for (x in 0 until smallW)
            small[x, y] = image.at(x * factor, y * factor)
        val out = RasterImage(image.width, image.height)
        for (y in 0 until out.height) for (x in 0 until out.width)
            out[x, y] = small.at(x / factor, y / factor)
        return out
    }
}
