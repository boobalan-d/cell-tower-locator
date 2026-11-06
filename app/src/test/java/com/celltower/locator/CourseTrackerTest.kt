package com.celltower.locator

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CourseTrackerTest {
    private val lat0 = 13.0827
    private val lon0 = 80.2707

    private fun at(dx: Double, dy: Double) =
        doubleArrayOf(lat0 + dy / 110_540.0, lon0 + dx / (111_320.0 * cos(Math.toRadians(lat0))))

    private fun walk(courseDeg: Double, yaw: (Int) -> Double, wobble: Double = 0.0): CourseTracker.Course? {
        val rnd = Random(5)
        val t = CourseTracker()
        val r = Math.toRadians(courseDeg)
        var res: CourseTracker.Course? = null
        for (i in 0..20) {                               // 1.4 m/s walking, 1 fix/s
            val along = 1.4 * i
            val side = if (wobble > 0) wobble * sin(i * 1.3) else 0.0
            val p = at(along * sin(r) + side * cos(r) + rnd.nextGaussian() * 1.0,
                       along * cos(r) - side * sin(r) + rnd.nextGaussian() * 1.0)
            res = t.add(CourseTracker.Fix(p[0], p[1], 4.0, i * 1000L, yaw(i))) ?: res
        }
        return res
    }

    @Test
    fun straightWalk_givesCourse() {
        for (c in listOf(0.0, 75.0, 190.0, 310.0)) {
            val res = walk(c, { 12.0 })
            assertNotNull("course $c", res)
            assertTrue("course $c got ${res!!.courseDeg}", abs(wrap180(res.courseDeg - c)) < 8.0)
        }
    }

    @Test
    fun phoneBeingTurned_rejected() {
        assertNull(walk(45.0, { i -> i * 9.0 }))
    }

    @Test
    fun cornerWalk_neverGivesFalseDiagonal() {
        // 10 m north then 14 m east: a naive first→last course would say ~55°, which is wrong for both legs
        val t = CourseTracker()
        var res: CourseTracker.Course? = null
        val pts = (0..7).map { at(0.0, it * 1.4) } + (1..10).map { at(it * 1.4, 9.8) }
        pts.forEachIndexed { i, p -> res = t.add(CourseTracker.Fix(p[0], p[1], 4.0, i * 1000L, 0.0)) ?: res }
        res?.let { assertTrue("got ${it.courseDeg}", abs(wrap180(it.courseDeg - 90.0)) < 10 || abs(wrap180(it.courseDeg)) < 10) }
    }
}
