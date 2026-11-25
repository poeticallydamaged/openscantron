package com.omrgrader.omr

import com.omrgrader.core.AppError
import com.omrgrader.core.Clock
import com.omrgrader.core.Ids
import com.omrgrader.core.OmrResult
import com.omrgrader.core.Sha256
import com.omrgrader.core.SystemClock
import com.omrgrader.domain.CellValue
import com.omrgrader.domain.IdentificationMode
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.domain.ProcessingStatus
import com.omrgrader.domain.QuestionRecognition
import com.omrgrader.export.PortableQrCodec
import com.omrgrader.export.QrCodec

data class OmrRequest(
    val image: GrayImage,
    val layoutRegistry: LayoutRegistry,
    val roster: RosterLookup,
    val exifRotationDegrees: Int = 0,
    val qrStudentNumber: String? = null,
    val submissionId: String = Ids.newId("submission"),
    val nowMillis: Long = System.currentTimeMillis()
)

data class OmrRecognitionResult(
    val id: String,
    val submissionId: String,
    val templateCode: String,
    val examId: String,
    val questionCount: Int,
    val rotationDegrees: Int,
    val markerScore: Double,
    val alignmentScore: Double,
    val quality: QualityReport,
    val warnings: List<String>,
    val questions: List<QuestionRecognition>,
    val recognizedAnswers: Map<Int, String>,
    val studentId: String?,
    val recognizedStudentNumber: String?,
    val studentIdConfidence: Double,
    val identificationMode: IdentificationMode,
    val needsManualAssignment: Boolean,
    val processingStatus: ProcessingStatus,
    val imageFingerprint: String,
    val canonicalImage: RasterImage,
    val canonicalBinary: RasterImage,
    val canonicalRaw: RasterImage,
    val layout: SheetLayout,
    val timestamp: Long
) {
    /** Zoomed ROI crop for the manual verification screen; never modifies the original. */
    fun cropForQuestion(questionNumber: Int, scale: Int = 3): RasterImage? {
        val specs = layout.bubbles.filter { it.question == questionNumber }
        if (specs.isEmpty()) return null
        val left = layout.canonicalX(specs.minOf { it.x }) - specs.first().radiusPx * 2
        val right = layout.canonicalX(specs.maxOf { it.x }) + specs.first().radiusPx * 2
        val top = layout.canonicalY(specs.first().y) - specs.first().radiusPx * 2
        val bottom = layout.canonicalY(specs.first().y) + specs.first().radiusPx * 2
        val x0 = left.coerceAtLeast(0.0).toInt(); val y0 = top.coerceAtLeast(0.0).toInt()
        val x1 = right.coerceAtMost(canonicalImage.width.toDouble()).toInt()
        val y1 = bottom.coerceAtMost(canonicalImage.height.toDouble()).toInt()
        val outW = ((x1 - x0) * scale).coerceAtLeast(1)
        val outH = ((y1 - y0) * scale).coerceAtLeast(1)
        val out = RasterImage(outW, outH)
        for (y in 0 until outH) for (x in 0 until outW)
            out[x, y] = canonicalImage.at(x0 + x / scale, y0 + y / scale)
        return out
    }
}

data class OmrEngineConfig(
    val imageOps: ImageOps = PortableImageOps,
    val qrCodec: QrCodec = PortableQrCodec,
    val thresholds: OmrThresholds = OmrThresholds(),
    val clock: Clock = SystemClock
)

/**
 * Eight-stage OMR engine. Each stage is a real image-processing operation; no stage
 * returns a hardcoded or simulated answer. The engine is pure Kotlin and therefore
 * independently testable on the JVM, while Android supplies an OpenCV-backed ImageOps.
 */
class OmrEngine(private val config: OmrEngineConfig = OmrEngineConfig()) {
    fun process(request: OmrRequest): OmrResult<OmrRecognitionResult> = OmrResult.runCatching {
        val warnings = ArrayList<String>()
        // Stage 1 — preprocessing: EXIF rotation, denoise, CLAHE-like normalization,
        // adaptive thresholding (local mean; tolerant of uneven illumination).
        val original = rotateForExif(request.image, request.exifRotationDegrees)
        val ops = config.imageOps
        val denoised = ops.denoise(original)
        val normalized = ops.normalizeContrast(denoised)
        val binary = ops.binarize(normalized, blockSize = 31, c = 7)

        // Stage 2 — registration markers + geometry + sheet orientation.
        val markerResult = MarkerDetector.detect(binary, normalized, config.thresholds, ops)
        warnings += markerResult.warnings
        val detection = markerResult.detection
            ?: throw AppError.Alignment("registration failed: ${markerResult.warnings.joinToString(",")}")

        // Stage 3 — perspective correction to a probe canvas (used to locate the QR).
        val probeW = 1240; val probeH = 1754
        val probeCanonical = canonicalCorners(probeW, probeH)
        val probeHMatrix = Homography.solve(probeCanonical, detection.points)
            ?: throw AppError.Alignment("homography is singular")
        val probeGray = ops.warp(normalized, probeHMatrix, probeW, probeH)
        val probeBinary = ops.warp(binary, probeHMatrix, probeW, probeH)
        val probeRaw = ops.warp(denoised, probeHMatrix, probeW, probeH)

        // Stage 4 — template identification: QR/ID decode, persisted layout lookup,
        // and geometric alignment verification (corner markers, not QR alone).
        val payload = QrSheet.decode(probeBinary, QrSheet.genericRect(probeW, probeH), config.qrCodec)
        var layout = payload?.let { request.layoutRegistry.findByPayload(it) }
        if (layout == null) {
            warnings += "QR_TEMPLATE_DECODE_FALLBACK"
            layout = bestGeometricLayout(normalized, detection.points, request.layoutRegistry)
                ?: throw AppError.Alignment("no registered template matches this sheet")
        }
        val canonicalCorners = canonicalCorners(layout.canonicalWidth, layout.canonicalHeight)
        val finalH = Homography.solve(canonicalCorners, detection.points)
            ?: throw AppError.Alignment("final homography is singular")
        val useProbe = layout.canonicalWidth == probeW && layout.canonicalHeight == probeH
        val canonicalGray = if (useProbe) probeGray
        else ops.warp(normalized, finalH, layout.canonicalWidth, layout.canonicalHeight)
        val canonicalBinary = if (useProbe) probeBinary else rasterOf(
            ops.binarize(
                ops.normalizeContrast(ops.denoise(canonicalGray)), 31, 7
            )
        )
        val canonicalRaw = if (useProbe) probeRaw
        else ops.warp(denoised, finalH, layout.canonicalWidth, layout.canonicalHeight)
        val alignmentScore = LayoutAlignment.score(canonicalBinary, layout)
        if (alignmentScore < config.thresholds.minimumAlignmentScore)
            warnings += "LOW_LAYOUT_ALIGNMENT"
        if (layout.markers.size != 4) throw AppError.Alignment("template has invalid marker definition")

        // Stage 5 + 6 — bubble ROI fill ratios against local unmarked baselines,
        // then BLANK / SINGLE_MARK / MULTIPLE_MARKS / AMBIGUOUS / INVALID classification.
        val readings = BubbleAnalyzer.analyze(canonicalGray, canonicalRaw, canonicalBinary, layout, config.thresholds)
        val questions = BubbleAnalyzer.toRecognitions(readings, request.submissionId)
        val recognizedAnswers = readings.associate { it.questionNumber to it.answer }
        questions.filter { it.classification.name != "SINGLE_MARK" }.forEach {
            warnings += "ITEM_${it.questionNumber}_${it.classification.name}"
        }

        // Stage 7 — student identification (bubble grid or QR) with roster validation.
        val student = StudentIdRecognizer.recognize(
            canonicalGray, canonicalBinary, layout, config.thresholds, request.roster, request.qrStudentNumber
        )
        warnings += student.warnings

        // Stage 8 — immutable structured output plus quality gate and checksums.
        val quality = ImageQualityGate.evaluate(
            original, normalized, ops, 4, detection.skewDegrees, config.thresholds
        )
        warnings += quality.warnings
        OmrRecognitionResult(
            id = Ids.newId("omr"),
            submissionId = request.submissionId,
            templateCode = layout.templateCode,
            examId = layout.examId,
            questionCount = layout.questionCount,
            rotationDegrees = detection.rotationDegrees,
            markerScore = detection.score,
            alignmentScore = alignmentScore,
            quality = quality,
            warnings = warnings.distinct(),
            questions = questions,
            recognizedAnswers = recognizedAnswers,
            studentId = student.studentId,
            recognizedStudentNumber = student.recognizedNumber,
            studentIdConfidence = student.confidence,
            identificationMode = student.mode,
            needsManualAssignment = student.needsManualAssignment,
            processingStatus = ProcessingStatus.RECOGNIZED,
            imageFingerprint = fingerprint(original),
            canonicalImage = canonicalGray,
            canonicalBinary = canonicalBinary,
            canonicalRaw = canonicalRaw,
            layout = layout,
            timestamp = request.nowMillis
        )
    }

    private fun rotateForExif(image: GrayImage, degrees: Int): RasterImage {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return image as? RasterImage ?: RasterImage(image.width, image.height).also { out ->
            for (y in 0 until image.height) for (x in 0 until image.width) out[x, y] = image.at(x, y)
        }
        val quarter = normalized / 90
        val swap = quarter % 2 == 1
        val w = if (swap) image.height else image.width
        val h = if (swap) image.width else image.height
        val out = RasterImage(w, h)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val (nx, ny) = when (quarter) {
                1 -> (image.height - 1 - y) to x
                2 -> (image.width - 1 - x) to (image.height - 1 - y)
                3 -> y to (image.width - 1 - x)
                else -> x to y
            }
            out[nx, ny] = image.at(x, y)
        }
        return out
    }

    private fun rasterOf(image: GrayImage): RasterImage =
        image as? RasterImage ?: RasterImage(image.width, image.height).also { out ->
            for (y in 0 until image.height) for (x in 0 until image.width) out[x, y] = image.at(x, y)
        }

    private fun canonicalCorners(width: Int, height: Int): List<PointD> {
        val mx = width * 0.045; val my = height * 0.045
        return listOf(
            PointD(mx, my), PointD(width - mx, my),
            PointD(width - mx, height - my), PointD(mx, height - my)
        )
    }

    private fun fingerprint(image: GrayImage): String {
        val bytes = ByteArray(image.width * image.height)
        var i = 0
        for (y in 0 until image.height) for (x in 0 until image.width) bytes[i++] = image.at(x, y).toByte()
        return Sha256.of(bytes)
    }

    private fun bestGeometricLayout(
        normalized: GrayImage,
        points: List<PointD>,
        registry: LayoutRegistry
    ): SheetLayout? {
        var best: SheetLayout? = null
        var bestScore = -1.0
        for (candidate in registry.all()) {
            val h = Homography.solve(canonicalCorners(candidate.canonicalWidth, candidate.canonicalHeight), points)
                ?: continue
            val warped = config.imageOps.warp(normalized, h, candidate.canonicalWidth, candidate.canonicalHeight)
            val score = LayoutAlignment.score(config.imageOps.binarize(warped, 31, 7), candidate)
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        return if (bestScore >= config.thresholds.minimumAlignmentScore * 0.6) best else null
    }
}
