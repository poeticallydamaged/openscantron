package com.omrgrader.presentation.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Real PDF page rendering with Android's PdfRenderer; used by preview and archive viewer. */
@Composable
fun PdfPreview(path: String, modifier: Modifier = Modifier, pageIndex: Int = 0,
               onPageCount: (Int) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            val file = File(path)
            if (!file.isFile) { view.setImageDrawable(null); onPageCount(0); return@AndroidView }
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                val count = renderer.pageCount
                onPageCount(count)
                if (count == 0) { view.setImageDrawable(null); return@AndroidView }
                val page = renderer.openPage(pageIndex.coerceIn(0, count - 1))
                val scale = 2
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale,
                    Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                view.setImageBitmap(bitmap)
                page.close()
            } catch (_: Exception) {
                view.setImageDrawable(null)
                onPageCount(0)
            } finally {
                renderer?.close()
                descriptor?.close()
            }
        }
    )
}
