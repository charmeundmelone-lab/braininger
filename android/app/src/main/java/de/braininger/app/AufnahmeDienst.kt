package de.braininger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.braininger.app.drive.HochladenAuftrag
import java.io.File

/**
 * Vordergrund-Dienst für die Aufnahme. Läuft mit Benachrichtigung weiter,
 * auch wenn das Handy gesperrt ist oder die App geschlossen wird.
 */
class AufnahmeDienst : Service() {

    companion object {
        private const val AKTION_START = "de.braininger.START"
        private const val AKTION_STOPP = "de.braininger.STOPP"
        private const val AKTION_MARKIEREN = "de.braininger.MARKIEREN"
        private const val AKTION_FOTO = "de.braininger.FOTO"
        private const val EXTRA_DATEI = "datei"
        private const val EXTRA_SEK = "sek"
        private const val EXTRA_MODUS = "modus"
        private const val KANAL_AUFNAHME = "aufnahme"
        private const val KANAL_HINWEISE = "hinweise"
        private const val ID_AUFNAHME = 1
        private const val ID_HINWEIS = 2

        fun starten(context: Context, modus: Modus) {
            val intent = Intent(context, AufnahmeDienst::class.java)
                .setAction(AKTION_START).putExtra(EXTRA_MODUS, modus.kennung)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Die Foto-Seite meldet ein fertiges Foto, damit es in info.json steht und hochgeladen wird. */
        fun fotoMelden(context: Context, datei: String, sek: Long) {
            context.startService(
                Intent(context, AufnahmeDienst::class.java).setAction(AKTION_FOTO)
                    .putExtra(EXTRA_DATEI, datei).putExtra(EXTRA_SEK, sek)
            )
        }

        fun markieren(context: Context) {
            context.startService(Intent(context, AufnahmeDienst::class.java).setAction(AKTION_MARKIEREN))
        }

        fun stoppen(context: Context) {
            context.startService(Intent(context, AufnahmeDienst::class.java).setAction(AKTION_STOPP))
        }

        fun kanaeleAnlegen(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(KANAL_AUFNAHME, context.getString(R.string.kanal_aufnahme), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(KANAL_HINWEISE, context.getString(R.string.kanal_hinweise), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private var recorder: StueckRecorder? = null
    private var ordner: File? = null
    private var modus = Modus.GEDANKE
    private var start = 0L
    private var auswertung = "laptop" // handy, sobald ein Claude-Schlüssel eingetragen ist
    private val stuecke = mutableListOf<String>()
    private val hinweise = mutableListOf<String>()
    private val markierungen = mutableListOf<Long>() // Sekunden ab Aufnahmebeginn
    private val fotos = mutableListOf<Pair<String, Long>>() // Datei, Sekunde
    private var weckSperre: PowerManager.WakeLock? = null
    private val haupt = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    private val geraeteBeobachter = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = mikrofonPruefen()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = mikrofonPruefen()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        kanaeleAnlegen(this)
        GlyphLicht.verbinden(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AKTION_START -> if (recorder == null) aufnahmeStarten(Modus.aus(intent.getStringExtra(EXTRA_MODUS)))
            AKTION_STOPP -> aufnahmeBeenden()
            AKTION_MARKIEREN -> markieren()
            AKTION_FOTO -> {
                val datei = intent.getStringExtra(EXTRA_DATEI)
                if (recorder != null && datei != null) {
                    fotos += datei to intent.getLongExtra(EXTRA_SEK, 0L)
                    AufnahmeStatus.aendern { it.copy(fotos = fotos.size) }
                    infoSchreiben(ende = null, status = "laeuft")
                    HochladenAuftrag.anstossen(this)
                } else if (recorder == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun aufnahmeStarten(m: Modus) {
        modus = m
        start = System.currentTimeMillis()
        // Eine laufende Spracherkennung macht Pause, damit die Aufnahme die volle Rechenkraft hat
        de.braininger.app.erkennung.Whisper.abbrechen()
        auswertung = if (de.braininger.app.erkennung.HandyAuswertung.sollAmHandy(this)) "handy" else "laptop"
        stuecke.clear()
        hinweise.clear()
        markierungen.clear()
        fotos.clear()

        // Sofort in den Vordergrund, sonst beendet Android den Dienst nach wenigen Sekunden.
        ServiceCompat.startForeground(
            this, ID_AUFNAHME, benachrichtigung(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        weckSperre = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Braininger:Aufnahme")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        val geraet = MikroWahl.bestes(this)
        val o = Ablage.neueAufnahme(this, modus, start)
        ordner = o
        // erst jetzt kennt die Benachrichtigung den Ordner für den Foto-Knopf
        getSystemService(NotificationManager::class.java).notify(ID_AUFNAHME, benachrichtigung())
        if (!MikroWahl.istExtern(geraet)) {
            hinweise += "Kein Lavalier gefunden, Aufnahme mit dem eingebauten Mikrofon."
            hinweisZeigen("Kein Lavalier-Mikrofon", "Braininger nimmt mit dem eingebauten Mikrofon auf.")
        }

        recorder = StueckRecorder(
            ordner = o,
            geraet = geraet,
            beiStueckFertig = { datei -> haupt.post { stueckFertig(datei) } },
            beiFehler = { text -> haupt.post { fehler(text) } },
        ).also { it.start() }

        audio.registerAudioDeviceCallback(geraeteBeobachter, haupt)
        AufnahmeStatus.setzen(
            AufnahmeZustand(
                laeuft = true, modus = modus, start = start,
                mikrofon = MikroWahl.name(geraet), mikrofonExtern = MikroWahl.istExtern(geraet),
                warnung = if (MikroWahl.istExtern(geraet)) null else "Eingebautes Mikrofon",
                ordner = o.absolutePath,
            )
        )
        infoSchreiben(ende = null, status = "laeuft")
        BrainingerWidget.aktualisieren(this)
        GlyphLicht.start()
    }

    private fun stueckFertig(datei: File) {
        stuecke += datei.name
        AufnahmeStatus.aendern { it.copy(stueckeFertig = stuecke.size) }
        infoSchreiben(ende = null, status = if (recorder != null) "laeuft" else "fertig")
        HochladenAuftrag.anstossen(this)
    }

    /** Markier-Knopf: merkt sich die Stelle, der Laptop behandelt sie wie die Sprachmarke „Wichtig“. */
    private fun markieren() {
        if (recorder == null) return
        markierungen += (System.currentTimeMillis() - start) / 1000
        AufnahmeStatus.aendern { it.copy(markierungen = markierungen.size) }
        infoSchreiben(ende = null, status = "laeuft")
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
        getSystemService(NotificationManager::class.java).notify(ID_AUFNAHME, benachrichtigung())
        GlyphLicht.markieren()
    }

    private fun mikrofonPruefen() {
        val r = recorder ?: return
        val bestes = MikroWahl.bestes(this)
        r.bevorzugtesGeraet(bestes)
        val extern = MikroWahl.istExtern(bestes)
        val vorher = AufnahmeStatus.zustand.value.mikrofonExtern
        if (vorher && !extern) {
            val sek = (System.currentTimeMillis() - start) / 1000
            hinweise += "Lavalier getrennt nach %d:%02d min, weiter mit eingebautem Mikrofon.".format(sek / 60, sek % 60)
            hinweisZeigen("Lavalier getrennt", "Die Aufnahme läuft mit dem eingebauten Mikrofon weiter.")
        }
        AufnahmeStatus.aendern {
            it.copy(
                mikrofon = MikroWahl.name(bestes), mikrofonExtern = extern,
                warnung = if (extern) null else "Eingebautes Mikrofon",
            )
        }
    }

    private fun fehler(text: String) {
        hinweise += text
        hinweisZeigen("Aufnahme gestört", text)
        aufnahmeBeenden()
    }

    private fun aufnahmeBeenden() {
        val r = recorder
        if (r == null) {
            stopSelf()
            return
        }
        recorder = null
        audio.unregisterAudioDeviceCallback(geraeteBeobachter)
        GlyphLicht.ende()
        // Das Abschließen des letzten Stücks dauert kurz, deshalb nicht im Hauptfaden.
        Thread {
            r.stopp()
            haupt.post {
                infoSchreiben(ende = System.currentTimeMillis(), status = "fertig")
                HochladenAuftrag.anstossen(this)
                de.braininger.app.erkennung.HandyAuswertung.anstossen(this)
                weckSperre?.let { if (it.isHeld) it.release() }
                weckSperre = null
                AufnahmeStatus.setzen(AufnahmeZustand(laeuft = false))
                BrainingerWidget.aktualisieren(this)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun infoSchreiben(ende: Long?, status: String) {
        val o = ordner ?: return
        try {
            Ablage.infoSchreiben(
                o, modus, start, ende, stuecke.toList(),
                AufnahmeStatus.zustand.value.mikrofon.ifBlank { MikroWahl.name(null) },
                status, hinweise.toList(), markierungen.toList(), fotos.toList(), auswertung,
            )
        } catch (e: Exception) {
            hinweisZeigen("Speichern fehlgeschlagen", e.message ?: "unbekannter Fehler")
        }
    }

    private fun benachrichtigung(): Notification {
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markieren = PendingIntent.getService(
            this, 2, Intent(this, AufnahmeDienst::class.java).setAction(AKTION_MARKIEREN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val foto = ordner?.let {
            PendingIntent.getActivity(
                this, 3, FotoActivity.intent(this, it, start),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val stopp = PendingIntent.getService(
            this, 1, Intent(this, AufnahmeDienst::class.java).setAction(AKTION_STOPP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, KANAL_AUFNAHME)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Aufnahme läuft: ${modus.anzeige}")
            .setContentText(
                markierungen.lastOrNull()?.let { "${markierungen.size}× markiert, zuletzt bei %d:%02d".format(it / 60, it % 60) }
                    ?: "Tippen zum Öffnen"
            )
            .setWhen(start)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(oeffnen)
            .addAction(0, getString(R.string.knopf_markieren), markieren)
            .apply { foto?.let { addAction(0, getString(R.string.knopf_foto), it) } }
            .addAction(0, getString(R.string.knopf_stopp), stopp)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Knöpfe auch auf dem Sperrbildschirm
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun hinweisZeigen(titel: String, text: String) {
        val n = NotificationCompat.Builder(this, KANAL_HINWEISE)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(ID_HINWEIS, n)
        } catch (_: SecurityException) {
            // Benachrichtigungen nicht erlaubt: Hinweis steht trotzdem in der info.json.
        }
    }

    override fun onDestroy() {
        // Falls Android den Dienst beendet, das offene Stück noch retten.
        recorder?.stopp()
        recorder = null
        weckSperre?.let { if (it.isHeld) it.release() }
        AufnahmeStatus.setzen(AufnahmeZustand(laeuft = false))
        BrainingerWidget.aktualisieren(this)
        super.onDestroy()
    }
}
