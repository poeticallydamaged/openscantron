package com.omrgrader.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.omr.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Live pre-capture quality gate. Runs on the CameraX analyzer executor (not the UI
 * thread), downsamples the Y plane, computes blur/lighting/shadow/resolution/marker
 * warnings and reports them for the overlay and optional auto-capture.
 */
class ImageQualityAnalyzer(
    private val imageOps: ImageOps,
    private var thresholds: OmrThresholds,
    private val onReport: (QualityReport) -> Unit
) : ImageAnalysis.Analyzer {
    private val lastReport = AtomicLong(0L)

    fun updateThresholds(value: OmrThresholds) { thresholds = value }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastReport.get() < 350) { image.close(); return }
        lastReport.set(now)
        try {
            val raster = yPlaneToRaster(image, maxSide = 420)
            val denoised = imageOps.denoise(raster)
            val normalized = imageOps.normalizeContrast(denoised)
            val binary = imageOps.binarize(normalized, 21, 7)
            val markerResult = MarkerDetector.detect(binary, normalized, thresholds, imageOps)
            val skew = markerResult.detection?.skewDegrees ?: 0.0
            val report = ImageQualityGate.evaluate(raster, normalized, imageOps,
                if (markerResult.detection != null) 4 else markerResult.candidates.size,
                skew, thresholds)
            onReport(report)
        } catch (_: Exception) {
            // Never crash the camera stream because of an analysis failure.
        } finally {
            image.close()
        }
    }

    private fun yPlaneToRaster(image: ImageProxy, maxSide: Int): RasterImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width; val height = image.height
        val step = (maxOf(width, height) / maxSide).coerceAtLeast(1)
        val outW = width / step; val outH = height / step
        val out = RasterImage(outW, outH)
        buffer.rewind()
        for (y in 0 until outH) {
            val srcRow = y * step * rowStride
            for (x in 0 until outW) {
                val index = srcRow + x * step * plane.pixelStride
                if (index < buffer.limit()) out[x, y] = buffer.get(index).toInt() and 0xFF
            }
        }
        return out
    }
}
