package com.omrgrader.platform

import com.omrgrader.omr.ImageOps
import com.omrgrader.omr.PortableImageOps

/**
 * Selects the real OpenCV-backed ImageOps when the OpenCV Android SDK module was
 * compiled in (local.properties -> opencv.sdk.dir). Otherwise the identical pure-Kotlin
 * implementation runs. No stub and no simulated CV results in either branch.
 */
object ImageOpsFactory {
    fun create(): ImageOps = try {
        // OpenCV native libraries are initialized by reflection so the core source set
        // compiles without the optional SDK module.
        runCatching {
            val loader = Class.forName("org.opencv.android.OpenCVLoader")
            loader.getMethod("initDebug").invoke(null)
        }
        val clazz = Class.forName("com.omrgrader.omr.OpenCvImageOps")
        (clazz.getDeclaredField("INSTANCE").get(null) as ImageOps)
    } catch (_: Throwable) {
        PortableImageOps
    }
}
