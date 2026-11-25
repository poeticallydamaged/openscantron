package com.omrgrader.platform

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/** Real Android print-service adapter for generated PDF bytes. */
class PdfPrintAdapter(private val bytes: ByteArray, private val fileName: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: android.os.Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(fileName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>?, destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?, callback: WriteResultCallback
    ) {
        val fd = destination ?: run { callback.onWriteFailed("no destination"); return }
        try {
            FileOutputStream(fd.fileDescriptor).use { it.write(bytes); it.flush() }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.javaClass.simpleName)
        } finally {
            fd.close()
        }
    }
}

fun printPdf(context: Context, fileName: String, bytes: ByteArray) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val attrs = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    manager.print(fileName, PdfPrintAdapter(bytes, fileName), attrs)
}
