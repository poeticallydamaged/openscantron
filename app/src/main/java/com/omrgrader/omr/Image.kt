package com.omrgrader.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface GrayImage {
    val width: Int
    val height: Int
    /** Luminance 0 (black) .. 255 (white). */
    fun at(x: Int, y: Int): Int
}

class RasterImage(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray = IntArray(width * height) { 255 }
) : GrayImage {
    init { require(width > 0 && height > 0) }
    override fun at(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    operator fun set(x: Int, y: Int, value: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = value.coerceIn(0, 255)
    }

    fun copy(): RasterImage = RasterImage(width, height, pixels.copyOf())

    fun fill(value: Int) { pixels.fill(value.coerceIn(0, 255)) }

    fun drawFilledRect(x0: Double, y0: Double, x1: Double, y1: Double, value: Int) {
        for (y in max(0, y0.toInt()) until min(height, y1.toInt()))
            for (x in max(0, x0.toInt()) until min(width, x1.toInt())) this[x, y] = value
    }

    fun drawCircle(cx: Double, cy: Double, radius: Double, value: Int, outline: Boolean = false, thickness: Double = 2.0) {
        val r2 = radius * radius
        val inner = (radius - thickness).coerceAtLeast(0.0)
        val inner2 = inner * inner
        for (y in max(0, (cy - radius - 1).toInt())..min(height - 1, (cy + radius + 1).toInt()))
            for (x in max(0, (cx - radius - 1).toInt())..min(width - 1, (cx + radius + 1).toInt())) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d2 <= r2 && (!outline || d2 >= inner2)) this[x, y] = value
            }
    }

    fun drawLine(x0: Double, y0: Double, x1: Double, y1: Double, value: Int, thickness: Double = 1.5) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1) * 2
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            drawCircle(x, y, thickness, value)
        }
    }
}

data class BrightnessStats(val mean: Double, val darkRatio: Double, val min: Int, val max: Int)

/** Platform-independent image operators. OpenCV implements the same contract on device. */
interface ImageOps {
    fun denoise(image: GrayImage): GrayImage
    fun normalizeContrast(image: GrayImage): GrayImage
    fun binarize(image: GrayImage, blockSize: Int, c: Int): GrayImage
    fun warp(source: GrayImage, canonicalToSource: Homography, outWidth: Int, outHeight: Int): RasterImage
    fun laplacianVariance(image: GrayImage): Double
    fun brightness(image: GrayImage): BrightnessStats
}

/** Performance-oriented pure Kotlin implementation (3-pass box blur ≈ Gaussian, local-mean CLAHE). */
object PortableImageOps : ImageOps {
    private fun boxBlur(src: GrayImage, radius: Int): RasterImage {
        val w = src.width; val h = src.height
        val tmp = RasterImage(w, h)
        val out = RasterImage(w, h)
        val r = radius.coerceAtLeast(1)
        for (y in 0 until h) {
            var sum = 0
            for (x in -r..r) sum += src.at((x).coerceIn(0, w - 1), y)
            for (x in 0 until w) {
                tmp[x, y] = sum / (2 * r + 1)
                sum -= src.at((x - r).coerceIn(0, w - 1), y)
                sum += src.at((x + r + 1).coerceIn(0, w - 1), y)
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp.at(x, y.coerceIn(0, h - 1))
            for (y in 0 until h) {
                out[x, y] = sum / (2 * r + 1)
                sum -= tmp.at(x, (y - r).coerceIn(0, h - 1))
                sum += tmp.at(x, (y + r + 1).coerceIn(0, h - 1))
            }
        }
        return out
    }

    private class Integral(val image: GrayImage) {
        val w = image.width
        val h = image.height
        val data = LongArray((w + 1) * (h + 1))
        init {
            for (y in 1..h) {
                var row = 0L
                for (x in 1..w) {
                    row += image.at(x - 1, y - 1).toLong()
                    data[y * (w + 1) + x] = data[(y - 1) * (w + 1) + x] + row
                }
            }
        }
        fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
            val ax = x0.coerceIn(0, w); val ay = y0.coerceIn(0, h)
            val bx = x1.coerceIn(0, w); val by = y1.coerceIn(0, h)
            if (bx <= ax || by <= ay) return 0
            return data[by * (w + 1) + bx] - data[ay * (w + 1) + bx] -
                data[by * (w + 1) + ax] + data[ay * (w + 1) + ax]
        }
        fun mean(x: Int, y: Int, radius: Int): Double {
            val x0 = x - radius; val y0 = y - radius; val x1 = x + radius + 1; val y1 = y + radius + 1
            val ax = x0.coerceIn(0, w); val ay = y0.coerceIn(0, h)
            val bx = x1.coerceIn(0, w); val by = y1.coerceIn(0, h)
            val count = (bx - ax).coerceAtLeast(1) * (by - ay).coerceAtLeast(1)
            return sum(x0, y0, x1, y1).toDouble() / count
        }
    }

    override fun denoise(image: GrayImage): GrayImage = boxBlur(image, 1)

    /** CLAHE-style local contrast normalization with a global histogram stretch. */
    override fun normalizeContrast(image: GrayImage): GrayImage {
        var lo = 255; var hi = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val v = image.at(x, y); lo = min(lo, v); hi = max(hi, v)
        }
        val scale = if (hi > lo) 255.0 / (hi - lo) else 1.0
        val stretched = RasterImage(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width)
            stretched[x, y] = ((image.at(x, y) - lo) * scale).toInt().coerceIn(0, 255)
        val integral = Integral(stretched)
        val radius = max(8, min(image.width, image.height) / 25)
        val out = RasterImage(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val local = integral.mean(x, y, radius)
            out[x, y] = ((stretched.at(x, y) - local) * 1.15 + 128.0).toInt().coerceIn(0, 255)
        }
        return out
    }

    override fun binarize(image: GrayImage, blockSize: Int, c: Int): GrayImage {
        val integral = Integral(image)
        val radius = (blockSize / 2).coerceAtLeast(4)
        val out = RasterImage(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val local = integral.mean(x, y, radius)
            out[x, y] = if (image.at(x, y) < local - c) 0 else 255
        }
        return out
    }

    override fun warp(source: GrayImage, canonicalToSource: Homography, outWidth: Int, outHeight: Int): RasterImage {
        val out = RasterImage(outWidth, outHeight)
        for (y in 0 until outHeight) for (x in 0 until outWidth) {
            val p = canonicalToSource.map(x.toDouble(), y.toDouble())
            out[x, y] = bilinear(source, p.x, p.y)
        }
        return out
    }

    private fun bilinear(image: GrayImage, x: Double, y: Double): Int {
        if (x < 0 || y < 0 || x > image.width - 1 || y > image.height - 1) return 255
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(image.width - 1); val y1 = (y0 + 1).coerceAtMost(image.height - 1)
        val fx = x - x0; val fy = y - y0
        val a = image.at(x0, y0) * (1 - fx) + image.at(x1, y0) * fx
        val b = image.at(x0, y1) * (1 - fx) + image.at(x1, y1) * fx
        return (a * (1 - fy) + b * fy).toInt().coerceIn(0, 255)
    }

    override fun laplacianVariance(image: GrayImage): Double {
        var sum = 0.0; var sum2 = 0.0; var count = 0
        for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
            val v = 4 * image.at(x, y) - image.at(x - 1, y) - image.at(x + 1, y) -
                image.at(x, y - 1) - image.at(x, y + 1)
            sum += v; sum2 += v.toDouble() * v; count++
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sum2 / count - mean * mean).coerceAtLeast(0.0)
    }

    override fun brightness(image: GrayImage): BrightnessStats {
        var sum = 0L; var dark = 0; var lo = 255; var hi = 0
        val total = image.width.toLong() * image.height
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val v = image.at(x, y)
            sum += v; if (v < 80) dark++; lo = min(lo, v); hi = max(hi, v)
        }
        return BrightnessStats(sum.toDouble() / total, dark.toDouble() / total, lo, hi)
    }
}
