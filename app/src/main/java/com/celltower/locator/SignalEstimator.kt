package com.celltower.locator

import kotlin.math.pow

/**
 * Builds your OWN estimate of where a tower is, from GPS points + signal strength
 * collected while you move around. Stronger readings get much more weight
 * (weighted centroid). Works even when the tower is missing from OpenCelliD.
 *
 * Accuracy depends on HOW you walk: the estimate can only land inside the area you
 * covered, so circle around the suspected spot rather than walking a straight line.
 */
class SignalEstimator {

    data class Sample(val lat: Double, val lon: Double, val dbm: Int)
    data class Estimate(val lat: Double, val lon: Double, val points: Int, val spreadM: Double, val bestDbm: Int)

    private val data = HashMap<String, ArrayList<Sample>>()

    fun add(siteKey: String, lat: Double, lon: Double, dbm: Int) {
        val list = data.getOrPut(siteKey) { ArrayList() }
        val last = list.lastOrNull()
        // Ignore readings while standing still (prevents one spot dominating)
        if (last != null && GeoMath.distance(last.lat, last.lon, lat, lon) < 10.0) return
        list.add(Sample(lat, lon, dbm))
        if (list.size > 600) list.removeAt(0)
    }

    fun count(siteKey: String): Int = data[siteKey]?.size ?: 0

    fun estimate(siteKey: String): Estimate? {
        val list = data[siteKey] ?: return null
        if (list.size < MIN_POINTS) return null
        val best = list.maxOf { it.dbm }
        var sw = 0.0
        var sLat = 0.0
        var sLon = 0.0
        for (s in list) {
            val w = 10.0.pow((s.dbm - best) / 20.0)   // 20 dB weaker → 10x less weight
            sw += w; sLat += w * s.lat; sLon += w * s.lon
        }
        val lat = sLat / sw
        val lon = sLon / sw
        val spread = list.maxOf { GeoMath.distance(lat, lon, it.lat, it.lon) }
        if (spread < MIN_SPREAD_M) return null
        return Estimate(lat, lon, list.size, spread, best)
    }

    companion object {
        const val MIN_POINTS = 5
        const val MIN_SPREAD_M = 50.0
    }
}
