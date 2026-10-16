package de.braininger.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Essential Key (Nothing Phone) als Aufnahme-Taste, über eine Bedienungshilfe, die nur Tastendrücke sieht.
 * Die Taste kommt als keyCode 0 mit scanCode 250 an (so dokumentiert es github.com/KoukeNeko/EssentialKeyTools).
 * Den einfachen Druck behält Nothing OS. Doppelt drücken: Gedanke starten oder stoppen. Lange drücken: markieren.
 *
 * Probe-Stand: jeder Tastendruck landet in files/tasten.log, damit sich am Handy prüfen lässt, was ankommt.
 */
class TastenDienst : AccessibilityService() {

    companion object {
        private const val ESSENTIAL_SCAN = 250
        private const val DOPPEL_MS = 450L
        private const val LANG_MS = 700L
    }

    private val haupt = Handler(Looper.getMainLooper())
    private var unten = 0L
    private var letztesLoslassen = 0L
    private var langAusgeloest = false
    private val langPruefen = Runnable {
        langAusgeloest = true
        protokoll("lang")
        if (AufnahmeStatus.zustand.value.laeuft) {
            AufnahmeDienst.markieren(this)
        } else {
            vibrieren(longArrayOf(0, 40, 80, 40))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        protokoll("taste code=${event.keyCode} scan=${event.scanCode} aktion=${event.action} wiederholt=${event.repeatCount}")
        if (event.scanCode != ESSENTIAL_SCAN) return false
        val jetzt = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                unten = jetzt
                langAusgeloest = false
                haupt.postDelayed(langPruefen, LANG_MS)
            }
            KeyEvent.ACTION_UP -> {
                haupt.removeCallbacks(langPruefen)
                if (!langAusgeloest) {
                    if (jetzt - letztesLoslassen < DOPPEL_MS) {
                        letztesLoslassen = 0L
                        doppelt()
                    } else {
                        letztesLoslassen = jetzt
                    }
                }
            }
        }
        return false // Nothing behält seine eigenen Aktionen
    }

    private fun doppelt() {
        val laeuft = AufnahmeStatus.zustand.value.laeuft
        protokoll("doppelt, Aufnahme läuft: $laeuft")
        vibrieren(if (laeuft) longArrayOf(0, 300) else longArrayOf(0, 80, 100, 80))
        // Die Aufnahme muss aus einer sichtbaren Seite starten (Android 14+), wie beim Widget
        startActivity(
            Intent(this, StartActivity::class.java)
                .putExtra(StartActivity.EXTRA_WAS, if (laeuft) StartActivity.WAS_STOPP else StartActivity.WAS_GEDANKE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun vibrieren(muster: LongArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(muster, -1))
        } catch (_: Exception) {
        }
    }

    private fun protokoll(text: String) {
        Log.i("BrainingerTaste", text)
        try {
            val f = File(filesDir, "tasten.log")
            if (f.length() > 200_000) f.delete()
            f.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + text + "\n")
        } catch (_: Exception) {
        }
    }
}
