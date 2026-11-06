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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private enum class Mode(val label: String) {
        BEST("Target: BEST (fused)"),
        DATABASE("Target: OpenCelliD only"),
        MEASURED("Target: My measurements only")
    }

    private enum class HeadingMode(val label: String) {
        AUTO("Heading: AUTO"),
        COMPASS("Heading: COMPASS"),
        GPS("Heading: GPS travel")
    }

    // ---- services & helpers
    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private lateinit var compass: Compass
    private lateinit var lookup: TowerLookup
    private lateinit var store: MeasurementStore
    private val hunt = HuntSound()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // ---- views
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvTrend: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvNeighbors: TextView
    private lateinit var arrow: ImageView
    private lateinit var map: MapView
    private lateinit var btnSim: Button
    private lateinit var btnMode: Button
    private lateinit var btnHunt: Button
    private lateinit var btnHeading: Button
    private lateinit var tvTurn: TextView

    // ---- state
    private var registered: List<CellData> = emptyList()
    private var simIndex = 0
    private var serving: CellData? = null
    private var dbTower: TowerResult? = null
    private var lookupMsg: String? = null
    private var myLoc: Location? = null
    private var heading = Float.NaN
    private var compassNeedsCal = false
    private var mode = Mode.BEST
    private var headingMode = HeadingMode.AUTO
    private var validTaSeen = false     // Samsung-style modems report a fixed TA=0 until real TA appears
    private var mapFitted = false
    private var sensing = false

    private var fused: Solution? = null
    private var measured: Solution? = null
    private var solving = false
    private var solvedFor = ""          // "siteKey|count|db" of the last solve
    private var lastSolveAt = 0L

    private val recent = ArrayDeque<Pair<Long, Int>>()   // (time, dBm) for trend

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshCells()
            handler.postDelayed(this, if (hunt.running) 1000L else 2000L)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSensing()
            else tvStatus.text = getString(R.string.need_permission)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportTo(uri)
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
    private fun marker(title: String, iconRes: Int, bottom: Boolean) = Marker(map).apply {
        this.title = title
        this.icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
        setAnchor(Marker.ANCHOR_CENTER, if (bottom) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
    }
    private fun circle(color: Int, fill: Int) = Polygon(map).apply {
        fillPaint.color = fill
        outlinePaint.color = color
        outlinePaint.strokeWidth = 3f
    }
    private val meMarker by lazy { marker("You", R.drawable.ic_me, false) }
    private val dbMarker by lazy { marker("Tower – OpenCelliD", R.drawable.ic_tower, true) }
    private val fitMarker by lazy { marker("Tower – best estimate", R.drawable.ic_tower_est, true) }
    private val dbCircle by lazy { circle(0x991E88E5.toInt(), 0x161E88E5) }
    private val fitCircle by lazy { circle(0xCC43A047.toInt(), 0x2243A047) }
    private val taRing by lazy { circle(0xCCFB8C00.toInt(), 0x00000000) }
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
        tvTrend = findViewById(R.id.tvTrend)
        tvTarget = findViewById(R.id.tvTarget)
        tvDistance = findViewById(R.id.tvDistance)
        tvHeading = findViewById(R.id.tvHeading)
        tvNeighbors = findViewById(R.id.tvNeighbors)
        arrow = findViewById(R.id.arrow)
        map = findViewById(R.id.map)
        btnSim = findViewById(R.id.btnSim)
        btnMode = findViewById(R.id.btnMode)
        btnHunt = findViewById(R.id.btnHunt)
        btnHeading = findViewById(R.id.btnHeading)
        tvTurn = findViewById(R.id.tvTurn)
        btnHeading.text = headingMode.label
        btnHeading.setOnClickListener {
            headingMode = HeadingMode.values()[(headingMode.ordinal + 1) % HeadingMode.values().size]
            btnHeading.text = headingMode.label
            updateGuidance()
        }

        tm = getSystemService(TelephonyManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        lookup = TowerLookup(this)
        store = MeasurementStore(File(filesDir, "measurements.json"))
        compass = Compass(this) { deg, needsCal ->
            heading = deg
            compassNeedsCal = needsCal
            updateGuidance()
        }

        setupMap()

        findViewById<Button>(R.id.btnLookup).setOnClickListener { doLookup() }
        findViewById<Button>(R.id.btnMaps).setOnClickListener { openDirections() }
        findViewById<Button>(R.id.btnKey).setOnClickListener { askApiKey() }
        val btnExport = findViewById<Button>(R.id.btnExport)
        btnExport.setOnClickListener { exportLauncher.launch("cell_measurements.csv") }
        btnExport.setOnLongClickListener { confirmClear(); true }
        btnMode.text = mode.label
        btnMode.setOnClickListener {
            mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
            btnMode.text = mode.label
            mapFitted = false
            updateGuidance(); updateMap()
        }
        btnHunt.setOnClickListener { toggleHunt() }
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
        if (hunt.running) toggleHunt()
        saveAsync()
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
                lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
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
                @SuppressLint("MissingPermission")
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
            tvTrend.text = ""
            tvNeighbors.text = ""
            hunt.dbm = null
            return
        }
        if (simIndex >= registered.size) simIndex = 0
        val cell = registered[simIndex]
        val changed = cell.key != serving?.key
        val siteChanged = cell.siteKey != serving?.siteKey
        serving = cell

        tvNetwork.text = describe(cell)
        tvSignal.text = cell.dbm?.let { "$it dBm  •  ${CellReader.quality(cell)}" } ?: "Signal: n/a"
        hunt.dbm = cell.dbm
        updateTrend(cell.dbm, siteChanged)
        tvNeighbors.text = if (neighbors.isEmpty()) "Neighbour cells: none reported" else
            "Neighbour cells (strongest first):\n" + neighbors.take(6).joinToString("\n") {
                "  ${it.radio}  PCI ${it.pci ?: "?"}  ch ${it.channel ?: "?"}  ${it.dbm ?: "?"} dBm"
            }

        recordSample(cell)

        if (siteChanged) {
            fused = null; measured = null; solvedFor = ""
        }
        if (changed) {
            dbTower = null
            lookupMsg = null
            mapFitted = false
            if (!prefs.getString("api_key", null).isNullOrBlank()) doLookup()
        }
        maybeSolve()
        updateGuidance()
        updateMap()
    }

    /** Save a measurement only with a good, fresh GPS fix. */
    private fun recordSample(cell: CellData) {
        val loc = myLoc ?: return
        val dbm = cell.dbm ?: return
        if (!loc.hasAccuracy() || loc.accuracy > 25f) return
        if (System.currentTimeMillis() - loc.time > 8_000) return
        // NetMonster's rule: ignore TA until a value > 0 has been seen (fixed-0 modems)
        if ((cell.taRaw ?: 0) > 0) validTaSeen = true
        val ta = cell.taM?.takeIf { validTaSeen }
        if (store.add(cell.siteKey, Sample(loc.latitude, loc.longitude, dbm, ta, System.currentTimeMillis()))) {
            if (store.count(cell.siteKey) % 25 == 0) saveAsync()
        }
    }

    private fun saveAsync() {
        if (!store.needsSave()) return
        lifecycleScope.launch(Dispatchers.IO) { store.save() }
    }

    private fun updateTrend(dbm: Int?, reset: Boolean) {
        if (reset) recent.clear()
        val now = System.currentTimeMillis()
        if (dbm != null) recent.addLast(now to dbm)
        while (recent.isNotEmpty() && now - recent.first().first > 12_000) recent.removeFirst()
        val newer = recent.filter { now - it.first <= 6_000 }.map { it.second }
        val older = recent.filter { now - it.first > 6_000 }.map { it.second }
        if (newer.isEmpty() || older.isEmpty()) { tvTrend.text = ""; return }
        val delta = newer.average() - older.average()
        tvTrend.text = when {
            delta >= 2 -> String.format(Locale.US, "▲ Getting stronger (+%.0f dB) – keep going", delta)
            delta <= -2 -> String.format(Locale.US, "▼ Getting weaker (%.0f dB) – turn around", delta)
            else -> "■ Steady"
        }
    }

    private fun describe(c: CellData): String {
        val sb = StringBuilder()
        sb.append("${c.radio}  •  ${c.operator ?: "Unknown operator"}")
        if (registered.size > 1) sb.append("  (SIM ${simIndex + 1} of ${registered.size})")
        sb.append("\nMCC-MNC ${c.mcc}-${c.mnc}   ${if (c.radio == "LTE" || c.radio == "NR") "TAC" else "LAC"} ${c.area}")
        sb.append("\nCell ID ${c.cid}")
        if (c.enb != null) sb.append("   (eNB ${c.enb}, sector ${c.sector})")
        sb.append("\nPCI ${c.pci ?: "?"}   Channel ${c.channel ?: "?"}")
        sb.append("\nTiming Advance: ")
        if (c.taM != null && validTaSeen) {
            val step = if (c.radio == "GSM") CellReader.GSM_TA_M else CellReader.LTE_TA_M
            sb.append("${c.taRaw} → tower ≈ ${c.taM} m away (±${(step / 2).roundToInt()} m)")
        } else sb.append("n/a (play a video or run a download to get it)")
        return sb.toString()
    }

    // ------------------------------------------------------------------ solving

    private fun maybeSolve() {
        val cell = serving ?: return
        val site = cell.siteKey
        val db = dbTower
        val tag = "$site|${store.count(site)}|${db?.lat}"
        val now = System.currentTimeMillis()
        if (solving || tag == solvedFor || now - lastSolveAt < 3_000) return
        solving = true
        lastSolveAt = now
        val samples = store.snapshot(site)
        val prior = db?.let {
            Prior(it.lat, it.lon, ((it.rangeM ?: 1000) / 2.0).coerceIn(150.0, 2000.0))
        }
        lifecycleScope.launch {
            val (f, m) = withContext(Dispatchers.Default) {
                val meas = TowerSolver.solve(samples, null)
                val fus = if (prior != null) TowerSolver.solve(samples, prior) else meas
                fus to meas
            }
            solving = false
            if (serving?.siteKey != site) return@launch
            fused = f; measured = m; solvedFor = tag
            updateGuidance(); updateMap()
        }
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
            if (serving?.key != requested) return@launch
            when (r) {
                is LookupOutcome.Found -> { dbTower = r.tower; lookupMsg = null; mapFitted = false }
                is LookupOutcome.NotFound -> { dbTower = null; lookupMsg = getString(R.string.not_found) }
                is LookupOutcome.Failed -> { dbTower = null; lookupMsg = "Lookup failed: ${r.message}" }
            }
            solvedFor = ""
            maybeSolve()
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

    // ------------------------------------------------------------------ tools

    private fun toggleHunt() {
        if (hunt.running) {
            hunt.stop(); btnHunt.text = "Hunt sound: OFF"
        } else {
            hunt.start(); btnHunt.text = "Hunt sound: ON"
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { store.exportCsv(it) } != null
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity,
                    if (ok) "Exported measurements" else "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClear() {
        val cell = serving ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear measurements?")
            .setMessage("Delete the ${store.count(cell.siteKey)} saved points for this tower and start fresh?")
            .setPositiveButton("Clear") { _, _ ->
                store.clear(cell.siteKey); saveAsync()
                fused = null; measured = null; solvedFor = ""
                updateGuidance(); updateMap()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ guidance

    private data class Target(val label: String, val point: GeoPoint, val errM: Double?)

    private fun dbTarget() = dbTower?.let {
        Target("OpenCelliD database (coverage ±${it.rangeM ?: "?"} m, ${it.samples ?: "?"} samples)",
            GeoPoint(it.lat, it.lon), it.rangeM?.toDouble())
    }

    private fun solTarget(s: Solution?, name: String) = s?.let {
        val parts = ArrayList<String>()
        if (it.usedSignal) parts.add("${it.points} signal pts")
        if (it.taPoints > 0) parts.add("${it.taPoints} TA")
        if (it.usedPrior) parts.add("database")
        Target(String.format(Locale.US, "%s ±%d m (%s, n=%.1f)",
            name, it.errorM.roundToInt(), parts.joinToString(" + "), it.pathLossExp),
            GeoPoint(it.lat, it.lon), it.errorM)
    }

    private fun currentTarget(): Target? = when (mode) {
        Mode.BEST -> {
            // Only let our own fit override the database once it is genuinely confident
            val f = fused
            val dbRange = dbTower?.rangeM?.toDouble()
            val reliable = f != null && f.errorM <= 400.0 && (dbRange == null || f.errorM < dbRange)
            if (reliable) solTarget(f, "Best fit")
            else dbTarget() ?: solTarget(f, "Measured fit (LOW confidence)")
        }
        Mode.DATABASE -> dbTarget()
        Mode.MEASURED -> solTarget(measured, "Measured fit")
    }

    private fun updateGuidance() {
        val loc = myLoc
        val t = currentTarget()
        val cell = serving
        val extra = buildString {
            lookupMsg?.let { append("\n").append(it) }
            if (cell != null) {
                val n = store.count(cell.siteKey)
                val ta = store.taCount(cell.siteKey)
                append("\nSaved points for this tower: $n ($ta with TA)")
                if (measured == null && n < TowerSolver.MIN_SIGNAL_POINTS && ta < 3)
                    append(" – walk around to build your own fix")
                val db = dbTower; val m = measured
                if (db != null && m != null) {
                    val gap = GeoMath.distance(db.lat, db.lon, m.lat, m.lon)
                    append("\nDatabase vs your measurements differ by ${GeoMath.formatDistance(gap)}")
                }
            }
        }

        if (t == null) {
            tvTarget.text = (if (mode == Mode.BEST) "No tower position yet." else "No position for this target mode yet.") + extra
            tvDistance.text = "—"
            tvTurn.text = ""
            arrow.alpha = 0.2f
            return
        }
        tvTarget.text = "${t.label}$extra"
        if (loc == null) {
            tvDistance.text = "Waiting for GPS fix… (go outdoors)"
            tvTurn.text = ""
            arrow.alpha = 0.2f
            return
        }
        val d = GeoMath.distance(loc.latitude, loc.longitude, t.point.latitude, t.point.longitude)
        val b = GeoMath.bearing(loc.latitude, loc.longitude, t.point.latitude, t.point.longitude)
        tvDistance.text = "${GeoMath.formatDistance(d)}   •   ${b.roundToInt()}° ${GeoMath.cardinal(b)}"

        val h = effectiveHeading(loc)
        if (h == null) {
            arrow.alpha = 0.2f
            tvTurn.text = ""
            tvHeading.text = when {
                headingMode == HeadingMode.GPS -> "GPS heading needs movement above 9 km/h. Use the bearing and map line."
                compass.available -> "Starting compass…"
                else -> getString(R.string.no_compass)
            }
        } else {
            val (deg, source) = h
            val inside = t.errM != null && d < t.errM
            arrow.alpha = if (inside) 0.45f else 1f
            arrow.rotation = ((b - deg) % 360f + 360f) % 360f
            val rel = ((b - deg + 540f) % 360f) - 180f       // −180..180, + = right
            val a = kotlin.math.abs(rel).roundToInt()
            tvTurn.text = when {
                inside -> "You are inside the uncertainty zone – look up for the mast"
                a <= 15 -> "STRAIGHT AHEAD"
                a >= 165 -> "BEHIND YOU – turn around"
                rel > 0 -> "$a° to your RIGHT"
                else -> "$a° to your LEFT"
            }
            tvHeading.text = String.format(Locale.US, "Heading %d° %s from %s%s",
                deg.roundToInt(), GeoMath.cardinal(deg), source,
                if (source == "compass" && compassNeedsCal) "\n⚠ Compass inaccurate: wave phone in a figure-8, away from metal" else "")
        }
    }

    /** Compass when walking; GPS course when moving fast (vehicles distort the compass). */
    private fun effectiveHeading(loc: Location): Pair<Float, String>? {
        val moving = loc.hasSpeed() && loc.speed >= 2.5f && loc.hasBearing() &&
            System.currentTimeMillis() - loc.time < 4_000
        val gps = if (moving) loc.bearing to "GPS travel direction" else null
        val comp = if (!heading.isNaN()) heading to "compass" else null
        return when (headingMode) {
            HeadingMode.GPS -> gps
            HeadingMode.COMPASS -> comp
            HeadingMode.AUTO -> gps ?: comp
        }
    }

    // ------------------------------------------------------------------ map

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(20.59, 78.96))
        map.setOnTouchListener { v, ev ->
            v.parent.requestDisallowInterceptTouchEvent(ev.action != MotionEvent.ACTION_UP)
            false
        }
    }

    private fun updateMap() {
        val ov = map.overlays
        ov.clear()
        val pts = ArrayList<GeoPoint>()
        val loc = myLoc

        dbTower?.let { t ->
            val p = GeoPoint(t.lat, t.lon)
            t.rangeM?.let { r ->
                dbCircle.points = Polygon.pointsAsCircle(p, r.toDouble()); ov.add(dbCircle)
            }
            dbMarker.position = p; ov.add(dbMarker)
        }
        val sol = if (mode == Mode.MEASURED) measured else (fused ?: measured)
        if (mode != Mode.DATABASE) sol?.let { s ->
            val p = GeoPoint(s.lat, s.lon)
            fitCircle.points = Polygon.pointsAsCircle(p, s.errorM.coerceAtLeast(10.0)); ov.add(fitCircle)
            fitMarker.position = p; ov.add(fitMarker)
        }
        val ta = serving?.taM?.takeIf { validTaSeen }
        if (loc != null && ta != null) {
            taRing.points = Polygon.pointsAsCircle(GeoPoint(loc.latitude, loc.longitude), ta.toDouble())
            ov.add(taRing)
        }
        val target = currentTarget()
        if (loc != null) {
            val me = GeoPoint(loc.latitude, loc.longitude)
            if (target != null) {
                line.setPoints(listOf(me, target.point)); ov.add(line); pts.add(target.point)
            }
            meMarker.position = me; ov.add(meMarker); pts.add(me)
        } else if (target != null) {
            pts.add(target.point)
        }

        if (!mapFitted && pts.isNotEmpty()) {
            map.post {
                if (pts.size >= 2) {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.5f), false)
                } else {
                    map.controller.setZoom(16.0); map.controller.setCenter(pts[0])
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
