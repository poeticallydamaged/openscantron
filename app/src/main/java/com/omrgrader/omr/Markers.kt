package com.omrgrader.omr

import com.omrgrader.core.AppError
import com.omrgrader.domain.OmrThresholds
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class Component(
    val area: Int,
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val centroid: PointD,
    val fillRatio: Double,
    val holeScore: Double
) {
    val boxWidth: Int get() = maxX - minX + 1
    val boxHeight: Int get() = maxY - minY + 1
}

/** 8-connected component labelling on the binary (dark = 0) image. */
object ConnectedComponents {
    fun find(binary: GrayImage, minArea: Int, maxArea: Int): List<Component> {
        val w = binary.width; val h = binary.height
        val visited = BooleanArray(w * h)
        val out = ArrayList<Component>()
        val stackX = IntArray(w * h)
        val stackY = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val index = y * w + x
            if (visited[index] || binary.at(x, y) > 127) continue
            var sp = 0
            stackX[sp] = x; stackY[sp] = y; sp++
            visited[index] = true
            var area = 0; var sumX = 0L; var sumY = 0L
            var minX = x; var maxX = x; var minY = y; var maxY = y
            while (sp > 0) {
                sp--
                val cx = stackX[sp]; val cy = stackY[sp]
                area++; sumX += cx; sumY += cy
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (visited[ni] || binary.at(nx, ny) > 127) continue
                    visited[ni] = true
                    stackX[sp] = nx; stackY[sp] = ny; sp++
                }
            }
            if (area < minArea || area > maxArea) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val centreX = (minX + maxX) / 2.0; val centreY = (minY + maxY) / 2.0
            // Hole score: fraction of light pixels in the inner third of the bounding box.
            var light = 0; var total = 0
            val rx = (bw / 6).coerceAtLeast(1); val ry = (bh / 6).coerceAtLeast(1)
            for (yy in (centreY - ry).toInt()..(centreY + ry).toInt())
                for (xx in (centreX - rx).toInt()..(centreX + rx).toInt()) {
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    total++
                    if (binary.at(xx, yy) > 127) light++
                }
            out += Component(
                area = area, minX = minX, minY = minY, maxX = maxX, maxY = maxY,
                centroid = PointD(sumX.toDouble() / area, sumY.toDouble() / area),
                fillRatio = area.toDouble() / (bw * bh),
                holeScore = if (total == 0) 0.0 else light.toDouble() / total
            )
        }
        return out
    }
}

data class MarkerDetection(
    val points: List<PointD>,   // canonical order TL, TR, BR, BL in source pixels
    val rotationDegrees: Int,
    val score: Double,
    val skewDegrees: Double,
    val warnings: List<String>
)

object MarkerDetector {
    data class DetectionResult(
        val detection: MarkerDetection?,
        val candidates: List<Component>,
        val warnings: List<String>
    )

    /** Stage 2: detect the four fiducials, validate geometry and resolve 0/90/180/270. */
    fun detect(
        binary: GrayImage,
        gray: GrayImage,
        thresholds: OmrThresholds,
        imageOps: ImageOps
    ): DetectionResult {
        val warnings = ArrayList<String>()
        val minDim = min(binary.width, binary.height).toDouble()
        val expectedSide = minDim * 0.030
        val minArea = (expectedSide * expectedSide * 0.10).toInt().coerceAtLeast(12)
        val maxArea = (expectedSide * expectedSide * 5.0).toInt()
        val components = ConnectedComponents.find(binary, minArea, maxArea).filter { c ->
            val aspect = c.boxWidth.toDouble() / c.boxHeight.coerceAtLeast(1)
            aspect in 0.45..2.2 && c.fillRatio in 0.28..0.98 && c.holeScore > 0.45
        }
        if (components.size < 4) {
            warnings += "MISSING_REGISTRATION_MARKERS"
            return DetectionResult(null, components, warnings)
        }
        // Choose the four candidates with the largest-area convex quadrilateral.
        var best: List<Component>? = null
        var bestScore = -1.0
        val sorted = components.sortedByDescending { it.area }.take(18)
        for (a in sorted.indices) for (b in a + 1 until sorted.size)
            for (c in b + 1 until sorted.size) for (d in c + 1 until sorted.size) {
                val quad = orderCyclic(listOf(sorted[a], sorted[b], sorted[c], sorted[d]).map { it.centroid })
                val q = Quadrilateral(quad)
                val areaRatio = q.area() / (binary.width.toDouble() * binary.height)
                val (edgeRatio, _) = q.edgeRatios()
                if (areaRatio < 0.12 || edgeRatio < 0.28) continue
                val totalArea = (sorted[a].area + sorted[b].area + sorted[c].area + sorted[d].area).toDouble()
                val score = areaRatio * (0.5 + 0.5 * edgeRatio) +
                    0.00001 * totalArea / (minDim * minDim)
                if (score > bestScore) { bestScore = score; best = listOf(sorted[a], sorted[b], sorted[c], sorted[d]) }
            }
        if (best == null) {
            warnings += "MARKER_GEOMETRY_INVALID"
            return DetectionResult(null, components, warnings)
        }
        val cyclic = orderCyclic(best.map { it.centroid })
        // Try all four rotations and use the printed index tab (near canonical TL) to orient.
        var chosen: List<PointD>? = null
        var chosenRotation = 0
        var bestTab = -1.0
        for (rotation in 0..3) {
            val ordered = (0..3).map { cyclic[(it + rotation) % 4] }
            if (!isConvex(ordered)) continue
            val tab = indexTabScore(gray, ordered, imageOps)
            if (tab > bestTab) { bestTab = tab; chosen = ordered; chosenRotation = rotation * 90 }
        }
        if (chosen == null || bestTab < 0.35) {
            warnings += "ORIENTATION_UNRESOLVED"
            // Still use the geometric order but flag the low reliability.
            chosen = cyclic
        }
        val quad = Quadrilateral(chosen)
        val top = quad.points[1].x - quad.points[0].x
        val topY = quad.points[1].y - quad.points[0].y
        val rawSkew = Math.toDegrees(atan2(topY, top)).let { if (it > 90) it - 180 else if (it < -90) it + 180 else it }
        // A deliberate 90/180/270 sheet rotation is resolved above; skew is the residual
        // deviation from the resolved orientation, not the rotation itself.
        var skew = rawSkew - chosenRotation
        while (skew > 90) skew -= 180
        while (skew < -90) skew += 180
        if (abs(skew) > 12.0) warnings += "EXCESSIVE_SKEW"
        val (edgeRatio, _) = quad.edgeRatios()
        val score = (0.45 * edgeRatio + 0.35 * (quad.area() / (binary.width * binary.height.toDouble())) +
            0.20 * bestTab).coerceIn(0.0, 1.0)
        if (score < thresholds.minimumRegistrationScore) warnings += "LOW_REGISTRATION_RELIABILITY"
        return DetectionResult(
            MarkerDetection(chosen, chosenRotation, score, skew, warnings), components, warnings
        )
    }

    private fun orderCyclic(points: List<PointD>): List<PointD> {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        return points.sortedBy { atan2(it.y - cy, it.x - cx) }
    }

    private fun isConvex(points: List<PointD>): Boolean {
        var sign = 0
        for (i in 0..3) {
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun indexTabScore(gray: GrayImage, ordered: List<PointD>, imageOps: ImageOps): Double {
        // Render a small canonical probe using the candidate corner mapping; the printed
        // index tab sits 1.2% inward from the top-left fiducial.
        val probeW = 310; val probeH = 438
        val insetX = 0.045 * probeW; val insetY = 0.045 * probeH
        val canonical = listOf(
            PointD(insetX, insetY), PointD(probeW - insetX, insetY),
            PointD(probeW - insetX, probeH - insetY), PointD(insetX, probeH - insetY)
        )
        val h = Homography.solve(canonical, ordered) ?: return 0.0
        val probe = imageOps.warp(gray, h, probeW, probeH)
        val tabX = (insetX + 0.012 * (probeW - 2 * insetX)).toInt()
        val tabY = (insetY + 0.012 * (probeH - 2 * insetY)).toInt()
        val half = (0.006 * probeW).toInt().coerceAtLeast(2)
        var dark = 0; var total = 0
        for (y in tabY - half..tabY + half) for (x in tabX - half..tabX + half) {
            if (x !in 0 until probeW || y !in 0 until probeH) continue
            total++
            if (probe.at(x, y) < 100) dark++
        }
        val tabDark = if (total == 0) 0.0 else dark.toDouble() / total
        // A control patch away from the tab must stay light; this prevents a uniform
        // shadow from being mistaken for the orientation marker.
        var controlDark = 0; var controlTotal = 0
        val cx = tabX + 3 * half; val cy = tabY + 3 * half
        for (y in cy - half..cy + half) for (x in cx - half..cx + half) {
            if (x !in 0 until probeW || y !in 0 until probeH) continue
            controlTotal++
            if (probe.at(x, y) < 100) controlDark++
        }
        val control = if (controlTotal == 0) 0.0 else controlDark.toDouble() / controlTotal
        return (tabDark - control).coerceIn(0.0, 1.0) * 2.0
    }
}
