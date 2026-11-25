package com.omrgrader.omr

import com.omrgrader.domain.CellValue
import com.omrgrader.domain.IdentificationMode
import com.omrgrader.domain.OmrThresholds
import com.omrgrader.domain.Student

interface RosterLookup {
    fun findByNumber(studentNumber: String): Student?
}

class InMemoryRosterLookup(private val students: List<Student>) : RosterLookup {
    override fun findByNumber(studentNumber: String): Student? =
        students.firstOrNull { it.studentNumber.equals(studentNumber, ignoreCase = true) }
}

data class StudentIdResult(
    val recognizedNumber: String?,
    val studentId: String?,
    val confidence: Double,
    val mode: IdentificationMode,
    val needsManualAssignment: Boolean,
    val warnings: List<String>,
    val perColumn: List<Double>
)

/** Stage 7: decode the 0-9 bubble ID grid, then validate against the Room-backed roster. */
object StudentIdRecognizer {
    fun recognize(
        gray: GrayImage,
        binary: GrayImage,
        layout: SheetLayout,
        thresholds: OmrThresholds,
        roster: RosterLookup,
        qrStudentNumber: String? = null
    ): StudentIdResult {
        if (qrStudentNumber != null) {
            val student = roster.findByNumber(qrStudentNumber)
            return StudentIdResult(
                recognizedNumber = qrStudentNumber,
                studentId = student?.id,
                confidence = if (student != null) 0.99 else 0.80,
                mode = IdentificationMode.QR_CODE,
                needsManualAssignment = student == null,
                warnings = if (student == null) listOf("QR_STUDENT_NOT_IN_ROSTER") else emptyList(),
                perColumn = emptyList()
            )
        }
        val warnings = ArrayList<String>()
        val byColumn = layout.idBubbles.groupBy { it.column }.toSortedMap()
        val digits = StringBuilder()
        val confidences = ArrayList<Double>()
        for ((_, specs) in byColumn) {
            val ordered = specs.sortedBy { it.digit }
            val fills = ordered.map { fill(gray, binary, layout, it) }
            val best = fills.withIndex().maxByOrNull { it.value }!!
            val second = fills.withIndex().filter { it.index != best.index }.maxOfOrNull { it.value } ?: 0.0
            val margin = best.value - second
            val digitConfidence = ((margin / thresholds.ambiguousMargin).coerceIn(0.0, 1.0) * 0.6 +
                (best.value / thresholds.markFillRatio.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0) * 0.4)
            confidences += digitConfidence
            if (best.value < thresholds.markFillRatio || margin < thresholds.minimumMargin) {
                digits.append('?')
                warnings += "UNCERTAIN_ID_COLUMN"
            } else digits.append('0' + ordered[best.index].digit)
        }
        val number = digits.toString()
        val valid = '?' !in number
        val student = if (valid) roster.findByNumber(number) else null
        if (valid && student == null) warnings += "STUDENT_ID_NOT_IN_ROSTER"
        if (!valid) warnings += "STUDENT_ID_UNRECOGNIZED"
        val confidence = if (confidences.isEmpty()) 0.0 else confidences.average()
        return StudentIdResult(
            recognizedNumber = if (valid) number else null,
            studentId = student?.id,
            confidence = confidence.coerceIn(0.0, 1.0),
            mode = IdentificationMode.BUBBLE_GRID,
            needsManualAssignment = student == null,
            warnings = warnings.distinct(),
            perColumn = confidences
        )
    }

    private fun fill(gray: GrayImage, binary: GrayImage, layout: SheetLayout, spec: IdBubbleSpec): Double {
        val cx = layout.canonicalX(spec.x); val cy = layout.canonicalY(spec.y)
        val r = spec.radiusPx * 0.66
        var dark = 0; var total = 0; var sum = 0L
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy > r * r) continue
            if (x < 0 || y < 0 || x >= gray.width || y >= gray.height) continue
            total++; sum += gray.at(x, y)
            if (binary.at(x, y) < 128) dark++
        }
        if (total == 0) return 0.0
        return (0.72 * dark.toDouble() / total + 0.28 * (1.0 - sum.toDouble() / total / 255.0)).coerceIn(0.0, 1.0)
    }
}
