package de.braininger.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Essential Key (Nothing Phone) als Aufnahme-Taste, über eine Bedienungshilfe, die nur Tastendrücke sieht.
 * Die Taste kommt als keyCode 0 mit scanCode 250 an (so dokumentiert es github.com/KoukeNeko/EssentialKeyTools).
 * Den einfachen Druck behält Nothing OS. Belegung (Grill 16.10.2026):
 * - doppelt: Gedanke starten / Aufnahme stoppen
 * - lang, während eine Aufnahme läuft: Stelle markieren
 * - lang, ohne Aufnahme: Funkgerät – gedrückt halten nimmt auf, loslassen beendet
 * Am 16.10. am Handy getestet: entsperrt, gesperrt und bei schwarzem Bildschirm.
 */
class TastenDienst : AccessibilityService() {

    companion object {
        private const val ESSENTIAL_SCAN = 250
        private const val DOPPEL_MS = 450L
        private const val LANG_MS = 700L
        private const val START_GEDULD_MS = 4000L
    }

    private val haupt = Handler(Looper.getMainLooper())
    private var letztesLoslassen = 0L
    private var langAusgeloest = false
    private var funk = false // Aufnahme läuft, solange die Taste gehalten wird
    private var stoppNachStart = false

    private val langPruefen = Runnable {
        langAusgeloest = true
        if (AufnahmeStatus.zustand.value.laeuft) {
            protokoll("lang: markieren")
            AufnahmeDienst.markieren(this) // vibriert selbst
        } else {
            protokoll("lang: Funkgerät-Aufnahme")
            funk = true
            stoppNachStart = false
            starten(longArrayOf(0, 120))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.scanCode != ESSENTIAL_SCAN) return false
        val jetzt = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                langAusgeloest = false
                haupt.postDelayed(langPruefen, LANG_MS)
            }
            KeyEvent.ACTION_UP -> {
                haupt.removeCallbacks(langPruefen)
                when {
                    funk -> {
                        funk = false
                        protokoll("losgelassen: Funkgerät-Aufnahme beenden")
                        if (AufnahmeStatus.zustand.value.laeuft) stoppen() else stoppNachStart = true
                    }
                    langAusgeloest -> {}
                    jetzt - letztesLoslassen < DOPPEL_MS -> {
                        letztesLoslassen = 0L
                        doppelt()
                    }
                    else -> letztesLoslassen = jetzt
                }
            }
        }
        return true // Ereignis schlucken, damit Nothing möglichst nicht zusätzlich Essential Space öffnet
    }

    private fun doppelt() {
        if (AufnahmeStatus.zustand.value.laeuft) {
            protokoll("doppelt: stoppen")
            stoppen()
        } else {
            protokoll("doppelt: Gedanke starten")
            starten(longArrayOf(0, 80, 100, 80))
        }
    }

    /** Startet einen Gedanken und brummt erst, wenn wirklich aufgenommen wird (dann kann man lossprechen). */
    private fun starten(muster: LongArray) {
        seite(StartActivity.WAS_GEDANKE)
        val bis = SystemClock.uptimeMillis() + START_GEDULD_MS
        val warten = object : Runnable {
            override fun run() {
                when {
                    AufnahmeStatus.zustand.value.laeuft -> {
                        vibrieren(muster)
                        if (stoppNachStart) {
                            stoppNachStart = false
                            stoppen()
                        }
                    }
                    SystemClock.uptimeMillis() < bis -> haupt.postDelayed(this, 100)
                    else -> {
                        protokoll("Aufnahme startete nicht")
                        vibrieren(longArrayOf(0, 60, 60, 60, 60, 60))
                    }
                }
            }
        }
        haupt.post(warten)
    }

    private fun stoppen() {
        vibrieren(longArrayOf(0, 300))
        seite(StartActivity.WAS_STOPP)
    }

    /** Die Aufnahme muss aus einer sichtbaren Seite starten (Android 14+), wie beim Widget. */
    private fun seite(was: String) {
        startActivity(
            Intent(this, StartActivity::class.java).putExtra(StartActivity.EXTRA_WAS, was).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun vibrieren(muster: LongArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(muster, -1))
        } catch (_: Exception) {
        }
    }

    /** Kleines Protokoll in files/tasten.log, nur Essential-Key-Ereignisse. */
    private fun protokoll(text: String) {
        try {
            val f = File(filesDir, "tasten.log")
            if (f.length() > 100_000) f.delete()
            f.appendText(SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT).format(Date()) + " " + text + "\n")
        } catch (_: Exception) {
        }
    }
}
