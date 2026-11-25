package com.omrgrader.archive

import com.omrgrader.core.AtomicFiles
import com.omrgrader.core.Sha256
import com.omrgrader.domain.AnswerSheetArchive
import com.omrgrader.domain.GenerationStatus
import com.omrgrader.export.PdfDocumentModel
import com.omrgrader.export.PdfOp
import com.omrgrader.export.PdfPage
import com.omrgrader.export.PortablePdfWriter
import com.omrgrader.omr.RasterImage
import java.io.File
import kotlin.math.min

object ArchiveNaming {
    fun individual(examId: String, studentNumber: String?, submissionId: String): String =
        "EXAM_${examId}_STUDENT_${studentNumber?.takeIf { it.isNotBlank() } ?: "UNASSIGNED"}_SUBMISSION_${submissionId}.pdf"

    fun batch(examId: String, batchId: String): String =
        "EXAM_${examId}_BATCH_${batchId}_ANSWER_ARCHIVE.pdf"
}

data class IndividualArchiveResult(
    val archive: AnswerSheetArchive,
    val pdfBytes: ByteArray?,
    val checksum: String,
    val error: String?
) {
    val succeeded: Boolean get() = archive.generationStatus == GenerationStatus.GENERATED
}

data class BatchEntry(
    val submissionId: String,
    val studentNumber: String?,
    val originalImage: RasterImage,
    val scannedAt: Long,
    val discarded: Boolean = false,
    /** Original JPEG bytes; when present they are embedded without recompression. */
    val originalJpegBytes: ByteArray? = null,
    val exifRotationDegrees: Int = 0
)

data class BatchPageMeta(val page: Int, val submissionId: String, val studentNumber: String)

data class BatchArchiveResult(
    val fileName: String,
    val bytes: ByteArray,
    val checksum: String,
    val pages: List<BatchPageMeta>
)

data class StorageUsage(
    val archivePdfCount: Int,
    val archivePdfBytes: Long,
    val rawImageCount: Int,
    val rawImageBytes: Long,
    val datasetCount: Int,
    val datasetBytes: Long,
    val totalBytes: Long
)

/** Mandatory per-scan archival PDFs and consolidated batch PDFs. */
object ArchiveService {
    private const val A4_W = 595.2756
    private const val A4_H = 841.8898

    private data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)

    private fun normalizedRotation(degrees: Int): Int =
        (((degrees % 360) + 360) % 360 / 90) * 90

    private fun pageDimensions(rotation: Int): Pair<Double, Double> =
        if (rotation == 90 || rotation == 270) A4_H to A4_W else A4_W to A4_H

    private fun fitRect(imageWidth: Int, imageHeight: Int, pageW: Double, pageH: Double,
                        margin: Double, captionHeight: Double = 0.0): Rect {
        val availableW = pageW - 2 * margin
        val availableH = pageH - 2 * margin - captionHeight
        val scale = min(availableW / imageWidth, availableH / imageHeight)
        val w = imageWidth * scale; val h = imageHeight * scale
        return Rect((pageW - w) / 2, margin + (availableH - h) / 2, w, h)
    }

    private fun fitForPage(image: RasterImage, pageW: Double, pageH: Double,
                           margin: Double, captionHeight: Double = 0.0): PdfOp.Image {
        val r = fitRect(image.width, image.height, pageW, pageH, margin, captionHeight)
        return PdfOp.Image(r.x, r.y, r.w, r.h, image)
    }

    private fun fit(image: RasterImage, margin: Double, captionHeight: Double = 0.0): PdfOp.Image =
        fitForPage(image, A4_W, A4_H, margin, captionHeight)

    /** Reads intrinsic dimensions from a JPEG SOF marker without decoding pixels. */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF ||
            (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or
                    (bytes[i + 8].toInt() and 0xFF)
                if (width > 0 && height > 0) return width to height
            }
            i += 2 + length
        }
        return null
    }

    /**
     * Page 1 is the original capture: when original JPEG bytes are supplied they are
     * embedded with DCTDecode without recompression and EXIF orientation is expressed
     * with PDF /Rotate. No overlays, perspective warp or cropping are applied.
     * Page 2 (optional) is clearly labelled as the perspective-corrected processing image.
     */
    fun generateIndividual(
        examId: String,
        studentNumber: String?,
        submissionId: String,
        originalImage: RasterImage,
        processingImage: RasterImage?,
        originalImagePath: String,
        directory: File,
        includeProcessingPage: Boolean = true,
        generatedAt: Long = System.currentTimeMillis(),
        originalJpegBytes: ByteArray? = null,
        exifRotationDegrees: Int = 0
    ): IndividualArchiveResult {
        val name = ArchiveNaming.individual(examId, studentNumber, submissionId)
        val pages = mutableListOf<PdfPage>()
        if (originalJpegBytes != null) {
            val rotation = normalizedRotation(exifRotationDegrees)
            val (pageW, pageH) = pageDimensions(rotation)
            val dimensions = jpegDimensions(originalJpegBytes)
                ?: (originalImage.width to originalImage.height)
            val r = fitRect(dimensions.first, dimensions.second, pageW, pageH, 8.0)
            pages += PdfPage(pageW, pageH, listOf(
                PdfOp.Jpeg(r.x, r.y, r.w, r.h, originalJpegBytes,
                    dimensions.first, dimensions.second, rotation)
            ), rotation = rotation)
        } else {
            pages += PdfPage(A4_W, A4_H, listOf(fit(originalImage, 8.0)))
        }
        if (includeProcessingPage && processingImage != null) {
            pages += PdfPage(A4_W, A4_H, listOf(
                fit(processingImage, 8.0, captionHeight = 20.0),
                PdfOp.Text(16.0, A4_H - 14.0, 8.0,
                    "PAGE 2: PERSPECTIVE-CORRECTED OMR PROCESSING IMAGE (NOT THE ORIGINAL CAPTURE)", PdfFontBold)
            ))
        }
        return try {
            val bytes = PortablePdfWriter.render(PdfDocumentModel(pages, title = name))
            val file = AtomicFiles.write(directory, name, bytes)
            val checksum = Sha256.of(bytes)
            IndividualArchiveResult(
                AnswerSheetArchive(
                    id = "archive-$submissionId",
                    submissionId = submissionId,
                    originalImagePath = originalImagePath,
                    pdfFilePath = file.absolutePath,
                    pdfFileName = name,
                    pdfFileSize = file.length(),
                    pdfChecksum = checksum,
                    generationStatus = GenerationStatus.GENERATED,
                    generatedAt = generatedAt
                ), bytes, checksum, null
            )
        } catch (e: Exception) {
            // Never drop a scan because PDF generation failed: record a retryable status.
            IndividualArchiveResult(
                AnswerSheetArchive(
                    id = "archive-$submissionId",
                    submissionId = submissionId,
                    originalImagePath = originalImagePath,
                    pdfFilePath = "",
                    pdfFileName = name,
                    pdfFileSize = 0,
                    pdfChecksum = "",
                    generationStatus = GenerationStatus.RETRY_QUEUED,
                    generatedAt = null
                ), null, "", e::class.java.simpleName
            )
        }
    }

    fun retry(request: IndividualArchiveRequest): IndividualArchiveResult = generateIndividual(
        examId = request.examId, studentNumber = request.studentNumber,
        submissionId = request.submissionId, originalImage = request.originalImage,
        processingImage = request.processingImage, originalImagePath = request.originalImagePath,
        directory = request.directory, includeProcessingPage = request.includeProcessingPage,
        originalJpegBytes = request.originalJpegBytes,
        exifRotationDegrees = request.exifRotationDegrees
    )

    /** One student per page, ordered by scan order or student number, with page metadata. */
    fun generateBatch(
        examId: String,
        batchId: String,
        entries: List<BatchEntry>,
        sortByStudentId: Boolean = false,
        directory: File? = null
    ): BatchArchiveResult {
        val ordered = entries.filterNot { it.discarded }
            .let { list -> if (sortByStudentId) list.sortedBy { it.studentNumber ?: "~" } else list.sortedBy { it.scannedAt } }
        val pages = ArrayList<PdfPage>()
        val meta = ArrayList<BatchPageMeta>()
        ordered.forEachIndexed { index, entry ->
            val caption = 22.0
            val rotation = normalizedRotation(entry.exifRotationDegrees)
            val (pageW, pageH) = pageDimensions(rotation)
            val imageOp: PdfOp = if (entry.originalJpegBytes != null) {
                val dimensions = jpegDimensions(entry.originalJpegBytes)
                    ?: (entry.originalImage.width to entry.originalImage.height)
                val r = fitRect(dimensions.first, dimensions.second, pageW, pageH, 8.0, caption)
                PdfOp.Jpeg(r.x, r.y, r.w, r.h, entry.originalJpegBytes,
                    dimensions.first, dimensions.second, rotation)
            } else {
                fitForPage(entry.originalImage, pageW, pageH, 8.0, caption)
            }
            pages += PdfPage(pageW, pageH, listOf(
                imageOp,
                PdfOp.Text(12.0, pageH - 12.0, 7.5,
                    "EXAM $examId | BATCH $batchId | PAGE ${index + 1} | STUDENT ${entry.studentNumber ?: "UNASSIGNED"} | SUBMISSION ${entry.submissionId}")
            ), rotation = rotation)
            meta += BatchPageMeta(index + 1, entry.submissionId, entry.studentNumber ?: "UNASSIGNED")
        }
        if (pages.isEmpty()) pages += PdfPage(A4_W, A4_H,
            listOf(PdfOp.Text(24.0, 40.0, 11.0, "NO RETAINED SUBMISSIONS IN THIS BATCH")))
        val bytes = PortablePdfWriter.render(
            PdfDocumentModel(pages, title = "Batch answer archive $batchId")
        )
        val name = ArchiveNaming.batch(examId, batchId)
        directory?.let { AtomicFiles.write(it, name, bytes) }
        return BatchArchiveResult(name, bytes, Sha256.of(bytes), meta)
    }

    fun verify(archive: AnswerSheetArchive): Boolean {
        val file = File(archive.pdfFilePath)
        return file.isFile && file.length() == archive.pdfFileSize && Sha256.of(file) == archive.pdfChecksum
    }

    fun storageUsage(
        archives: List<AnswerSheetArchive>,
        imageFiles: List<File>,
        datasetFiles: List<File>
    ): StorageUsage {
        val archiveBytes = archives.filter { File(it.pdfFilePath).isFile }.sumOf { File(it.pdfFilePath).length() }
        val archiveCount = archives.count { File(it.pdfFilePath).isFile }
        val imageBytes = imageFiles.filter { it.isFile }.sumOf { it.length() }
        val datasetBytes = datasetFiles.filter { it.isFile }.sumOf { it.length() }
        return StorageUsage(
            archivePdfCount = archiveCount, archivePdfBytes = archiveBytes,
            rawImageCount = imageFiles.count { it.isFile }, rawImageBytes = imageBytes,
            datasetCount = datasetFiles.count { it.isFile }, datasetBytes = datasetBytes,
            totalBytes = archiveBytes + imageBytes + datasetBytes
        )
    }

    /** Explicit deletion only; callers must surface the warning before invoking. */
    fun deleteRawImages(files: List<File>, confirmationToken: String): Int {
        require(confirmationToken == "DELETE_RAW_IMAGES_CONFIRMED") { "explicit confirmation required" }
        return files.count { it.isFile && it.delete() }
    }
}

data class IndividualArchiveRequest(
    val examId: String,
    val studentNumber: String?,
    val submissionId: String,
    val originalImage: RasterImage,
    val processingImage: RasterImage?,
    val originalImagePath: String,
    val directory: File,
    val includeProcessingPage: Boolean = true,
    val originalJpegBytes: ByteArray? = null,
    val exifRotationDegrees: Int = 0
)

private val PdfFontBold = com.omrgrader.export.PdfFont.BOLD
