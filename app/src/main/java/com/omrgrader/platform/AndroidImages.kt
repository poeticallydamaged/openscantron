package com.omrgrader.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.omrgrader.omr.RasterImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Bitmap <-> platform-independent RasterImage conversions, with EXIF orientation handling. */
object AndroidImages {
    data class Decoded(val image: RasterImage, val rotationDegrees: Int)

    fun exifRotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }

    fun decode(bytes: ByteArray, maxSide: Int = 2200): Decoded {
        val rotation = exifRotation(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("image decode failed")
        val rotated = if (rotation == 0) bitmap else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        }
        return Decoded(fromBitmap(rotated), rotation)
    }

    fun fromBitmap(bitmap: Bitmap): RasterImage {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = RasterImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            out[x, y] = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
        }
        bitmap.recycle()
        return out
    }

    fun toBitmap(image: RasterImage): Bitmap {
        val w = image.width; val h = image.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = image.at(x, y)
            pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun toJpeg(image: RasterImage, quality: Int = 88): ByteArray {
        val w = image.width; val h = image.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = image.at(x, y)
            pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    fun readUri(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("cannot open image URI")
}
