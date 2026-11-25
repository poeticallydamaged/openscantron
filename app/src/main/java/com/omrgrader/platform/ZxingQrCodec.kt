package com.omrgrader.platform

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.omrgrader.export.QrCodec
import javax.inject.Inject
import javax.inject.Singleton

/** ZXing QR generation/decoding for camera-grade robustness; same QrCodec contract as the JVM core. */
@Singleton
class ZxingQrCodec @Inject constructor() : QrCodec {
    override fun encode(payload: String): com.omrgrader.export.BitMatrix {
        val matrix: BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256,
            mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
        val out = com.omrgrader.export.BitMatrix(matrix.width)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) out[x, y] = matrix.get(x, y)
        return out
    }

    override fun decode(matrix: com.omrgrader.export.BitMatrix): String? = try {
        val side = matrix.size
        val pixels = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val v = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            pixels[y * side + x] = v
        }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        decodeBitmap(bitmap)
    } catch (_: Exception) { null }

    fun decodeBitmap(bitmap: Bitmap): String? = try {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binary, mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8")).text
    } catch (_: Exception) { null }
}
