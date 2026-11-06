package com.celltower.locator

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.sqrt

/**
 * Reads the phone's motion sensors and feeds [HeadingFusion]:
 *  • GAME_ROTATION_VECTOR  → gyro yaw (relative, smooth, no magnetometer)
 *  • ROTATION_VECTOR       → magnetic heading (only on phones that HAVE a magnetometer)
 *  • MAGNETIC_FIELD        → field strength check: Earth's field is ~25–65 µT; outside that,
 *                            something magnetic is near and the compass is ignored.
 * GPS course measurements are pushed in from the activity via [onCourse].
 */
class HeadingEngine(context: Context, private val onUpdate: () -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val gameRv: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    // A "rotation vector" on a phone without a magnetometer is fake for heading: never use it then
    private val rotRv: Sensor? = if (magSensor != null) sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) else null

    val fusion = HeadingFusion()

    val hasGyro: Boolean get() = gameRv != null
    val hasMagnetometer: Boolean get() = magSensor != null

    /** Latest relative gyro yaw (degrees, arbitrary zero). */
    var gyroYaw: Double? = null
        private set
    /** Latest raw magnetic heading, true-north corrected (degrees). */
    var compassHeading: Double? = null
        private set
    var fieldUT: Double = Double.NaN
        private set
    var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    private var declination = 0.0
    private val m = FloatArray(9)
    private var lastNotify = 0L

    // recent yaw samples for stability checks
    private val yawHist = ArrayDeque<Pair<Long, Double>>()

    val fieldOk: Boolean get() = !fieldUT.isNaN() && fieldUT in 25.0..65.0
    val compassUsable: Boolean
        get() = rotRv != null && fieldOk && magAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        gameRv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotRv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    /** Gyro yaw restarts from a new arbitrary zero after a pause, so the lock must be re-learned. */
    fun stop() {
        sm.unregisterListener(this)
        gyroYaw = null
        yawHist.clear()
        fusion.reset()
    }

    fun updateLocation(loc: Location) {
        declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(),
            loc.altitude.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
    }

    /** Fused true heading, or null until it is locked. */
    fun heading(): Double? {
        val y = gyroYaw ?: return null
        fusion.predict(System.currentTimeMillis())
        return fusion.heading(y)
    }

    /** Circular spread of the gyro yaw over the last [windowMs]: is the phone being held steadily? */
    fun yawSpread(windowMs: Long = 3000): Double? {
        val now = System.currentTimeMillis()
        val ys = yawHist.filter { now - it.first <= windowMs }.map { it.second }
        if (ys.size < 5) return null
        return CourseTracker.circularMeanAndSpread(ys).second
    }

    fun onCourse(c: CourseTracker.Course, calibration: Boolean): Boolean {
        val src = if (calibration) HeadingFusion.Source.CALIBRATION else HeadingFusion.Source.GPS
        val ok = fusion.addAbsolute(c.courseDeg, c.yawDeg, c.sigmaDeg, src, System.currentTimeMillis())
        onUpdate()
        return ok
    }

    /** Doppler course from the GPS chip (accurate when moving) paired with the current yaw. */
    fun onDopplerCourse(courseDeg: Double, sigmaDeg: Double): Boolean {
        val y = gyroYaw ?: return false
        val spread = yawSpread() ?: return false
        if (spread > 10.0) return false
        return fusion.addAbsolute(courseDeg, y, sigmaDeg, HeadingFusion.Source.GPS, System.currentTimeMillis())
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(m, e.values)
                val y = GeoMath.pointingHeading(m)?.toDouble() ?: return
                gyroYaw = y
                yawHist.addLast(now to y)
                while (yawHist.isNotEmpty() && now - yawHist.first().first > 10_000) yawHist.removeFirst()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(m, e.values)
                val h = GeoMath.pointingHeading(m)?.toDouble() ?: return
                val trueH = wrap360(h + declination)
                compassHeading = trueH
                val y = gyroYaw
                if (y != null && compassUsable) fusion.addCompass(trueH, y, now)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val v = e.values
                fieldUT = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
                magAccuracy = e.accuracy
            }
        }
        if (now - lastNotify > 60) { lastNotify = now; onUpdate() }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }
}
