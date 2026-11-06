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
    val operator: String?,
    val taRaw: Int? = null,     // Timing Advance index reported by the modem
    val taM: Int? = null        // Distance to the tower implied by TA (centre of the TA bin), metres
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

    /** One LTE TA step = 16 Ts = 0.52 µs round trip → 78.12 m one-way. */
    const val LTE_TA_M = 78.12
    /** One GSM TA step ≈ 553.5 m. */
    const val GSM_TA_M = 553.5

    // Valid ranges as used by NetMonster Core (OEM modems often report junk outside them)
    private val LTE_CI = 1..268_435_454
    private val LTE_TAC = 1..65_534
    private val LTE_PCI = 0..503
    private val GSM_CID = 1..65_535
    private val LAC = 1..65_534
    private val UMTS_CID = 1..268_435_455
    private val NR_NCI = 1L..68_719_476_735L
    private val NR_PCI = 0..1007

    private fun ok(v: Int): Boolean = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE && v != Int.MIN_VALUE
    private fun okL(v: Long): Boolean = v != CellInfo.UNAVAILABLE_LONG && v != Long.MAX_VALUE

    fun parse(info: CellInfo): CellData? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellData(
                "LTE", id.mccString, id.mncString,
                id.tac.takeIf { it in LTE_TAC }, id.ci.takeIf { it in LTE_CI }?.toLong(),
                id.pci.takeIf { it in LTE_PCI }, id.earfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString(),
                s.timingAdvance.takeIf { it in 0..1282 },
                s.timingAdvance.takeIf { it in 0..1282 }?.let { ((it + 0.5) * LTE_TA_M).toInt() }
            )
        }
        is CellInfoNr -> {
            val id = info.cellIdentity as CellIdentityNr
            val s = info.cellSignalStrength as CellSignalStrengthNr
            CellData(
                "NR", id.mccString, id.mncString,
                id.tac.takeIf { ok(it) && it > 0 }, id.nci.takeIf { okL(it) && it in NR_NCI },
                id.pci.takeIf { it in NR_PCI }, id.nrarfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString()
            )
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            val s = info.cellSignalStrength
            CellData(
                "UMTS", id.mccString, id.mncString,
                id.lac.takeIf { it in LAC }, id.cid.takeIf { it in UMTS_CID }?.toLong(),
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
                id.lac.takeIf { it in LAC }, id.cid.takeIf { it in GSM_CID }?.toLong(),
                id.bsic.takeIf { ok(it) }, id.arfcn.takeIf { ok(it) },
                s.dbm.takeIf { ok(it) }, info.isRegistered,
                id.operatorAlphaLong?.toString(),
                s.timingAdvance.takeIf { it in 0..219 },
                s.timingAdvance.takeIf { it in 0..219 }?.let { ((it + 0.5) * GSM_TA_M).toInt() }
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
