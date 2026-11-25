package com.omrgrader.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/** Real CameraX lifecycle binding, capture, flash and lens switching. */
class CameraXController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private val executor: Executor get() = ContextCompat.getMainExecutor(context)

    fun bind(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        analyzer: ImageAnalysis.Analyzer,
        onError: (String) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(executor, analyzer) }
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                applyFlash()
            } catch (e: Exception) {
                onError(e.javaClass.simpleName)
            }
        }, executor)
    }

    fun capture(onResult: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture ?: run { onError("camera not ready"); return }
        val file = File.createTempFile("capture-", ".jpg", context.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try { onResult(file.readBytes()) } finally { file.delete() }
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete(); onError(exception.javaClass.simpleName)
            }
        })
    }

    fun toggleFlash(): Boolean {
        flashEnabled = !flashEnabled
        applyFlash()
        return flashEnabled
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    private fun applyFlash() {
        camera?.cameraControl?.enableTorch(flashEnabled)
    }

    fun close() { provider?.unbindAll(); camera = null; imageCapture = null }
}
