package com.omrgrader.omr

import com.omrgrader.core.Json
import com.omrgrader.core.JsonValue
import com.omrgrader.domain.PageSize
import com.omrgrader.export.BitMatrix
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** All coordinates are normalized to the quadrilateral formed by the four marker centroids. */
data class NormRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun contains(nx: Double, ny: Double) = nx >= x && ny >= y && nx <= x + w && ny <= y + h
}

data class MarkerSpec(val id: String, val x: Double, val y: Double, val sizePx: Double)

data class BubbleSpec(val question: Int, val label: String, val x: Double, val y: Double, val radiusPx: Double)

data class IdBubbleSpec(val column: Int, val digit: Int, val x: Double, val y: Double, val radiusPx: Double)

/**
 * Deterministic normalized layout definition. Every coordinate is in [0.0..1.0] relative
 * to the four registration marker centroids, which makes A4 and Letter, and any capture
 * resolution, map to exactly the same canonical regions.
 */
data class SheetLayout(
    val templateCode: String,
    val examId: String,
    val version: Int,
    val pageSize: PageSize,
    val questionCount: Int,
    val answerChoices: List<String>,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val markerInsetX: Double,
    val markerInsetY: Double,
    val markerSizePx: Double,
    val markers: List<MarkerSpec>,
    val indexTab: NormRect,
    val qrRect: NormRect,
    val idColumns: Int,
    val bubbleRadiusPx: Double,
    val bubbles: List<BubbleSpec>,
    val idBubbles: List<IdBubbleSpec>
) {
    val innerWidth: Double get() = canonicalWidth - 2 * markerInsetX * canonicalWidth
    val innerHeight: Double get() = canonicalHeight - 2 * markerInsetY * canonicalHeight

    fun canonicalX(nx: Double): Double = markerInsetX * canonicalWidth + nx * innerWidth
    fun canonicalY(ny: Double): Double = markerInsetY * canonicalHeight + ny * innerHeight

    fun canonicalPoint(nx: Double, ny: Double) = PointD(canonicalX(nx), canonicalY(ny))

    fun markerPoint(index: Int): PointD = canonicalPoint(markers[index].x, markers[index].y)

    fun qrPixelRect(): NormRect = NormRect(
        canonicalX(qrRect.x), canonicalY(qrRect.y),
        qrRect.w * innerWidth, qrRect.h * innerHeight
    )

    fun metadataPayload(): String = listOf(
        "OMR1", templateCode, "v$version", examId,
        questionCount.toString(), answerChoices.joinToString("")
    ).joinToString("|")

    fun toJson(): String {
        fun rect(r: NormRect) = Json.obj("x" to Json.num(r.x), "y" to Json.num(r.y),
            "w" to Json.num(r.w), "h" to Json.num(r.h))
        val root = Json.obj(
            "schema" to Json.num(1),
            "templateCode" to Json.str(templateCode),
            "examId" to Json.str(examId),
            "version" to Json.num(version),
            "pageSize" to Json.str(pageSize.name),
            "questionCount" to Json.num(questionCount),
            "answerChoices" to Json.arr(answerChoices.map { Json.str(it) }),
            "canonicalWidth" to Json.num(canonicalWidth),
            "canonicalHeight" to Json.num(canonicalHeight),
            "markerInsetX" to Json.num(markerInsetX),
            "markerInsetY" to Json.num(markerInsetY),
            "markerSizePx" to Json.num(markerSizePx),
            "markers" to Json.arr(markers.map { Json.obj("id" to Json.str(it.id), "x" to Json.num(it.x),
                "y" to Json.num(it.y), "sizePx" to Json.num(it.sizePx)) }),
            "indexTab" to rect(indexTab),
            "qrRect" to rect(qrRect),
            "idColumns" to Json.num(idColumns),
            "bubbleRadiusPx" to Json.num(bubbleRadiusPx),
            "bubbles" to Json.arr(bubbles.map { Json.obj("q" to Json.num(it.question), "label" to Json.str(it.label),
                "x" to Json.num(it.x), "y" to Json.num(it.y), "r" to Json.num(it.radiusPx)) }),
            "idBubbles" to Json.arr(idBubbles.map { Json.obj("col" to Json.num(it.column), "digit" to Json.num(it.digit),
                "x" to Json.num(it.x), "y" to Json.num(it.y), "r" to Json.num(it.radiusPx)) })
        )
        return Json.stringify(root)
    }

    companion object {
        fun fromJson(text: String): SheetLayout {
            val o = Json.parse(text) as JsonValue.Obj
            fun rect(v: JsonValue?): NormRect {
                val r = v as JsonValue.Obj
                return NormRect(r.double("x"), r.double("y"), r.double("w"), r.double("h"))
            }
            val markers = o.array("markers").map { it as JsonValue.Obj }.map {
                MarkerSpec(it.string("id"), it.double("x"), it.double("y"), it.double("sizePx"))
            }
            val bubbles = o.array("bubbles").map { it as JsonValue.Obj }.map {
                BubbleSpec(it.int("q"), it.string("label"), it.double("x"), it.double("y"), it.double("r"))
            }
            val idBubbles = o.array("idBubbles").map { it as JsonValue.Obj }.map {
                IdBubbleSpec(it.int("col"), it.int("digit"), it.double("x"), it.double("y"), it.double("r"))
            }
            return SheetLayout(
                templateCode = o.string("templateCode"), examId = o.string("examId"),
                version = o.int("version"), pageSize = PageSize.valueOf(o.string("pageSize", "A4")),
                questionCount = o.int("questionCount"),
                answerChoices = o.array("answerChoices").map { (it as JsonValue.Str).value },
                canonicalWidth = o.int("canonicalWidth"), canonicalHeight = o.int("canonicalHeight"),
                markerInsetX = o.double("markerInsetX"), markerInsetY = o.double("markerInsetY"),
                markerSizePx = o.double("markerSizePx"), markers = markers,
                indexTab = rect(o["indexTab"]), qrRect = rect(o["qrRect"]),
                idColumns = o.int("idColumns"), bubbleRadiusPx = o.double("bubbleRadiusPx"),
                bubbles = bubbles, idBubbles = idBubbles
            )
        }
    }
}

object LayoutFactory {
    fun canonicalSize(pageSize: PageSize): Pair<Int, Int> = when (pageSize) {
        PageSize.A4 -> 1240 to 1754
        PageSize.LETTER -> 1275 to 1650
    }

    fun templateCode(examId: String, pageSize: PageSize, questionCount: Int, version: Int): String {
        val safe = examId.filter { it.isLetterOrDigit() }.takeLast(8).uppercase().padStart(8, '0')
        return "TPL-${pageSize.name.take(1)}${questionCount.toString().padStart(3, '0')}-V$version-$safe"
    }

    fun build(
        examId: String,
        pageSize: PageSize,
        questionCount: Int,
        choices: List<String>,
        idColumns: Int = 8,
        version: Int = 1,
        templateCode: String = templateCode(examId, pageSize, questionCount, version)
    ): SheetLayout {
        require(questionCount in 1..200) { "questionCount out of range" }
        require(choices.isNotEmpty() && choices.size <= 6)
        val (w, h) = canonicalSize(pageSize)
        val insetX = 0.045; val insetY = 0.045
        val innerW = w * (1 - 2 * insetX)
        val innerH = h * (1 - 2 * insetY)
        val markerSize = min(w, h) * 0.030

        val columns = when {
            questionCount <= 25 -> 1
            questionCount <= 50 -> 2
            questionCount <= 75 -> 3
            else -> 4
        }
        val rows = ceil(questionCount.toDouble() / columns).toInt()
        val top = 0.35; val bottom = 0.965
        val rowStep = (bottom - top) / rows
        val colWidth = 0.96 / columns
        val bubbles = ArrayList<BubbleSpec>(questionCount * choices.size)
        var radiusPx = 0.0
        for (q in 1..questionCount) {
            val col = (q - 1) / rows
            val row = (q - 1) % rows
            val x0 = 0.02 + col * colWidth + 0.040
            val xEnd = 0.02 + (col + 1) * colWidth - 0.012
            val step = if (choices.size > 1) (xEnd - x0) / (choices.size - 1) else 0.0
            val r = min(step * 0.34 * innerW, rowStep * 0.34 * innerH).coerceAtLeast(4.0)
            radiusPx = r
            val y = top + row * rowStep
            choices.forEachIndexed { index, label ->
                bubbles += BubbleSpec(q, label, x0 + index * step, y, r)
            }
        }

        val idBubbles = ArrayList<IdBubbleSpec>(idColumns * 10)
        val idStepX = 0.40 / (idColumns - 1).coerceAtLeast(1)
        val idStepY = 0.145 / 9
        val idRadius = min(idStepX * 0.32 * innerW, idStepY * 0.32 * innerH).coerceAtLeast(3.0)
        for (col in 0 until idColumns) for (digit in 0..9) {
            idBubbles += IdBubbleSpec(col, digit, 0.05 + col * idStepX, 0.17 + digit * idStepY, idRadius)
        }

        val qrSizePx = min(0.205 * innerW, 0.170 * innerH)
        val qrW = qrSizePx / innerW; val qrH = qrSizePx / innerH
        val qrRect = NormRect(0.80 - qrW / 2, 0.115 - qrH / 2, qrW, qrH)
        val tabSizePx = min(w, h) * 0.011
        val indexTab = NormRect(0.012, 0.012, tabSizePx / innerW, tabSizePx / innerH)

        return SheetLayout(
            templateCode = templateCode, examId = examId, version = version,
            pageSize = pageSize, questionCount = questionCount, answerChoices = choices,
            canonicalWidth = w, canonicalHeight = h,
            markerInsetX = insetX, markerInsetY = insetY, markerSizePx = markerSize,
            markers = listOf(
                MarkerSpec("TL", 0.0, 0.0, markerSize),
                MarkerSpec("TR", 1.0, 0.0, markerSize),
                MarkerSpec("BR", 1.0, 1.0, markerSize),
                MarkerSpec("BL", 0.0, 1.0, markerSize)
            ),
            indexTab = indexTab, qrRect = qrRect, idColumns = idColumns,
            bubbleRadiusPx = radiusPx, bubbles = bubbles, idBubbles = idBubbles
        )
    }
}

interface LayoutRegistry {
    fun findByCode(templateCode: String): SheetLayout?
    fun findByPayload(payload: String): SheetLayout?
    fun all(): List<SheetLayout>
}

class InMemoryLayoutRegistry(private val layouts: List<SheetLayout>) : LayoutRegistry {
    override fun findByCode(templateCode: String): SheetLayout? = layouts.firstOrNull { it.templateCode == templateCode }
    override fun findByPayload(payload: String): SheetLayout? =
        layouts.firstOrNull { it.metadataPayload() == payload } ?: run {
            val parts = payload.split('|')
            if (parts.size >= 2) findByCode(parts[1]) else null
        }
    override fun all(): List<SheetLayout> = layouts
}

/** Verification that the printed geometry is where the recognition engine expects it. */
object LayoutAlignment {
    fun score(image: GrayImage, layout: SheetLayout): Double {
        val sample = layout.bubbles.take(min(120, layout.bubbles.size))
        if (sample.isEmpty()) return 0.0
        var hits = 0
        for (b in sample) {
            val cx = layout.canonicalX(b.x); val cy = layout.canonicalY(b.y)
            val r = b.radiusPx * 0.82
            var dark = 0; var total = 0
            for (a in 0 until 16) {
                val angle = a * Math.PI / 8
                val x = (cx + r * kotlin.math.cos(angle)).roundToInt()
                val y = (cy + r * kotlin.math.sin(angle)).roundToInt()
                if (x in 0 until image.width && y in 0 until image.height) {
                    total++
                    if (image.at(x, y) < 110) dark++
                }
            }
            if (total > 0 && dark.toDouble() / total > 0.25) hits++
        }
        return hits.toDouble() / sample.size
    }
}
