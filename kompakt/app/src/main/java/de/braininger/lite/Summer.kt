package de.braininger.lite

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Rückmeldung per Vibration, damit man ohne Hinsehen weiß, was passiert ist (Taste bei dunklem Bildschirm).
 * Als „Wecker“ gekennzeichnet: Android lässt sonst Vibrationen von Apps im Hintergrund weg.
 */
object Summer {
    private val wecker = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

    private fun muster(c: Context, vararg ms: Long) {
        try {
            c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, *ms), -1), wecker)
        } catch (_: Exception) {}
    }

    /** Aufnahme läuft: einmal lang. */
    fun start(c: Context) = muster(c, 450)

    /** Aufnahme beendet: zweimal kurz. */
    fun stopp(c: Context) = muster(c, 120, 140, 120)

    /** Mikrofon stumm: dreimal lang. */
    fun warnung(c: Context) = muster(c, 500, 200, 500, 200, 500)
}
