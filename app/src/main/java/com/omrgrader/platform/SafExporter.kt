package com.omrgrader.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.omrgrader.core.AppError
import com.omrgrader.core.AtomicFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Storage Access Framework export/sharing. No network, no cloud dependency. */
@Singleton
class SafExporter @Inject constructor(@ApplicationContext private val context: Context) {
    fun exportsDirectory(): File = File(context.filesDir, "exports").apply { mkdirs() }

    /** FileProvider URI for an existing app-private file (archive, dataset or export). */
    fun uriForFile(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Writes to app-private exports and returns a shareable FileProvider URI. */
    fun stageAndShare(fileName: String, bytes: ByteArray): Uri {
        val file = AtomicFiles.write(exportsDirectory(), fileName, bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Writes into a teacher-chosen SAF document tree (ACTION_OPEN_DOCUMENT_TREE). */
    fun writeToTree(treeUri: Uri, fileName: String, bytes: ByteArray, mimeType: String): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw AppError.Storage("invalid SAF tree")
        val existing = tree.findFile(fileName)
        existing?.delete()
        val doc = tree.createFile(mimeType, fileName)
            ?: throw AppError.Storage("cannot create SAF document")
        context.contentResolver.openOutputStream(doc.uri)?.use { stream ->
            stream.write(bytes); stream.flush()
        } ?: throw AppError.Storage("cannot open SAF output stream")
        return doc.uri
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
