package com.celltower.locator

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer

/**
 * Keeps every measurement per tower site and saves it to the phone,
 * so your accuracy keeps improving across days and app restarts.
 */
class MeasurementStore(private val file: File) {

    private val data = LinkedHashMap<String, ArrayList<Sample>>()
    private var dirty = 0

    init { load() }

    @Synchronized
    fun add(siteKey: String, s: Sample): Boolean {
        val list = data.getOrPut(siteKey) { ArrayList() }
        val last = list.lastOrNull()
        // Skip duplicates while standing still, unless TA appears for the first time here
        if (last != null && GeoMath.distance(last.lat, last.lon, s.lat, s.lon) < 8.0 &&
            !(s.taM != null && last.taM == null)) return false
        list.add(s)
        if (list.size > MAX_PER_SITE) list.removeAt(0)
        while (data.size > MAX_SITES) data.remove(data.keys.first())
        dirty++
        return true
    }

    @Synchronized fun snapshot(siteKey: String): List<Sample> = ArrayList(data[siteKey].orEmpty())
    @Synchronized fun count(siteKey: String): Int = data[siteKey]?.size ?: 0
    @Synchronized fun taCount(siteKey: String): Int = data[siteKey]?.count { it.taM != null } ?: 0
    @Synchronized fun clear(siteKey: String) { data.remove(siteKey); dirty++ }
    @Synchronized fun needsSave(): Boolean = dirty > 0

    @Synchronized
    fun save() {
        val root = JSONObject()
        for ((k, list) in data) {
            val arr = JSONArray()
            for (s in list) arr.put(JSONArray().put(s.lat).put(s.lon).put(s.dbm).put(s.taM ?: -1).put(s.time))
            root.put(k, arr)
        }
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
            dirty = 0
        } catch (_: Exception) { }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            for (k in root.keys()) {
                val arr = root.getJSONArray(k)
                val list = ArrayList<Sample>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list.add(Sample(a.getDouble(0), a.getDouble(1), a.getInt(2),
                        a.getInt(3).takeIf { it >= 0 }, a.getLong(4)))
                }
                data[k] = list
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    fun exportCsv(w: Writer) {
        w.write("site,lat,lon,dbm,ta_distance_m,time_ms\n")
        for ((k, list) in data) for (s in list) {
            w.write("$k,${s.lat},${s.lon},${s.dbm},${s.taM ?: ""},${s.time}\n")
        }
    }

    companion object {
        const val MAX_PER_SITE = 800
        const val MAX_SITES = 300
    }
}
