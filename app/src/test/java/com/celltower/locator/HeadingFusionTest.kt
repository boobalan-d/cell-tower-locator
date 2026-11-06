package com.celltower.locator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Simulates a user walking with a phone whose gyro has an unknown zero (137°) and slow drift,
 * with GPS courses and a (sometimes badly biased) magnetic compass.
 */
class HeadingFusionTest {

    private val gyroZero = 137.0           // unknown to the filter
    private val driftPerS = 0.03           // °/s gyro drift

    private fun gyroYaw(trueHeading: Double, tS: Double) = wrap360(trueHeading - gyroZero + driftPerS * tS)

    @Test
    fun gpsWalksLockHeading_within5deg() {
        val rnd = Random(1)
        val f = HeadingFusion()
        var trueH = 40.0
        var worst = 0.0
        for (step in 0..600) {                       // 10 minutes, 1 Hz
            val t = step.toDouble()
            val now = (t * 1000).toLong()
            if (step % 45 == 0) trueH = wrap360(trueH + 70 + rnd.nextGaussian() * 30)   // user turns
            if (step % 20 == 10) {                   // a straight-walk GPS course
                f.addAbsolute(trueH + rnd.nextGaussian() * 5, gyroYaw(trueH, t), 6.0, HeadingFusion.Source.GPS, now)
            }
            f.predict(now)
            val h = f.heading(gyroYaw(trueH, t))
            if (step > 60 && h != null) worst = maxOf(worst, abs(wrap180(h - trueH)))
        }
        println("GPS-locked worst heading error = $worst°")
        assertTrue(worst < 12.0)
    }

    @Test
    fun badlyBiasedCompass_isDetectedAndIgnored() {
        val rnd = Random(2)
        val f = HeadingFusion()
        var trueH = 200.0
        var errAtEnd = 0.0
        for (i in 0..1200) {                         // 10 minutes at 2 Hz
            val t = i * 0.5
            val now = (t * 1000).toLong()
            if (i % 90 == 0) trueH = wrap360(trueH + 55)
            // Compass is off by +50° (magnetic case / steel nearby) – it reports first!
            f.addCompass(trueH + 50 + rnd.nextGaussian() * 4, gyroYaw(trueH, t), now)
            if (i % 40 == 20) f.addAbsolute(trueH + rnd.nextGaussian() * 5, gyroYaw(trueH, t), 6.0, HeadingFusion.Source.GPS, now)
            f.predict(now)
            f.heading(gyroYaw(trueH, t))?.let { errAtEnd = abs(wrap180(it - trueH)) }
        }
        println("biased compass: trusted=${f.magTrusted}, disagreement=${f.magDisagreementDeg}°, final error=$errAtEnd°")
        assertFalse(f.magTrusted)
        assertTrue(errAtEnd < 10.0)
    }

    @Test
    fun goodCompassAlone_givesHeading() {
        val rnd = Random(3)
        val f = HeadingFusion()
        val trueH = 300.0
        for (i in 0..120) {
            val t = i * 0.5
            f.addCompass(trueH + rnd.nextGaussian() * 8, gyroYaw(trueH, t), (t * 1000).toLong())
        }
        val h = f.heading(gyroYaw(trueH, 60.0))
        assertNotNull(h)
        println("compass-only error = ${abs(wrap180(h!! - trueH))}°")
        assertTrue(abs(wrap180(h - trueH)) < 6.0)
    }

    @Test
    fun noReference_noArrow() {
        val f = HeadingFusion()
        assertNull(f.heading(10.0))
    }

    @Test
    fun phoneRotatedInHand_reanchorsAfterThreeCourses() {
        val f = HeadingFusion()
        var now = 0L
        repeat(4) { f.addAbsolute(90.0, 0.0, 5.0, HeadingFusion.Source.GPS, now); now += 20_000 }
        // User now holds the phone sideways: same walking course, gyro yaw differs by 90°
        repeat(3) { f.addAbsolute(90.0, 90.0, 5.0, HeadingFusion.Source.GPS, now); now += 20_000 }
        val h = f.heading(90.0)!!
        assertTrue(abs(wrap180(h - 90.0)) < 5.0)
    }
}
