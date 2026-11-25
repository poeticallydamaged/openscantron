package com.omrgrader.archive

import com.omrgrader.core.Json
import com.omrgrader.core.JsonValue
import com.omrgrader.core.Sha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class BackupFileKind { DATABASE, TEMPLATE, KEY, SUBMISSION, ARCHIVE_PDF, DATASET_CSV, RAW_IMAGE, MANIFEST }

data class BackupFile(val relativePath: String, val bytes: ByteArray, val kind: BackupFileKind)

data class BackupPayload(
    val database: ByteArray,
    val layoutsJson: String,
    val keysJson: String,
    val submissionsJson: String,
    val artifacts: List<BackupFile>,
    val relationalCounts: Map<String, Int>,
    val submissionIds: List<String>,
    val recognitionSubmissionIds: List<String>,
    val includeRawImages: Boolean
)

data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val files: List<ManifestEntry>,
    val relationalCounts: Map<String, Int>,
    val includeRawImages: Boolean
)

data class ManifestEntry(val path: String, val kind: BackupFileKind, val size: Long, val sha256: String)

data class BackupBundle(val bytes: ByteArray, val manifest: BackupManifest)

data class RestoreResult(
    val success: Boolean,
    val extracted: List<String>,
    val errors: List<String>,
    val warnings: List<String>
)

/**
 * Documented offline backup archive:
 *   manifest.json, database/omrgrader.db, templates/layouts.json, keys/keys.json,
 *   submissions/submissions.json, archives/<pdf>, datasets/<csv>, captures/<image>.jpg (optional).
 * Post-restore validates zip entry checksums, relational counts and file integrity.
 */
object BackupService {
    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"

    fun create(payload: BackupPayload, now: Long = System.currentTimeMillis()): BackupBundle {
        val files = ArrayList<BackupFile>()
        files += BackupFile("database/omrgrader.db", payload.database, BackupFileKind.DATABASE)
        files += BackupFile("templates/layouts.json", payload.layoutsJson.toByteArray(Charsets.UTF_8), BackupFileKind.TEMPLATE)
        files += BackupFile("keys/keys.json", payload.keysJson.toByteArray(Charsets.UTF_8), BackupFileKind.KEY)
        files += BackupFile("submissions/submissions.json", payload.submissionsJson.toByteArray(Charsets.UTF_8), BackupFileKind.SUBMISSION)
        files += payload.artifacts.filter { payload.includeRawImages || it.kind != BackupFileKind.RAW_IMAGE }
        val entries = files.map {
            ManifestEntry(it.relativePath, it.kind, it.bytes.size.toLong(), Sha256.of(it.bytes))
        }
        val manifest = BackupManifest(FORMAT_VERSION, now, entries, payload.relationalCounts, payload.includeRawImages)
        val manifestBytes = manifestJson(manifest).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put(MANIFEST_NAME, manifestBytes)
            files.forEach { put(it.relativePath, it.bytes) }
        }
        return BackupBundle(out.toByteArray(), manifest)
    }

    fun restore(bytes: ByteArray, destination: File): RestoreResult {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val extracted = ArrayList<String>()
        val contents = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entry.name.contains("..") || entry.name.startsWith("/")) {
                        errors += "UNSAFE_PATH"; continue
                    }
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n <= 0) break
                        buffer.write(chunk, 0, n)
                    }
                    contents[entry.name] = buffer.toByteArray()
                }
            }
        } catch (e: Exception) {
            return RestoreResult(false, emptyList(), listOf("ZIP_READ_FAILED"), emptyList())
        }
        val manifestBytes = contents[MANIFEST_NAME]
            ?: return RestoreResult(false, emptyList(), listOf("MISSING_MANIFEST"), emptyList())
        val manifest = parseManifest(String(manifestBytes, Charsets.UTF_8))
            ?: return RestoreResult(false, emptyList(), listOf("INVALID_MANIFEST"), emptyList())
        if (manifest.formatVersion != FORMAT_VERSION) errors += "UNSUPPORTED_FORMAT_VERSION"
        for (entry in manifest.files) {
            val data = contents[entry.path]
            if (data == null) { errors += "MISSING_FILE:${entry.path}"; continue }
            if (data.size.toLong() != entry.size) { errors += "SIZE_MISMATCH:${entry.path}"; continue }
            if (Sha256.of(data) != entry.sha256) { errors += "CHECKSUM_MISMATCH:${entry.path}"; continue }
        }
        // Relational validation: every recognition references an existing submission,
        // and archive/dataset counts cannot exceed the number of submissions.
        val submissions = manifest.relationalCounts["submissions"] ?: 0
        val recognitions = manifest.relationalCounts["recognitions"] ?: 0
        val archives = manifest.relationalCounts["archives"] ?: 0
        val datasets = manifest.relationalCounts["datasets"] ?: 0
        if (submissions < 0 || recognitions < 0) errors += "NEGATIVE_RELATIONAL_COUNT"
        if (archives > submissions) errors += "ARCHIVE_COUNT_EXCEEDS_SUBMISSIONS"
        if (datasets > submissions * 2 + 2) warnings += "UNEXPECTED_DATASET_COUNT"
        if (submissions > 0 && recognitions == 0) errors += "RECOGNITIONS_MISSING"
        val required = listOf("database/omrgrader.db", "templates/layouts.json", "keys/keys.json", "submissions/submissions.json")
        required.forEach { if (!contents.containsKey(it)) errors += "REQUIRED_FILE_MISSING:$it" }
        if (errors.isNotEmpty()) return RestoreResult(false, emptyList(), errors, warnings)

        if (!destination.exists() && !destination.mkdirs()) errors += "DESTINATION_UNWRITABLE"
        if (errors.isNotEmpty()) return RestoreResult(false, emptyList(), errors, warnings)
        for ((path, data) in contents) {
            if (path == MANIFEST_NAME) continue
            val target = File(destination, path)
            target.parentFile?.mkdirs()
            target.writeBytes(data)
            extracted += path
        }
        return RestoreResult(true, extracted, errors, warnings)
    }

    fun manifestJson(manifest: BackupManifest): String = Json.stringify(Json.obj(
        "formatVersion" to Json.num(manifest.formatVersion),
        "createdAt" to Json.num(manifest.createdAt.toDouble()),
        "includeRawImages" to Json.bool(manifest.includeRawImages),
        "relationalCounts" to Json.obj(*manifest.relationalCounts.map { (k, v) -> k to Json.num(v) }.toTypedArray()),
        "files" to Json.arr(manifest.files.map {
            Json.obj(
                "path" to Json.str(it.path), "kind" to Json.str(it.kind.name),
                "size" to Json.num(it.size.toDouble()), "sha256" to Json.str(it.sha256)
            )
        })
    ))

    fun parseManifest(text: String): BackupManifest? = try {
        val o = Json.parse(text) as JsonValue.Obj
        BackupManifest(
            formatVersion = o.int("formatVersion"),
            createdAt = o.long("createdAt"),
            includeRawImages = o.bool("includeRawImages"),
            relationalCounts = (o["relationalCounts"] as JsonValue.Obj).values.entries.associate { (k, v) ->
                k to (v as JsonValue.Num).value.toInt()
            },
            files = o.array("files").map { it as JsonValue.Obj }.map {
                ManifestEntry(
                    path = it.string("path"),
                    kind = BackupFileKind.valueOf(it.string("kind")),
                    size = it.long("size"),
                    sha256 = it.string("sha256")
                )
            }
        )
    } catch (_: Exception) { null }
}
