package com.omrgrader.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppDirectories @Inject constructor(@ApplicationContext context: Context) {
    val captures = File(context.filesDir, "captures").apply { mkdirs() }
    val archives = File(context.filesDir, "archives").apply { mkdirs() }
    val datasets = File(context.filesDir, "datasets").apply { mkdirs() }
    val backups = File(context.filesDir, "backups").apply { mkdirs() }
    val exports = File(context.filesDir, "exports").apply { mkdirs() }
    val databaseFile: File get() = File(context.filesDir.parentFile, "databases/omrgrader.db")
    fun allFiles(): List<File> = listOf(captures, archives, datasets, exports).flatMap { dir ->
        dir.listFiles()?.filter { it.isFile } ?: emptyList()
    }
}
