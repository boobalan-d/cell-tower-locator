package com.celltower.locator

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TowerResult(val lat: Double, val lon: Double, val rangeM: Int?, val samples: Int?)

sealed class LookupOutcome {
    data class Found(val tower: TowerResult) : LookupOutcome()
    data class NotFound(val message: String) : LookupOutcome()
    data class Failed(val message: String) : LookupOutcome()
}

/**
 * Looks up a cell's estimated position in OpenCelliD (free, open database).
 * Results are cached on the phone so the same cell never uses your daily quota twice.
 */
class TowerLookup(context: Context) {

    private val cache = context.getSharedPreferences("tower_cache", Context.MODE_PRIVATE)

    fun clearCache() = cache.edit().clear().apply()

    suspend fun lookup(cell: CellData, apiKey: String): LookupOutcome = withContext(Dispatchers.IO) {
        cache.getString(cell.key, null)?.let { decode(it) }?.let {
            return@withContext LookupOutcome.Found(it)
        }

        val mnc = cell.mnc?.toIntOrNull()?.toString() ?: cell.mnc
        val url = Uri.parse("https://opencellid.org/cell/get").buildUpon()
            .appendQueryParameter("key", apiKey.trim())
            .appendQueryParameter("mcc", cell.mcc)
            .appendQueryParameter("mnc", mnc)
            .appendQueryParameter("lac", cell.area.toString())
            .appendQueryParameter("cellid", cell.cid.toString())
            .appendQueryParameter("radio", cell.radio)
            .appendQueryParameter("format", "json")
            .build().toString()

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = try { JSONObject(body) } catch (e: Exception) {
                return@withContext LookupOutcome.Failed("Unexpected reply (HTTP $code). Check your API key.")
            }
            if (json.has("lat") && json.has("lon")) {
                val t = TowerResult(
                    json.getDouble("lat"), json.getDouble("lon"),
                    json.optInt("range", -1).takeIf { it > 0 },
                    json.optInt("samples", -1).takeIf { it >= 0 }
                )
                cache.edit().putString(cell.key, encode(t)).apply()
                LookupOutcome.Found(t)
            } else {
                val err = json.optString("error", "Cell not found")
                if (err.contains("not found", ignoreCase = true)) LookupOutcome.NotFound(err)
                else LookupOutcome.Failed(err)
            }
        } catch (e: Exception) {
            LookupOutcome.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun encode(t: TowerResult) = "${t.lat},${t.lon},${t.rangeM ?: -1},${t.samples ?: -1}"

    private fun decode(s: String): TowerResult? {
        val p = s.split(",")
        if (p.size != 4) return null
        return TowerResult(
            p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null,
            p[2].toIntOrNull()?.takeIf { it > 0 }, p[3].toIntOrNull()?.takeIf { it >= 0 }
        )
    }
}
