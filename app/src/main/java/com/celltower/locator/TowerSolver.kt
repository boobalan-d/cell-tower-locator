package com.celltower.locator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One measurement: where you were, how strong the tower was, and (if known) Timing-Advance distance. */
data class Sample(val lat: Double, val lon: Double, val dbm: Int, val taM: Int?, val time: Long)

/** A soft hint about where the tower is (e.g. the OpenCelliD position) with its uncertainty. */
data class Prior(val lat: Double, val lon: Double, val sigmaM: Double)

data class Solution(
    val lat: Double,
    val lon: Double,
    val errorM: Double,        // ~68 % confidence radius
    val pathLossExp: Double,   // fitted propagation exponent (2 = free space, 3-4 = city)
    val points: Int,
    val taPoints: Int,
    val usedPrior: Boolean,
    val usedSignal: Boolean
)

/**
 * Finds the most likely tower position by jointly fitting:
 *  1. a log-distance path-loss model   RSRP = A − 10·n·log10(d)   (A and n fitted per candidate),
 *  2. Timing-Advance distance rings     d ≈ TA × 78 m             (LTE, when the modem reports it),
 *  3. an optional prior (database position with its range).
 * The fit is a coarse-to-fine grid search over the total χ² cost. Unlike a simple
 * centroid, it can place the tower OUTSIDE the area you walked.
 * Pure Kotlin (no Android classes), so it is unit-tested on the JVM.
 */
object TowerSolver {
    const val MIN_SIGNAL_POINTS = 8
    const val MIN_SPREAD_M = 50.0
    private const val SIGMA_RSS = 6.0     // dB, typical shadow fading
    private const val SIGMA_TA = 80.0     // m, LTE TA resolution ≈ 78 m
    private const val DELTA_CHI2_68 = 2.3 // 2-parameter 68 % contour

    fun solve(samples: List<Sample>, prior: Prior?): Solution? {
        val n = samples.size
        if (n == 0) return null

        val lat0 = samples.sumOf { it.lat } / n
        val lon0 = samples.sumOf { it.lon } / n
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        val xs = DoubleArray(n) { (samples[it].lon - lon0) * kx }
        val ys = DoubleArray(n) { (samples[it].lat - lat0) * ky }
        val rss = DoubleArray(n) { samples[it].dbm.toDouble() }
        val ta = DoubleArray(n) { samples[it].taM?.toDouble() ?: Double.NaN }
        val taCount = ta.count { !it.isNaN() }

        var spread = 0.0
        for (i in 0 until n) spread = max(spread, hypot(xs[i], ys[i]))
        val useRss = n >= MIN_SIGNAL_POINTS && spread >= MIN_SPREAD_M

        if (!useRss && taCount == 0) return null
        if (!useRss && taCount < 3 && prior == null) return null

        // Neighbouring readings are correlated; cap their total influence
        val rssW = min(1.0, 25.0 / n)
        val taW = if (taCount > 0) min(1.0, 15.0 / taCount) else 0.0
        val px = prior?.let { (it.lon - lon0) * kx } ?: 0.0
        val py = prior?.let { (it.lat - lat0) * ky } ?: 0.0
        val ps = prior?.sigmaM ?: 1.0

        val lx = DoubleArray(n)
        var lastExp = 3.0

        fun cost(x: Double, y: Double): Double {
            var c = 0.0
            if (useRss) {
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                for (i in 0 until n) {
                    val d = max(hypot(xs[i] - x, ys[i] - y), 10.0)
                    val l = -10.0 * log10(d)
                    lx[i] = l; sx += l; sy += rss[i]; sxx += l * l; sxy += l * rss[i]
                }
                val den = n * sxx - sx * sx
                var e = if (abs(den) > 1e-9) (n * sxy - sx * sy) / den else 3.0
                if (e.isNaN()) e = 3.0
                e = e.coerceIn(1.6, 5.0)
                val a = (sy - e * sx) / n
                var s = 0.0
                for (i in 0 until n) {
                    val r = rss[i] - (a + e * lx[i]); s += r * r
                }
                c += rssW * s / (SIGMA_RSS * SIGMA_RSS)
                lastExp = e
            }
            if (taCount > 0) {
                var s = 0.0
                for (i in 0 until n) if (!ta[i].isNaN()) {
                    val r = (hypot(xs[i] - x, ys[i] - y) - ta[i]) / SIGMA_TA; s += r * r
                }
                c += taW * s
            }
            if (prior != null) {
                val r = hypot(x - px, y - py) / ps; c += r * r
            }
            return c
        }

        // Start from the signal-weighted centroid
        val best = rss.max()
        var sw = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val w = 10.0.pow((rss[i] - best) / 20.0); sw += w; cx += w * xs[i]; cy += w * ys[i]
        }
        cx /= sw; cy /= sw

        var half = max(2000.0, 3 * spread)
        if (prior != null) half = max(half, hypot(px - cx, py - cy) + 2 * ps)
        half = min(half, 15_000.0)
        val searchHalf = half

        var bx = cx; var by = cy; var bestCost = Double.MAX_VALUE
        for (level in 0 until 4) {
            val g = if (level == 0) 81 else 41
            val step = 2 * half / (g - 1)
            var nbx = bx; var nby = by
            for (i in 0 until g) for (j in 0 until g) {
                val x = bx - half + i * step
                val y = by - half + j * step
                val c = cost(x, y)
                if (c < bestCost) { bestCost = c; nbx = x; nby = y }
            }
            bx = nbx; by = nby
            half = 2 * step
        }

        val cMin = cost(bx, by)
        val exp = lastExp

        // Uncertainty: distance (in 16 directions) to the 68 % cost contour
        var sumSq = 0.0
        for (k in 0 until 16) {
            val ang = 2 * PI * k / 16
            var r = 5.0
            while (r < searchHalf) {
                if (cost(bx + r * cos(ang), by + r * sin(ang)) > cMin + DELTA_CHI2_68) break
                r *= 1.15
            }
            r = min(r, searchHalf); sumSq += r * r
        }

        return Solution(
            lat = lat0 + by / ky, lon = lon0 + bx / kx,
            errorM = sqrt(sumSq / 16), pathLossExp = exp,
            points = n, taPoints = taCount,
            usedPrior = prior != null, usedSignal = useRss
        )
    }
}
