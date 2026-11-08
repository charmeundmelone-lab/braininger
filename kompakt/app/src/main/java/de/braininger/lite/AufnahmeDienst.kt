package de.braininger.lite

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nimmt Gedanken auf und horcht auf die Lauter-Taste.
 *
 * Der Dienst läuft dauerhaft im Vordergrund („bereit“), damit Android ihn bei dunklem Bildschirm nicht beendet und die
 * Taste immer ankommt. Mudita stellt nachinstallierte Apps auf „Akku eingeschränkt“: dann stoppt Android den Dienst
 * eine Minute nach dem Ausschalten des Bildschirms. Die App warnt auf der Startseite, der Laptop hebt es per Kabel auf.
 *
 * Taste: Lauter-Taste 1 Sekunde halten = Aufnahme an/aus, auch bei ausgeschaltetem Bildschirm. Möglich macht das eine
 * Mediensitzung mit eigener Lautstärke: Lautstärketasten, die keine App selbst nutzt, landen dann hier. Kurze Drücke
 * reichen wir als normale Lautstärke weiter. Spielt etwas anderes Ton ab oder läuft ein Anruf, ist die Sitzung aus und
 * die Tasten verhalten sich ganz normal.
 */
class AufnahmeDienst : Service() {

    companion object {
        private const val START = "start"
        private const val STOPP = "stopp"
        private const val BEREIT = "bereit"
        private const val UMSCHALTEN = "umschalten"
        private const val KANAL = "aufnahme"
        private const val KANAL_BEREIT = "bereit"

        /** So lange muss die Lauter-Taste gehalten werden. */
        const val HALTEN_MS = 900L

        @Volatile var laeuft = false
            private set
        @Volatile var seit = 0L
            private set
        /** Die laufende Aufnahme bekommt nur Stille (Offline+ oder Android-Sperre). */
        @Volatile var stumm = false
            private set
        /** Stille kommt von Android selbst (Dienst ohne Mikrofon-Erlaubnis gestartet, z. B. nach Neustart). */
        @Volatile var gesperrt = false
            private set
        private val beobachter = mutableSetOf<() -> Unit>()

        fun beobachten(f: () -> Unit) = synchronized(beobachter) { beobachter += f }
        fun nichtMehr(f: () -> Unit) = synchronized(beobachter) { beobachter -= f }
        private fun melden() = synchronized(beobachter) { beobachter.toList() }.forEach { it() }

        private fun senden(c: Context, aktion: String, taste: Boolean = false) {
            try {
                c.startForegroundService(Intent(c, AufnahmeDienst::class.java).setAction(aktion).putExtra("taste", taste))
            } catch (e: Exception) {
                Lager.protokoll(c, "Dienst ließ sich nicht starten ($aktion): ${e.javaClass.simpleName}")
            }
        }

        fun starten(c: Context) = senden(c, START)
        fun stoppen(c: Context) { if (laeuft) senden(c, STOPP) }
        /** Lauter-Taste in der App gehalten. */
        fun umschalten(c: Context) = senden(c, UMSCHALTEN, taste = true)

        /** Dienst bereithalten (beim Öffnen der App, nach Neustart, nach Update). */
        fun bereitHalten(c: Context) {
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) senden(c, BEREIT)
        }
    }

    private var recorder: StueckRecorder? = null
    private var ordner: File? = null
    private var info: JSONObject? = null
    private var start = Date()
    private var sperre: PowerManager.WakeLock? = null
    private val haupt = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tasteEinrichten()
        haupt.postDelayed({
            Lager.protokoll(this, "Dienst bereit, Lauter-Taste aktiv" + if (mikrofonGesperrt()) " (Mikrofon noch gesperrt)" else "")
        }, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        vordergrund() // jede Nachricht kommt per startForegroundService: sofort in den Vordergrund
        val taste = intent?.getBooleanExtra("taste", false) == true
        when (intent?.action) {
            START -> if (!laeuft) beginnen(taste)
            STOPP -> if (laeuft) beenden(taste)
            UMSCHALTEN -> umschalten()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- Benachrichtigung

    private fun vordergrund() {
        val nm = getSystemService(NotificationManager::class.java)
        val oeffnen = PendingIntent.getActivity(
            this, 2, Intent(this, LiteActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = if (laeuft) {
            nm.createNotificationChannel(NotificationChannel(KANAL, "Aufnahme", NotificationManager.IMPORTANCE_LOW))
            val stopp = PendingIntent.getForegroundService(
                this, 1, Intent(this, AufnahmeDienst::class.java).setAction(STOPP), PendingIntent.FLAG_IMMUTABLE
            )
            Notification.Builder(this, KANAL)
                .setContentTitle("Aufnahme läuft seit " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(start))
                .setContentText("Stoppen: Lauter-Taste 1 Sekunde halten")
                .addAction(Notification.Action.Builder(null, "STOPP", stopp).build())
        } else {
            nm.createNotificationChannel(NotificationChannel(KANAL_BEREIT, "Bereit", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, KANAL_BEREIT)
                .setContentTitle("Braininger ist bereit")
                .setContentText(if (mikrofonGesperrt()) "Einmal Braininger öffnen, dann nimmt die Lauter-Taste auf"
                    else "Aufnehmen: Lauter-Taste 1 Sekunde halten")
        }
            .setSmallIcon(R.drawable.symbol)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(oeffnen)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /**
     * Darf der Dienst gerade ans Mikrofon? Android erlaubt das nur, wenn er aus der sichtbaren App (oder einem
     * erlaubten Anlass) gestartet wurde. Nur ein klares „nein“ zählt, im Zweifel gilt es als frei.
     */
    private fun mikrofonGesperrt(): Boolean = try {
        getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), packageName
        ) == android.app.AppOpsManager.MODE_IGNORED
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- Lauter-Taste

    private lateinit var audio: AudioManager
    private var sitzung: MediaSession? = null
    private var gedrueckt = false
    private var ausgeloest = false
    private var tastenSperre: PowerManager.WakeLock? = null

    private val gehalten = Runnable {
        ausgeloest = true
        Lager.protokoll(this, "Lauter-Taste gehalten")
        umschalten()
    }

    private fun tasteEinrichten() {
        audio = getSystemService(AudioManager::class.java)
        tastenSperre = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "braininger:taste").apply { setReferenceCounted(false) }
        val s = MediaSession(this, "Braininger Lite Taste")
        s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) = taste(direction)
        })
        s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        s.setCallback(object : MediaSession.Callback() {}, haupt)
        sitzung = s
        audio.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = sitzungAnpassen()
        }, haupt)
        audio.addOnModeChangedListener(mainExecutor) { sitzungAnpassen() }
        sitzungAnpassen()
    }

    /** Sitzung nur, wenn nichts anderes Ton abspielt und kein Anruf läuft: dann gehören die Tasten wieder den anderen. */
    private fun sitzungAnpassen() {
        val s = sitzung ?: return
        val scharf = !audio.isMusicActive && audio.mode == AudioManager.MODE_NORMAL
        if (s.isActive != scharf) s.isActive = scharf
    }

    /**
     * Meldungen der Mediensitzung: +1 = Lauter gedrückt, −1 = Leiser gedrückt, 0 = losgelassen.
     * Lauter: Halten löst nach [HALTEN_MS] aus, kurzes Drücken wird zu normalem „lauter“. Leiser: sofort „leiser“.
     */
    private fun taste(richtung: Int) {
        when {
            richtung > 0 -> if (!gedrueckt) {
                gedrueckt = true
                ausgeloest = false
                tastenSperre?.acquire(5_000) // damit das Handy beim Halten nicht einschläft
                haupt.postDelayed(gehalten, HALTEN_MS)
            }
            richtung < 0 -> {
                abbrechen()
                lautstaerke(AudioManager.ADJUST_LOWER)
            }
            else -> {
                val warKurz = gedrueckt && !ausgeloest
                abbrechen()
                if (warKurz) lautstaerke(AudioManager.ADJUST_RAISE)
            }
        }
    }

    private fun abbrechen() {
        gedrueckt = false
        haupt.removeCallbacks(gehalten)
        try { tastenSperre?.release() } catch (_: Exception) {}
    }

    /** Normale Lautstärke (Klingelton) mit Anzeige; direkt am Strom, sonst käme es wieder bei uns an. */
    private fun lautstaerke(richtung: Int) {
        if (!(getSystemService(PowerManager::class.java)).isInteractive) return // bei dunklem Bildschirm nichts verstellen
        try { audio.adjustStreamVolume(AudioManager.STREAM_RING, richtung, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
    }

    private fun umschalten() {
        if (laeuft) beenden(taste = true) else beginnen(taste = true)
    }

    // ---------------------------------------------------------------- Aufnahme

    /** Ansteckmikrofon oder Headset am USB-C-Anschluss vor dem eingebauten Mikrofon. */
    private fun mikrofon(): AudioDeviceInfo? {
        val reihenfolge = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        val geraete = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (typ in reihenfolge) geraete.firstOrNull { it.type == typ }?.let { return it }
        return null
    }

    private fun beginnen(taste: Boolean) {
        if (laeuft) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Lager.protokoll(this, "Aufnahme nicht möglich: Mikrofon nicht erlaubt")
            if (taste) Summer.warnung(this)
            return
        }
        start = Date()
        stumm = false
        gesperrt = false
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
        vordergrund()
        if (taste) Summer.start(this)
        Lager.protokoll(this, "Aufnahme gestartet $id ($mikroName${if (taste) ", per Taste" else ""})")
        melden()
        Lager.protokoll(this, "Offline+: " + (Offline.an(this)?.let { if (it) "an" else "aus" } ?: "nicht lesbar"))
        haupt.removeCallbacks(stummWache)
        haupt.postDelayed(stummWache, 1000)
    }

    /**
     * Stille erkennen, jede Sekunde: Offline+ trennt die Mikrofone per Hardware (fast nur Nullen), Android selbst
     * schaltet eine Aufnahme stumm, wenn der Dienst ohne Mikrofon-Erlaubnis lief. Ab 2 stummen Sekunden deutlich
     * warnen. Kommt wieder Ton, ist die Warnung erledigt und kann beim nächsten Mal erneut kommen.
     */
    private val stummWache: Runnable = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val systemSperre = r.vomSystemGesperrt()
            val jetztStumm = systemSperre || r.stummSekunden >= 2 || Offline.an(this@AufnahmeDienst) == true
            if (jetztStumm && !stumm) warnen(systemSperre)
            if (!jetztStumm && stumm) {
                stumm = false
                gesperrt = false
                getSystemService(NotificationManager::class.java).cancel(3)
                Lager.protokoll(this@AufnahmeDienst, "Mikrofon liefert wieder Ton")
                melden()
            }
            haupt.postDelayed(this, 1000)
        }
    }

    private fun warnen(systemSperre: Boolean) {
        stumm = true
        gesperrt = systemSperre
        val sekunde = (System.currentTimeMillis() - start.time) / 1000
        val grund = if (systemSperre) "Android hat das Mikrofon gesperrt" else "Offline+-Schalter an?"
        Lager.protokoll(this, "Mikrofon stumm ab etwa Sekunde $sekunde ($grund)")
        synchronized(this) {
            info?.getJSONArray("hinweise")?.put("Mikrofon lieferte ab etwa Sekunde $sekunde nur Stille ($grund).")
        }
        Summer.warnung(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("warnung", "Warnungen", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(3, Notification.Builder(this, "warnung")
            .setSmallIcon(R.drawable.symbol)
            .setContentTitle("Mikrofon ist stumm")
            .setContentText(
                if (systemSperre) "Braininger einmal öffnen, dann neu aufnehmen."
                else "Offline+-Schalter an der Seite ausschalten, dann neu aufnehmen."
            )
            .setAutoCancel(true)
            .build())
        melden()
    }

    private fun beenden(taste: Boolean) {
        val r = recorder ?: return
        recorder = null
        haupt.removeCallbacks(stummWache)
        if (taste) Summer.stopp(this)
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
            gesperrt = false
            seit = 0L
            try { sperre?.release() } catch (_: Exception) {}
            getSystemService(NotificationManager::class.java).cancel(3)
            Lager.protokoll(this, "Aufnahme beendet ${ordner?.name}, ${(ende.time - start.time) / 1000} s")
            haupt.post { vordergrund() } // zurück auf „bereit“
            melden()
            AbgleichJob.jetzt(this)
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
            Lager.protokoll(this, "Aufnahme unterbrochen: Android hat den Dienst beendet")
        }
        haupt.removeCallbacksAndMessages(null)
        try { sperre?.release() } catch (_: Exception) {}
        try { tastenSperre?.release() } catch (_: Exception) {}
        sitzung?.release()
        sitzung = null
        super.onDestroy()
    }
}
