package com.celltower.locator

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * "Geiger counter" for signal: beeps faster as the signal gets stronger,
 * so you can walk toward the tower without looking at the screen.
 */
class HuntSound {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    @Volatile var dbm: Int? = null
    var running = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val d = dbm
            if (d != null) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
            // -120 dBm → 1.6 s between beeps,  -60 dBm → 0.1 s
            val clamped = (d ?: -130).coerceIn(-120, -60)
            val interval = 1600 - (clamped + 120) * 25
            handler.postDelayed(this, interval.toLong())
        }
    }

    fun start() {
        if (running) return
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 85) } catch (_: Exception) { null }
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        tone?.release()
        tone = null
    }
}
