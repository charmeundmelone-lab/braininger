package de.braininger.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nimmt einen Gedanken auf (Vordergrund-Dienst „Mikrofon“, läuft auch bei ausgeschaltetem Bildschirm weiter). */
class AufnahmeDienst : Service() {

    companion object {
        private const val START = "start"
        private const val STOPP = "stopp"
        private const val KANAL = "aufnahme"

        @Volatile var laeuft = false
            private set
        @Volatile var seit = 0L
            private set
        /** Die laufende Aufnahme bekommt nur Stille (Offline+). */
        @Volatile var stumm = false
            private set
        private val beobachter = mutableSetOf<() -> Unit>()

        fun beobachten(f: () -> Unit) = synchronized(beobachter) { beobachter += f }
        fun nichtMehr(f: () -> Unit) = synchronized(beobachter) { beobachter -= f }
        private fun melden() = synchronized(beobachter) { beobachter.toList() }.forEach { it() }

        fun starten(c: Context) {
            c.startForegroundService(Intent(c, AufnahmeDienst::class.java).setAction(START))
        }

        fun stoppen(c: Context) {
            if (laeuft) c.startService(Intent(c, AufnahmeDienst::class.java).setAction(STOPP))
        }
    }

    private var recorder: StueckRecorder? = null
    private var ordner: File? = null
    private var info: JSONObject? = null
    private var start = Date()
    private var sperre: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!laeuft) beginnen() else vordergrund()
            STOPP -> beenden()
            else -> if (!laeuft) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun vordergrund() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Aufnahme", NotificationManager.IMPORTANCE_LOW))
        val stopp = PendingIntent.getService(
            this, 1, Intent(this, AufnahmeDienst::class.java).setAction(STOPP), PendingIntent.FLAG_IMMUTABLE
        )
        val oeffnen = PendingIntent.getActivity(
            this, 2, Intent(this, LiteActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, KANAL)
            .setSmallIcon(R.drawable.symbol)
            .setContentTitle("Aufnahme läuft")
            .setContentText("seit " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(start))
            .setOngoing(true)
            .setContentIntent(oeffnen)
            .addAction(Notification.Action.Builder(null, "STOPP", stopp).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** Ansteckmikrofon oder Headset am USB-C-Anschluss vor dem eingebauten Mikrofon. */
    private fun mikrofon(): AudioDeviceInfo? {
        val am = getSystemService(AudioManager::class.java)
        val reihenfolge = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        val geraete = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (typ in reihenfolge) geraete.firstOrNull { it.type == typ }?.let { return it }
        return null
    }

    private fun beginnen() {
        start = Date()
        stumm = false
        vordergrund()
        val id = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(start) + "_gedanke"
        val o = File(Lager.ordner(this), id).apply { mkdirs() }
        ordner = o
        val mikro = mikrofon()
        val hinweise = JSONArray()
        val mikroName = if (mikro != null) "Ansteckmikrofon (USB)" else "eingebautes Mikrofon".also {
            hinweise.put("Kein Ansteckmikrofon gefunden, Aufnahme mit dem eingebauten Mikrofon.")
        }
        info = JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("modus", "gedanke")
            .put("start", Lager.iso(start))
            .put("stuecke", JSONArray())
            .put("mikrofon", mikroName)
            .put("status", "laeuft")
            .put("hinweise", hinweise)
            .put("markierungen", JSONArray())
            .put("fotos", JSONArray())
            .put("app_version", "lite-" + versionName())
            .put("geraet", "Mudita Kompakt")
        infoSchreiben()

        sperre = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "braininger:aufnahme").apply { acquire(4 * 60 * 60 * 1000L) }

        recorder = StueckRecorder(
            ordner = o,
            geraet = mikro,
            beiStueckFertig = { datei ->
                synchronized(this) {
                    info?.getJSONArray("stuecke")?.put(datei.name)
                    infoSchreiben()
                }
            },
            beiFehler = { meldung ->
                Lager.protokoll(this, "Aufnahme-Fehler: $meldung")
                synchronized(this) { info?.getJSONArray("hinweise")?.put(meldung) }
            },
        ).also { it.start() }
        laeuft = true
        seit = start.time
        Lager.protokoll(this, "Aufnahme gestartet $id ($mikroName)")
        melden()
        android.os.Handler(mainLooper).postDelayed({ stummPruefen() }, 3000)
    }

    /**
     * Der Offline+-Schalter des Kompakt trennt die Mikrofone per Hardware. Dann kommen nur Nullen an.
     * Nach 3 Sekunden prüfen und deutlich warnen, statt still eine leere Aufnahme zu machen.
     */
    private fun stummPruefen() {
        val r = recorder ?: return
        Lager.protokoll(this, "Pegel nach 3 s: ${r.spitze}")
        if (r.spitze >= 20) return
        stumm = true
        synchronized(this) {
            info?.getJSONArray("hinweise")?.put("Mikrofon lieferte nur Stille (Offline+-Schalter an?).")
        }
        try {
            getSystemService(android.os.Vibrator::class.java)?.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1)
            )
        } catch (_: Exception) {}
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("warnung", "Warnungen", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(3, Notification.Builder(this, "warnung")
            .setSmallIcon(R.drawable.symbol)
            .setContentTitle("Mikrofon ist stumm")
            .setContentText("Offline+-Schalter an der Seite ausschalten, dann neu aufnehmen.")
            .setAutoCancel(true)
            .build())
        melden()
    }

    private fun beenden() {
        val r = recorder ?: run { stopSelf(); return }
        recorder = null
        Thread {
            r.stopp() // schließt das letzte Stück ab (ruft beiStueckFertig)
            val ende = Date()
            synchronized(this) {
                info?.put("ende", Lager.iso(ende))
                info?.put("dauer_s", (ende.time - start.time) / 1000)
                info?.put("status", "fertig")
                infoSchreiben()
            }
            laeuft = false
            stumm = false
            seit = 0L
            try { sperre?.release() } catch (_: Exception) {}
            Lager.protokoll(this, "Aufnahme beendet ${ordner?.name}, ${(ende.time - start.time) / 1000} s")
            melden()
            AbgleichJob.jetzt(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun infoSchreiben() {
        val o = ordner ?: return
        val i = info ?: return
        Lager.schreiben(File(o, "info.json"), i.toString(1))
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    override fun onDestroy() {
        // Falls Android den Dienst beendet: Aufnahme sauber abschließen, damit nichts verloren geht.
        recorder?.let { r ->
            recorder = null
            r.stopp()
            info?.put("status", "unterbrochen")
            info?.put("ende", Lager.iso(Date()))
            info?.put("dauer_s", (System.currentTimeMillis() - start.time) / 1000)
            infoSchreiben()
            laeuft = false
            stumm = false
            melden()
        }
        try { sperre?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
