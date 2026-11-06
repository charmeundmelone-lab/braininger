package de.braininger.lite

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Aufnahme-Taste über eine Bedienungshilfe, die nur Tastendrücke sieht (wie die Essential Key in der großen App).
 * Taste: die Knopf-Taste am Headset/Ansteckmikrofon (HEADSETHOOK oder PLAY/PAUSE).
 *  - doppelt: Gedanke starten / stoppen
 *  - lang (ohne Aufnahme): Funkgerät, halten = aufnehmen, loslassen = fertig
 * Alle anderen Tasten gehen unverändert weiter (Lautstärke blättert z. B. in Books).
 * Zum Herausfinden, was der Kompakt meldet, schreibt der Dienst jede Taste ins Protokoll.
 */
class TastenDienst : AccessibilityService() {

    companion object {
        private const val DOPPEL_MS = 450L
        private const val LANG_MS = 700L
        private const val START_GEDULD_MS = 5000L
        private val TASTEN = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        @Volatile var verbunden = false
            private set
    }

    private val haupt = Handler(Looper.getMainLooper())
    private var letztesLoslassen = 0L
    private var langAusgeloest = false
    private var funk = false
    private var stoppNachStart = false

    private val langPruefen = Runnable {
        langAusgeloest = true
        if (!AufnahmeDienst.laeuft) {
            Lager.protokoll(this, "Taste lang: Funkgerät")
            funk = true
            stoppNachStart = false
            starten(longArrayOf(0, 120))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        verbunden = true
        Lager.protokoll(this, "Tasten-Dienst verbunden")
    }

    override fun onDestroy() {
        verbunden = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            Lager.protokoll(this, "Taste ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} " +
                (if (event.action == KeyEvent.ACTION_DOWN) "runter" else "hoch") + " (${event.device?.name})")
        }
        if (event.keyCode !in TASTEN) return false
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
                        Lager.protokoll(this, "losgelassen: Funkgerät beenden")
                        if (AufnahmeDienst.laeuft) stoppen() else stoppNachStart = true
                    }
                    langAusgeloest -> {}
                    jetzt - letztesLoslassen < DOPPEL_MS -> {
                        letztesLoslassen = 0L
                        if (AufnahmeDienst.laeuft) stoppen() else starten(longArrayOf(0, 80, 100, 80))
                    }
                    else -> letztesLoslassen = jetzt
                }
            }
        }
        return true
    }

    /** Brummt erst, wenn wirklich aufgenommen wird (dann kann man lossprechen). */
    private fun starten(muster: LongArray) {
        seite(StartActivity.STARTEN)
        val bis = SystemClock.uptimeMillis() + START_GEDULD_MS
        haupt.post(object : Runnable {
            override fun run() {
                when {
                    AufnahmeDienst.laeuft -> {
                        vibrieren(muster)
                        if (stoppNachStart) {
                            stoppNachStart = false
                            stoppen()
                        }
                    }
                    SystemClock.uptimeMillis() < bis -> haupt.postDelayed(this, 100)
                    else -> {
                        Lager.protokoll(this@TastenDienst, "Aufnahme startete nicht")
                        vibrieren(longArrayOf(0, 60, 60, 60, 60, 60))
                    }
                }
            }
        })
    }

    private fun stoppen() {
        vibrieren(longArrayOf(0, 220, 120, 220, 120, 220))
        seite(StartActivity.STOPPEN)
    }

    private fun seite(was: String) {
        startActivity(Intent(this, StartActivity::class.java).putExtra(StartActivity.EXTRA_WAS, was)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun vibrieren(muster: LongArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(muster, -1))
        } catch (_: Exception) {
        }
    }
}
