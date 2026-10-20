package de.braininger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Zustand des Fokus-Timers für die Oberfläche. */
data class FokusZustand(val laeuft: Boolean = false, val aufgabe: String = "", val ende: Long = 0L, val dauerMs: Long = 0L)

/**
 * Fokus-Timer (Grill 20.10.2026, Frage 29–31): eine Aufgabe antippen, Länge wählen (10/25/45 min),
 * die Glyph-Lichter zeigen die Restzeit auf Streifen C, am Ende ein Lichtsignal. Kein Ton, kein Brummen.
 * Läuft als Vordergrund-Dienst, weil das Glyph Developer Kit die Lichter nur Apps im Vordergrund gibt.
 */
class FokusDienst : Service() {
    companion object {
        private const val KANAL = "fokus"
        private const val ID = 71
        private const val START = "de.braininger.FOKUS_START"
        private const val STOPP = "de.braininger.FOKUS_STOPP"
        private const val EXTRA_MIN = "minuten"
        private const val EXTRA_TEXT = "text"
        private const val TAKT_MS = 15_000L

        private val _zustand = MutableStateFlow(FokusZustand())
        val zustand: StateFlow<FokusZustand> = _zustand

        fun starten(context: Context, aufgabe: String, minuten: Int) {
            context.startForegroundService(
                Intent(context, FokusDienst::class.java).setAction(START).putExtra(EXTRA_MIN, minuten).putExtra(EXTRA_TEXT, aufgabe)
            )
        }

        fun stoppen(context: Context) {
            context.startService(Intent(context, FokusDienst::class.java).setAction(STOPP))
        }
    }

    private val haupt = Handler(Looper.getMainLooper())

    private val takt = object : Runnable {
        override fun run() {
            val z = _zustand.value
            if (!z.laeuft) return
            val rest = z.ende - System.currentTimeMillis()
            if (rest <= 0) {
                fertig()
                return
            }
            GlyphLicht.restzeit((rest * 100 / z.dauerMs).toInt())
            haupt.postDelayed(this, TAKT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val minuten = intent.getIntExtra(EXTRA_MIN, 25).coerceIn(1, 180)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val dauer = minuten * 60_000L
                _zustand.value = FokusZustand(true, text, System.currentTimeMillis() + dauer, dauer)
                ServiceCompat.startForeground(this, ID, benachrichtigung(text, minuten), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                GlyphLicht.verbinden(this)
                haupt.removeCallbacks(takt)
                haupt.postDelayed(takt, 1_000L)
            }
            STOPP -> beenden()
            else -> if (!_zustand.value.laeuft) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun fertig() {
        GlyphLicht.fokusEnde()
        haupt.postDelayed({ beenden() }, 3_000L)
        _zustand.value = _zustand.value.copy(laeuft = false)
    }

    private fun beenden() {
        haupt.removeCallbacks(takt)
        _zustand.value = FokusZustand()
        GlyphLicht.aus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        haupt.removeCallbacks(takt)
        super.onDestroy()
    }

    private fun benachrichtigung(text: String, minuten: Int): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KANAL, "Fokus-Timer", NotificationManager.IMPORTANCE_LOW)
        )
        val stopp = PendingIntent.getService(
            this, 72, Intent(this, FokusDienst::class.java).setAction(STOPP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val oeffnen = PendingIntent.getActivity(
            this, 73, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Fokus: $minuten Minuten")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(oeffnen)
            .addAction(0, "BEENDEN", stopp)
            .build()
    }
}
