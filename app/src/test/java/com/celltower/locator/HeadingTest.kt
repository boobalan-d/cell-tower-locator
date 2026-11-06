package com.celltower.locator

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Checks the compass maths for phones held flat, upright and tilted, facing any direction. */
class HeadingTest {

    /** Rotation matrix (world = R·device) for a phone whose top faces [bearing], pitched up by [pitch]. */
    private fun matrix(bearingDeg: Double, pitchDeg: Double): FloatArray {
        val t = Math.toRadians(bearingDeg); val p = Math.toRadians(pitchDeg)
        // Device axes expressed in world (East, North, Up)
        val xAxis = doubleArrayOf(cos(t), -sin(t), 0.0)                               // right edge
        val yAxis = doubleArrayOf(sin(t) * cos(p), cos(t) * cos(p), sin(p))           // top edge
        val zAxis = doubleArrayOf(-sin(t) * sin(p), -cos(t) * sin(p), cos(p))         // out of screen
        return FloatArray(9) { i ->
            val row = i / 3; val col = i % 3
            when (col) { 0 -> xAxis[row]; 1 -> yAxis[row]; else -> zAxis[row] }.toFloat()
        }
    }

    private fun angleDiff(a: Float, b: Double) = (((a - b) % 360 + 540) % 360 - 180)

    @Test
    fun flatUprightAndTilted_allFaceTheRightWay() {
        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 330.0)) {
            for (pitch in listOf(0.0, 30.0, 60.0, 85.0)) {   // 0 = flat on palm, 85 ≈ upright
                val h = GeoMath.pointingHeading(matrix(bearing, pitch))!!
                assertEquals("bearing $bearing pitch $pitch", 0.0, angleDiff(h, bearing), 1.0)
            }
        }
    }

    @Test
    fun bearingMath_knownCities() {
        // Chennai → Bengaluru is almost due west (~ 268°)
        val b = GeoMath.bearing(13.0827, 80.2707, 12.9716, 77.5946)
        assertEquals(267.9, b.toDouble(), 0.5)
        val d = GeoMath.distance(13.0827, 80.2707, 12.9716, 77.5946)
        assertEquals(290_000.0, d, 10_000.0)
    }
}
