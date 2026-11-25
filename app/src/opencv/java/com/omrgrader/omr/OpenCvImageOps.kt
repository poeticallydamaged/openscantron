package com.omrgrader.omr

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Real OpenCV Android SDK implementation of the ImageOps contract. Compiled only when
 * `opencv.sdk.dir` is configured in local.properties (see README). Uses CLAHE,
 * Gaussian denoise, adaptive threshold, getPerspectiveTransform and warpPerspective.
 */
object OpenCvImageOps : ImageOps {
    private fun toMat(image: GrayImage): Mat {
        val mat = Mat(image.height, image.width, CvType.CV_8UC1)
        val data = ByteArray(image.width * image.height)
        var i = 0
        for (y in 0 until image.height) for (x in 0 until image.width) data[i++] = image.at(x, y).toByte()
        mat.put(0, 0, data)
        return mat
    }

    private fun fromMat(mat: Mat): RasterImage {
        val out = RasterImage(mat.cols(), mat.rows())
        val data = ByteArray(mat.cols() * mat.rows())
        mat.get(0, 0, data)
        var i = 0
        for (y in 0 until out.height) for (x in 0 until out.width) out[x, y] = data[i++].toInt() and 0xFF
        mat.release()
        return out
    }

    override fun denoise(image: GrayImage): GrayImage {
        val src = toMat(image); val dst = Mat()
        Imgproc.GaussianBlur(src, dst, Size(3.0, 3.0), 0.0)
        src.release()
        return fromMat(dst)
    }

    override fun normalizeContrast(image: GrayImage): GrayImage {
        val src = toMat(image); val dst = Mat()
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(src, dst)
        src.release()
        return fromMat(dst)
    }

    override fun binarize(image: GrayImage, blockSize: Int, c: Int): GrayImage {
        val src = toMat(image); val dst = Mat()
        val block = if (blockSize % 2 == 1) blockSize else blockSize + 1
        Imgproc.adaptiveThreshold(src, dst, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, block, c.toDouble())
        src.release()
        return fromMat(dst)
    }

    override fun warp(source: GrayImage, canonicalToSource: Homography, outWidth: Int, outHeight: Int): RasterImage {
        val src = toMat(source)
        val canonical = MatOfPoint2f(
            Point(0.0, 0.0), Point(outWidth.toDouble(), 0.0),
            Point(outWidth.toDouble(), outHeight.toDouble()), Point(0.0, outHeight.toDouble())
        )
        val mapped = MatOfPoint2f(*canonicalToSource.let { h ->
            canonical.toList().map { p -> h.map(p.x, p.y).let { Point(it.x, it.y) } }.toTypedArray()
        })
        val transform = Imgproc.getPerspectiveTransform(canonical, mapped)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, transform, Size(outWidth.toDouble(), outHeight.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0))
        src.release(); transform.release(); canonical.release(); mapped.release()
        return fromMat(dst)
    }

    override fun laplacianVariance(image: GrayImage): Double {
        val src = toMat(image); val lap = Mat(); val mean = Mat(); val std = Mat()
        Imgproc.Laplacian(src, lap, CvType.CV_64F)
        Core.meanStdDev(lap, mean, std)
        val value = std.get(0, 0)[0].let { it * it }
        src.release(); lap.release(); mean.release(); std.release()
        return value
    }

    override fun brightness(image: GrayImage): BrightnessStats {
        val src = toMat(image)
        val mean = Core.mean(src).`val`[0]
        val dark = Mat()
        Imgproc.threshold(src, dark, 80.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val ratio = Core.countNonZero(dark).toDouble() / (src.rows() * src.cols())
        src.release(); dark.release()
        return BrightnessStats(mean, ratio, 0, 255)
    }
}
