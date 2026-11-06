package com.celltower.locator

import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr

/** One cell as seen by the phone's modem. */
data class CellData(
    val radio: String,          // "GSM", "UMTS", "LTE", "NR" (OpenCelliD naming)
    val mcc: String?,
    val mnc: String?,
    val area: Int?,             // LAC (2G/3G) or TAC (4G/5G)
    val cid: Long?,             // Cell ID (LTE = 28-bit ECI, NR = 36-bit NCI)
    val pci: Int?,              // PCI / PSC / BSIC
    val channel: Int?,          // ARFCN / UARFCN / EARFCN / NR-ARFCN
    val dbm: Int?,              // Signal strength (RSRP for LTE/NR)
    val registered: Boolean,    // true = this is the serving cell of a SIM
    val operator: String?
) {
    val isComplete: Boolean get() = mcc != null && mnc != null && area != null && cid != null

    /** Unique key for this exact cell (sector). */
    val key: String get() = "$radio-$mcc-$mnc-$area-$cid"

    /** LTE: eNodeB ID = ECI / 256. All sectors of one eNB are on the same tower. */
    val enb: Long? get() = if (radio == "LTE" && cid != null) cid shr 8 else null
    val sector: Long? get() = if (radio == "LTE" && cid != null) cid and 0xFF else null

    /** Key for the physical site (tower). For LTE, all sectors share one key. */
    val siteKey: String get() = if (enb != null) "LTE-$mcc-$mnc-eNB$enb" else key
}

object CellReader {

    private fun ok(v: Int): Boolean = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE && v != Int.MIN_VALUE
    private fun okL(v: Long): Boolean = v != CellInfo.UNAVAILABLE_LONG && v != Long.MAX_VALUE

    fun parse(info: CellInfo): CellData? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellData(
                "LTE", id.mccString, id.mncString,
                id.tac.takeIf { ok(it) }, id.ci.takeIf { ok(it) }?.toLong(),
                id.pci.takeIf { ok(it) }, id.earfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString()
            )
        }
        is CellInfoNr -> {
            val id = info.cellIdentity as CellIdentityNr
            val s = info.cellSignalStrength as CellSignalStrengthNr
            CellData(
                "NR", id.mccString, id.mncString,
                id.tac.takeIf { ok(it) }, id.nci.takeIf { okL(it) },
                id.pci.takeIf { ok(it) }, id.nrarfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString()
            )
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellData(
                "UMTS", id.mccString, id.mncString,
                id.lac.takeIf { ok(it) }, id.cid.takeIf { ok(it) }?.toLong(),
                id.psc.takeIf { ok(it) }, id.uarfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString()
            )
        }
        is CellInfoGsm -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellData(
                "GSM", id.mccString, id.mncString,
                id.lac.takeIf { ok(it) }, id.cid.takeIf { ok(it) }?.toLong(),
                id.bsic.takeIf { ok(it) }, id.arfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString()
            )
        }
        else -> null
    }

    /** Rough signal quality label. Thresholds are for RSRP on 4G/5G, RSSI on 2G/3G. */
    fun quality(cell: CellData): String {
        val d = cell.dbm ?: return "unknown"
        val t = if (cell.radio == "LTE" || cell.radio == "NR")
            intArrayOf(-80, -90, -100, -110) else intArrayOf(-70, -85, -100, -110)
        return when {
            d >= t[0] -> "Excellent"
            d >= t[1] -> "Good"
            d >= t[2] -> "Fair"
            d >= t[3] -> "Poor"
            else -> "Very poor"
        }
    }
}
