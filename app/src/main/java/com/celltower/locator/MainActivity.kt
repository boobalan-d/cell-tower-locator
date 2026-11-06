package com.celltower.locator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // ---- system services & helpers
    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private lateinit var compass: Compass
    private lateinit var lookup: TowerLookup
    private val estimator = SignalEstimator()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // ---- views
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvNeighbors: TextView
    private lateinit var arrow: ImageView
    private lateinit var map: MapView
    private lateinit var btnSim: Button

    // ---- state
    private var registered: List<CellData> = emptyList()
    private var simIndex = 0
    private var serving: CellData? = null
    private var dbTower: TowerResult? = null
    private var lookupMsg: String? = null
    private var myLoc: Location? = null
    private var heading = Float.NaN
    private var compassNeedsCal = false
    private var useEstimate = false
    private var mapFitted = false
    private var sensing = false

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshCells()
            handler.postDelayed(this, 3000)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSensing()
            else tvStatus.text = getString(R.string.need_permission)
        }

    // Implement every method: on Android 10 they are abstract, lambdas would crash.
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // ---- map overlays
    private val meMarker by lazy {
        Marker(map).apply {
            title = "You"
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_me)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
    }
    private val dbMarker by lazy {
        Marker(map).apply {
            title = "Tower (OpenCelliD estimate)"
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_tower)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
    private val estMarker by lazy {
        Marker(map).apply {
            title = "Tower (your signal estimate)"
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_tower_est)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
    private val rangeCircle by lazy {
        Polygon(map).apply {
            fillPaint.color = 0x221E88E5
            outlinePaint.color = 0x991E88E5.toInt()
            outlinePaint.strokeWidth = 3f
        }
    }
    private val line by lazy {
        Polyline(map).apply {
            outlinePaint.color = 0xFFE53935.toInt()
            outlinePaint.strokeWidth = 6f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStatus = findViewById(R.id.tvStatus)
        tvNetwork = findViewById(R.id.tvNetwork)
        tvSignal = findViewById(R.id.tvSignal)
        tvTarget = findViewById(R.id.tvTarget)
        tvDistance = findViewById(R.id.tvDistance)
        tvHeading = findViewById(R.id.tvHeading)
        tvNeighbors = findViewById(R.id.tvNeighbors)
        arrow = findViewById(R.id.arrow)
        map = findViewById(R.id.map)
        btnSim = findViewById(R.id.btnSim)

        tm = getSystemService(TelephonyManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        lookup = TowerLookup(this)
        compass = Compass(this) { deg, needsCal ->
            heading = deg
            compassNeedsCal = needsCal
            updateGuidance()
        }

        setupMap()

        findViewById<Button>(R.id.btnLookup).setOnClickListener { doLookup() }
        findViewById<Button>(R.id.btnTarget).setOnClickListener { toggleTarget() }
        findViewById<Button>(R.id.btnMaps).setOnClickListener { openDirections() }
        findViewById<Button>(R.id.btnKey).setOnClickListener { askApiKey() }
        btnSim.setOnClickListener {
            if (registered.size > 1) {
                simIndex = (simIndex + 1) % registered.size
                refreshCells()
            }
        }

        if (!compass.available) tvHeading.text = getString(R.string.no_compass)

        if (!hasFine()) {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        if (prefs.getString("api_key", null).isNullOrBlank()) askApiKey()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        compass.start()
        if (hasFine()) startSensing()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        compass.stop()
        stopSensing()
    }

    // ------------------------------------------------------------------ sensing

    private fun hasFine() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startSensing() {
        if (sensing || !hasFine()) return
        sensing = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(p)) continue
            try {
                lm.requestLocationUpdates(p, 2000L, 0f, locListener, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (myLoc == null) onNewLocation(it) }
            } catch (_: Exception) { }
        }
        handler.post(poll)
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        handler.removeCallbacks(poll)
        lm.removeUpdates(locListener)
    }

    private fun onNewLocation(loc: Location) {
        val cur = myLoc
        // Prefer GPS; accept network fixes only if we have nothing recent
        val accept = cur == null || loc.provider == LocationManager.GPS_PROVIDER ||
            System.currentTimeMillis() - cur.time > 10_000
        if (!accept) return
        myLoc = loc
        compass.updateLocation(loc)
        updateGuidance()
        updateMap()
    }

    @SuppressLint("MissingPermission")
    private fun refreshCells() {
        if (!hasFine()) return
        tvStatus.text = if (!lm.isLocationEnabled) getString(R.string.location_off) else ""
        try {
            tm.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = handleCells(cellInfo)
                override fun onError(errorCode: Int, detail: Throwable?) =
                    handleCells(tm.allCellInfo.orEmpty())
            })
        } catch (e: SecurityException) {
            tvStatus.text = getString(R.string.need_permission)
        }
    }

    private fun handleCells(infos: List<CellInfo>) {
        val cells = infos.mapNotNull { CellReader.parse(it) }
        registered = cells.filter { it.registered && it.isComplete }
        val neighbors = cells.filter { !it.registered }.sortedByDescending { it.dbm ?: -999 }

        btnSim.visibility = if (registered.size > 1) View.VISIBLE else View.GONE

        if (registered.isEmpty()) {
            tvNetwork.text = getString(R.string.no_cell)
            tvSignal.text = ""
            tvNeighbors.text = ""
            return
        }
        if (simIndex >= registered.size) simIndex = 0
        val cell = registered[simIndex]
        val changed = cell.key != serving?.key
        serving = cell

        tvNetwork.text = describe(cell)
        tvSignal.text = cell.dbm?.let { "$it dBm  •  ${CellReader.quality(cell)}" } ?: "Signal: n/a"
        tvNeighbors.text = if (neighbors.isEmpty()) "Neighbour cells: none reported" else
            "Neighbour cells (strongest first):\n" + neighbors.take(6).joinToString("\n") {
                "  ${it.radio}  PCI ${it.pci ?: "?"}  ch ${it.channel ?: "?"}  ${it.dbm ?: "?"} dBm"
            }

        // Record a sample for the user's own tower estimate (only with a good, fresh GPS fix)
        val loc = myLoc
        if (loc != null && cell.dbm != null && loc.hasAccuracy() && loc.accuracy <= 30f &&
            System.currentTimeMillis() - loc.time < 10_000
        ) {
            estimator.add(cell.siteKey, loc.latitude, loc.longitude, cell.dbm)
        }

        if (changed) {
            dbTower = null
            lookupMsg = null
            mapFitted = false
            if (!prefs.getString("api_key", null).isNullOrBlank()) doLookup()
        }
        updateGuidance()
        updateMap()
    }

    private fun describe(c: CellData): String {
        val sb = StringBuilder()
        sb.append("${c.radio}  •  ${c.operator ?: "Unknown operator"}")
        if (registered.size > 1) sb.append("  (SIM ${simIndex + 1} of ${registered.size})")
        sb.append("\nMCC-MNC ${c.mcc}-${c.mnc}   ${if (c.radio == "LTE" || c.radio == "NR") "TAC" else "LAC"} ${c.area}")
        sb.append("\nCell ID ${c.cid}")
        if (c.enb != null) sb.append("   (eNB ${c.enb}, sector ${c.sector})")
        sb.append("\nPCI ${c.pci ?: "?"}   Channel ${c.channel ?: "?"}")
        return sb.toString()
    }

    // ------------------------------------------------------------------ lookup

    private fun doLookup() {
        val cell = serving ?: run {
            Toast.makeText(this, "No serving cell yet", Toast.LENGTH_SHORT).show(); return
        }
        val key = prefs.getString("api_key", null)
        if (key.isNullOrBlank()) { askApiKey(); return }

        lookupMsg = "Looking up this cell in OpenCelliD…"
        updateGuidance()
        val requested = cell.key
        lifecycleScope.launch {
            val r = lookup.lookup(cell, key)
            if (serving?.key != requested) return@launch   // cell changed meanwhile
            when (r) {
                is LookupOutcome.Found -> {
                    dbTower = r.tower; lookupMsg = null; useEstimate = false; mapFitted = false
                }
                is LookupOutcome.NotFound -> {
                    dbTower = null; lookupMsg = getString(R.string.not_found)
                }
                is LookupOutcome.Failed -> {
                    dbTower = null; lookupMsg = "Lookup failed: ${r.message}"
                }
            }
            updateGuidance()
            updateMap()
        }
    }

    private fun askApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "pk.xxxxxxxxxxxxxxxx"
            setText(prefs.getString("api_key", ""))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("OpenCelliD API key")
            .setMessage(getString(R.string.key_help))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("api_key", input.text.toString().trim()).apply()
                serving?.let { doLookup() }
            }
            .setNeutralButton("Get free key") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencellid.org/")))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ------------------------------------------------------------------ guidance

    private data class Target(val label: String, val point: GeoPoint)

    private fun currentTarget(): Target? {
        val cell = serving ?: return null
        val est = estimator.estimate(cell.siteKey)
        val db = dbTower
        val estT = est?.let {
            Target("Your signal estimate (${it.points} points, best ${it.bestDbm} dBm)", GeoPoint(it.lat, it.lon))
        }
        val dbT = db?.let {
            Target("OpenCelliD (±${it.rangeM ?: "?"} m, ${it.samples ?: "?"} samples)", GeoPoint(it.lat, it.lon))
        }
        return if (useEstimate) estT ?: dbT else dbT ?: estT
    }

    private fun toggleTarget() {
        val cell = serving ?: return
        if (estimator.estimate(cell.siteKey) == null) {
            val n = estimator.count(cell.siteKey)
            Toast.makeText(this,
                "Own estimate not ready: $n/${SignalEstimator.MIN_POINTS} points. Walk around (50 m+) with GPS on.",
                Toast.LENGTH_LONG).show()
            return
        }
        useEstimate = !useEstimate
        mapFitted = false
        updateGuidance(); updateMap()
    }

    private fun updateGuidance() {
        val loc = myLoc
        val t = currentTarget()
        val cell = serving
        val samples = cell?.let { estimator.count(it.siteKey) } ?: 0
        val extra = buildString {
            lookupMsg?.let { append("\n").append(it) }
            if (cell != null) append("\nOwn-estimate points for this tower: $samples")
        }

        if (t == null) {
            tvTarget.text = "No tower position yet." + extra
            tvDistance.text = "—"
            arrow.alpha = 0.2f
            return
        }
        tvTarget.text = "Target: ${t.label}$extra"
        if (loc == null) {
            tvDistance.text = "Waiting for GPS fix… (go outdoors)"
            arrow.alpha = 0.2f
            return
        }
        val d = GeoMath.distance(loc.latitude, loc.longitude, t.point.latitude, t.point.longitude)
        val b = GeoMath.bearing(loc.latitude, loc.longitude, t.point.latitude, t.point.longitude)
        tvDistance.text = "${GeoMath.formatDistance(d)}   •   ${b.roundToInt()}° ${GeoMath.cardinal(b)}"

        if (heading.isNaN()) {
            arrow.alpha = 0.2f
            if (compass.available) tvHeading.text = "Starting compass…"
        } else {
            arrow.alpha = 1f
            arrow.rotation = ((b - heading) % 360f + 360f) % 360f
            tvHeading.text = String.format(Locale.US, "Phone facing %d° %s%s",
                heading.roundToInt(), GeoMath.cardinal(heading),
                if (compassNeedsCal) "\n⚠ Compass inaccurate: wave phone in a figure-8" else "")
        }
    }

    // ------------------------------------------------------------------ map

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(20.59, 78.96))   // India until GPS arrives
        // Let the map get gestures inside the ScrollView
        map.setOnTouchListener { v, ev ->
            v.parent.requestDisallowInterceptTouchEvent(ev.action != MotionEvent.ACTION_UP)
            false
        }
    }

    private fun updateMap() {
        val ov = map.overlays
        ov.clear()
        val pts = ArrayList<GeoPoint>()

        dbTower?.let { t ->
            val p = GeoPoint(t.lat, t.lon)
            t.rangeM?.let { r ->
                rangeCircle.points = Polygon.pointsAsCircle(p, r.toDouble())
                ov.add(rangeCircle)
            }
            dbMarker.position = p
            ov.add(dbMarker)
        }
        serving?.let { estimator.estimate(it.siteKey) }?.let { e ->
            estMarker.position = GeoPoint(e.lat, e.lon)
            ov.add(estMarker)
        }
        val loc = myLoc
        val target = currentTarget()
        if (loc != null) {
            val me = GeoPoint(loc.latitude, loc.longitude)
            if (target != null) {
                line.setPoints(listOf(me, target.point))
                ov.add(line)
                pts.add(target.point)
            }
            meMarker.position = me
            ov.add(meMarker)
            pts.add(me)
        } else if (target != null) {
            pts.add(target.point)
        }

        if (!mapFitted && pts.isNotEmpty()) {
            map.post {
                if (pts.size >= 2) {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.5f), false)
                } else {
                    map.controller.setZoom(16.0)
                    map.controller.setCenter(pts[0])
                }
            }
            mapFitted = pts.size >= 2
        }
        map.invalidate()
    }

    private fun openDirections() {
        val t = currentTarget() ?: run {
            Toast.makeText(this, "No tower position yet", Toast.LENGTH_SHORT).show(); return
        }
        val url = String.format(Locale.US,
            "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=walking",
            t.point.latitude, t.point.longitude)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
