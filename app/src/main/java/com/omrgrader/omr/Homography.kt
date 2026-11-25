package com.omrgrader.omr

import kotlin.math.abs
import kotlin.math.hypot

data class PointD(val x: Double, val y: Double) {
    fun distanceTo(other: PointD) = hypot(x - other.x, y - other.y)
}

/** Row-major 3x3 projective transform; h[8] is normalized to 1. */
class Homography(val h: DoubleArray) {
    init { require(h.size == 9) }

    fun map(x: Double, y: Double): PointD {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-9) return PointD(Double.NaN, Double.NaN)
        return PointD((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    fun inverse(): Homography? {
        val a = h
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        if (abs(det) < 1e-9) return null
        val inv = doubleArrayOf(
            c00 / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
            c01 / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
            c02 / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det
        )
        return Homography(inv)
    }

    companion object {
        /** Solve the 8-unknown DLT system for four point correspondences. */
        fun solve(source: List<PointD>, destination: List<PointD>): Homography? {
            require(source.size == 4 && destination.size == 4)
            // A (8x8), b (8), unknowns h0..h7 with h8 = 1.
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0..3) {
                val (x, y) = source[i]
                val (u, v) = destination[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
                b[2 * i] = u
                b[2 * i + 1] = v
            }
            val solution = gaussian(a, b) ?: return null
            return Homography(doubleArrayOf(*solution, 1.0))
        }

        private fun gaussian(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
                if (abs(a[pivot][col]) < 1e-12) return null
                val tempRow = a[col]; a[col] = a[pivot]; a[pivot] = tempRow
                val tempB = b[col]; b[col] = b[pivot]; b[pivot] = tempB
                for (row in col + 1 until n) {
                    val factor = a[row][col] / a[col][col]
                    if (factor == 0.0) continue
                    for (k in col until n) a[row][k] -= factor * a[col][k]
                    b[row] -= factor * b[col]
                }
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var sum = b[i]
                for (j in i + 1 until n) sum -= a[i][j] * x[j]
                x[i] = sum / a[i][i]
            }
            return x
        }
    }
}

data class Quadrilateral(val points: List<PointD>) {
    init { require(points.size == 4) }
    fun perimeter(): Double = (0..3).sumOf { i -> points[i].distanceTo(points[(i + 1) % 4]) }
    fun area(): Double = 0.5 * abs((0..3).sumOf { i ->
        val p = points[i]; val q = points[(i + 1) % 4]
        p.x * q.y - q.x * p.y
    })
    fun edgeRatios(): Pair<Double, Double> {
        val d = (0..3).map { i -> points[i].distanceTo(points[(i + 1) % 4]) }
        val horizontal = (d[0] + d[2]) / 2
        val vertical = (d[1] + d[3]) / 2
        return if (horizontal == 0.0 || vertical == 0.0) 0.0 to 0.0
        else minOf(horizontal, vertical) / maxOf(horizontal, vertical) to horizontal / vertical
    }
}
