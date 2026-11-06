package com.celltower.locator

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Derives a trustworthy walking/driving course from the GPS track and pairs it with the
 * gyro yaw measured over the same stretch. A result is produced only when:
 *  • you moved ≥ [minDistM] in ≤ 30 s with good GPS (accuracy ≤ 15 m),
 *  • the path was straight (path length ≤ 1.2 × straight distance),
 *  • the phone was held steadily (gyro-yaw spread ≤ 15°), so phone direction ≈ walking direction.
 */
class CourseTracker(private val minDistM: Double = 15.0) {

    data class Fix(val lat: Double, val lon: Double, val accM: Double, val timeMs: Long, val yawDeg: Double?)
    data class Course(val courseDeg: Double, val yawDeg: Double, val sigmaDeg: Double, val distM: Double)

    private val fixes = ArrayDeque<Fix>()

    /** Metres covered in the current straight segment (for "walk 15 m" progress). */
    var progressM = 0.0
        private set

    fun clear() { fixes.clear(); progressM = 0.0 }

    fun add(f: Fix): Course? {
        if (f.accM > 15.0) return null
        fixes.addLast(f)
        while (fixes.isNotEmpty() && f.timeMs - fixes.first().timeMs > 30_000) fixes.removeFirst()
        if (fixes.size < 3) { progressM = 0.0; return null }

        val first = fixes.first()
        val straight = GeoMath.distance(first.lat, first.lon, f.lat, f.lon)
        var path = 0.0
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]; val b = fixes[i]
            path += GeoMath.distance(a.lat, a.lon, b.lat, b.lon)
        }
        // A bend (or GPS jitter while standing) → restart the segment from recent fixes
        if (straight > 5.0 && path > 1.2 * straight + 6.0) {
            while (fixes.size > 2) fixes.removeFirst()
            progressM = 0.0
            return null
        }
        progressM = straight
        if (straight < minDistM) return null

        val yaws = fixes.mapNotNull { it.yawDeg }
        if (yaws.size < fixes.size / 2 + 1) return null
        val (meanYaw, spread) = circularMeanAndSpread(yaws)
        if (spread > 15.0) {
            // Phone was being turned around: can't link phone direction to walking direction
            while (fixes.size > 2) fixes.removeFirst()
            return null
        }
        val course = GeoMath.bearing(first.lat, first.lon, f.lat, f.lon).toDouble()
        val posErr = hypot(first.accM, f.accM) * 0.5
        val sigma = Math.toDegrees(atan2(posErr, straight)).coerceIn(3.0, 25.0)
        val result = Course(course, meanYaw, sigma, straight)
        // Start a fresh, independent segment from the latest fix
        fixes.clear(); fixes.addLast(f); progressM = 0.0
        return result
    }

    companion object {
        /** Circular mean and spread (circular standard deviation, degrees). */
        fun circularMeanAndSpread(deg: List<Double>): Pair<Double, Double> {
            var s = 0.0; var c = 0.0
            for (d in deg) { val r = Math.toRadians(d); s += sin(r); c += cos(r) }
            val n = deg.size.toDouble()
            val rLen = (hypot(s, c) / n).coerceIn(1e-9, 1.0)
            val mean = wrap360(Math.toDegrees(atan2(s, c)))
            val spread = Math.toDegrees(sqrt(max(0.0, -2.0 * kotlin.math.ln(rLen))))
            return mean to spread
        }
    }
}
