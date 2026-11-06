package com.celltower.locator

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location

/**
 * Compass giving heading relative to TRUE north (magnetic declination corrected
 * from your GPS position), smoothed so the arrow doesn't jitter.
 * Works with the phone held flat or upright (camera pointing ahead).
 */
class Compass(
    context: Context,
    private val onHeading: (degreesTrue: Float, needsCalibration: Boolean) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var hasGravity = false
    private var hasGeomag = false
    private val rotMat = FloatArray(9)

    private var declination = 0f
    private var smoothed = Float.NaN
    private var lowAccuracy = false

    val available: Boolean get() = rotSensor != null || (accSensor != null && magSensor != null)

    fun start() {
        if (rotSensor != null) {
            sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        // Magnetometer is also used to detect when calibration is needed
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    fun updateLocation(loc: Location) {
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            loc.altitude.toFloat(), System.currentTimeMillis()
        ).declination
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                emit()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) gravity[i] = 0.8f * gravity[i] + 0.2f * e.values[i]
                hasGravity = true
                if (rotSensor == null) fromAccMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowAccuracy = e.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                System.arraycopy(e.values, 0, geomag, 0, 3)
                hasGeomag = true
                if (rotSensor == null) fromAccMag()
            }
        }
    }

    private fun fromAccMag() {
        if (hasGravity && hasGeomag && SensorManager.getRotationMatrix(rotMat, null, gravity, geomag)) emit()
    }

    private fun emit() {
        // Heading of the direction the phone is POINTING, valid whether the phone is held
        // flat (top edge points ahead) or upright like a camera (back points ahead).
        // World = R · device. Device Y (top) = column 1, device Z (out of screen) = column 2.
        // Horizontal pointing vector = Y_h − Z_h. When flat Z_h≈0, when upright Y_h≈0.
        val magneticDeg = GeoMath.pointingHeading(rotMat) ?: return
        val trueDeg = (magneticDeg + declination + 720f) % 360f
        smoothed = if (smoothed.isNaN()) trueDeg else {
            val diff = ((trueDeg - smoothed + 540f) % 360f) - 180f   // shortest way round
            (smoothed + 0.2f * diff + 360f) % 360f
        }
        onHeading(smoothed, lowAccuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }
}
