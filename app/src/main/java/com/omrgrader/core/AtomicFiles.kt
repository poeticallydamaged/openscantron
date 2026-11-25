package com.omrgrader.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object Sha256 {
    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun of(file: File): String = of(Files.readAllBytes(file.toPath()))

    fun verify(bytes: ByteArray, expected: String): Boolean = of(bytes).equals(expected, ignoreCase = true)
}

/**
 * Atomic dataset/archive writer: write a sibling `.tmp`, flush + fsync, then rename.
 * A crash can leave the tmp file only; a partially written target can never be observed.
 */
object AtomicFiles {
    fun write(directory: File, fileName: String, bytes: ByteArray, clock: Clock = SystemClock): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw AppError.Storage("cannot create output directory")
        }
        val target = File(directory, fileName)
        val tmp = File(directory, "$fileName.${clock.nowMillis()}.tmp")
        try {
            FileOutputStream(tmp, false).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } catch (e: Exception) {
            tmp.delete()
            throw AppError.Storage("atomic write failed for dataset artifact", e)
        }
    }

    fun writeText(directory: File, fileName: String, text: String, clock: Clock = SystemClock): File =
        write(directory, fileName, text.toByteArray(Charsets.UTF_8), clock)

    fun staleTempFiles(directory: File): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.toList() ?: emptyList()
}

fun String.sha256(): String = Sha256.of(this.toByteArray(Charsets.UTF_8))
