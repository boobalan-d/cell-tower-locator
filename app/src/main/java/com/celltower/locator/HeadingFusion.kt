package com.celltower.locator

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Wrap an angle to (−180, 180]. */
fun wrap180(a: Double): Double {
    var x = (a + 180.0) % 360.0
    if (x < 0) x += 360.0
    return x - 180.0
}

/** Wrap an angle to [0, 360). */
fun wrap360(a: Double): Double {
    var x = a % 360.0
    if (x < 0) x += 360.0
    return x
}

/**
 * Heading fusion, the same principle as INS/GNSS navigation systems:
 *
 *   true heading = gyroYaw + offset
 *
 * gyroYaw comes from Android's GAME_ROTATION_VECTOR (gyroscope + accelerometer, NO magnetometer):
 * smooth, fast and immune to magnetic interference, but its zero point is arbitrary and drifts
 * slowly. The single unknown "offset" is estimated with a 1-D Kalman filter on the circle from
 * absolute references:
 *   • GPS course while walking/driving (most trustworthy outdoors),
 *   • magnetometer heading (only when it passes the field-strength checks and agrees with GPS),
 *   • explicit calibration walk.
 * Outliers are gated at 3σ. While a GPS anchor is fresh the compass is only cross-checked;
 * if it keeps disagreeing with GPS by > 20° it is switched off automatically.
 * Pure Kotlin (no Android classes), so it is unit-tested on the JVM.
 */
class HeadingFusion {

    enum class Source { GPS, COMPASS, CALIBRATION }

    var offset = 0.0
        private set
    var variance = UNKNOWN_VAR
        private set
    var magTrusted = true
        private set
    var magDisagreementDeg = 0.0
        private set
    var lastSource: Source? = null
        private set
    var lastAnchorMs = 0L
        private set
    var gpsAnchors = 0
        private set

    private var lastPredictMs = -1L
    private var gpsRejectStreak = 0
    private var magChecks = 0
    private var lastMagMs = -1L

    val sigmaDeg: Double get() = sqrt(variance)
    val locked: Boolean get() = variance <= LOCK_VAR

    fun reset() {
        offset = 0.0; variance = UNKNOWN_VAR; lastPredictMs = -1L
        gpsRejectStreak = 0; magTrusted = true; magDisagreementDeg = 0.0; magChecks = 0
        gpsAnchors = 0; lastSource = null
    }

    /** Re-enable the magnetometer (e.g. after the user moved away from interference). */
    fun trustCompassAgain() { magTrusted = true; magChecks = 0; magDisagreementDeg = 0.0 }

    /** Grow uncertainty with time to account for gyro drift. */
    fun predict(nowMs: Long) {
        if (lastPredictMs >= 0 && variance < UNKNOWN_VAR) {
            val dt = (nowMs - lastPredictMs).coerceAtLeast(0) / 1000.0
            variance = min(UNKNOWN_VAR, variance + DRIFT_VAR_PER_S * dt)
        }
        lastPredictMs = nowMs
    }

    /** Fused true heading for the current gyro yaw, or null while not locked. */
    fun heading(gyroYawDeg: Double): Double? = if (locked) wrap360(gyroYawDeg + offset) else null

    /**
     * Absolute heading measurement [absDeg] (true north) taken when the gyro read [gyroYawDeg].
     * Returns true if accepted.
     */
    fun addAbsolute(absDeg: Double, gyroYawDeg: Double, sigmaDeg: Double, source: Source, nowMs: Long): Boolean {
        predict(nowMs)
        val z = wrap180(absDeg - gyroYawDeg)
        val r = sigmaDeg * sigmaDeg

        // First GPS fix always overrides a compass-only lock
        val firstGps = source == Source.GPS && gpsAnchors == 0
        if (source == Source.CALIBRATION || firstGps || variance >= UNKNOWN_VAR * 0.5) {
            offset = z; variance = r
            accept(source, nowMs)
            return true
        }
        val innov = wrap180(z - offset)
        if (innov * innov > 9.0 * (variance + r)) {
            // Persistent GPS disagreement means the reference really changed
            // (e.g. phone turned in the hand / put in a mount): re-anchor.
            if (source == Source.GPS && ++gpsRejectStreak >= 3) {
                offset = z; variance = r; gpsRejectStreak = 0
                accept(source, nowMs)
                return true
            }
            return false
        }
        if (source == Source.GPS) gpsRejectStreak = 0
        val k = variance / (variance + r)
        offset = wrap180(offset + k * innov)
        variance = (1 - k) * variance
        accept(source, nowMs)
        return true
    }

    /**
     * Magnetometer heading. It is cross-checked against GPS-anchored estimates and
     * down-weighted while GPS is fresh, so a biased compass can't drag a good fix.
     */
    fun addCompass(magTrueDeg: Double, gyroYawDeg: Double, nowMs: Long): Boolean {
        if (!magTrusted) return false
        if (lastMagMs >= 0 && nowMs - lastMagMs < MAG_INTERVAL_MS) return false
        lastMagMs = nowMs

        // Cross-check against the GPS-anchored estimate
        if (gpsAnchors >= 1 && locked) {
            predict(nowMs)
            val dis = wrap180(wrap180(magTrueDeg - gyroYawDeg) - offset)
            magDisagreementDeg = if (magChecks == 0) dis else 0.92 * magDisagreementDeg + 0.08 * dis
            magChecks++
            if (magChecks >= 20 && abs(magDisagreementDeg) > MAG_DISTRUST_DEG) {
                magTrusted = false
                return false
            }
        }
        // While a GPS anchor is fresh, gyro+GPS beats any phone compass: cross-check only
        val gpsFresh = gpsAnchors >= 1 && nowMs - lastGpsMs < GPS_FRESH_MS
        if (gpsFresh) return false
        return addAbsolute(magTrueDeg, gyroYawDeg, MAG_SIGMA, Source.COMPASS, nowMs)
    }

    private var lastGpsMs = -1L

    private fun accept(source: Source, nowMs: Long) {
        lastSource = source
        lastAnchorMs = nowMs
        if (source == Source.GPS || source == Source.CALIBRATION) {
            gpsAnchors++
            lastGpsMs = nowMs
        }
    }

    companion object {
        const val UNKNOWN_VAR = 1.0e6
        const val LOCK_VAR = 35.0 * 35.0           // show an arrow once σ ≤ 35°
        const val DRIFT_VAR_PER_S = 0.5            // ≈ σ grows ~4°/min (gyro drift)
        const val MAG_SIGMA = 15.0
        const val MAG_INTERVAL_MS = 500L
        const val GPS_FRESH_MS = 120_000L
        const val MAG_DISTRUST_DEG = 20.0
    }
}
