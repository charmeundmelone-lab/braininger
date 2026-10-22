package de.braininger.app.erkennung

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.braininger.app.Ablage
import de.braininger.app.Aufnahme
import de.braininger.app.AufgabenSpeicher
import de.braininger.app.AufnahmeStatus
import de.braininger.app.MainActivity
import de.braininger.app.Ordnung
import de.braininger.app.R
import de.braininger.app.claude.Auswertung
import de.braininger.app.claude.ClaudeFehler
import de.braininger.app.claude.ClaudeSpaeter
import de.braininger.app.claude.DeckelErreicht
import de.braininger.app.claude.Kosten
import de.braininger.app.claude.Schluessel
import de.braininger.app.drive.DriveApi
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.ErgebnisAbruf
import de.braininger.app.drive.Hinweise
import de.braininger.app.drive.UploadStand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Auswertung am Handy (Grill 20.10.2026): erst Sprache → Text mit Whisper, dann Notizen mit Claude über Jans
 * API-Schlüssel. Der Laptop wertet solche Aufnahmen nicht selbst aus; er beantwortet nur Fragen und wertet auf Wunsch
 * neu aus.
 *
 * Ob eine Aufnahme am Handy ausgewertet wird, steht in info.json („auswertung“: handy | laptop) und wird beim Start
 * der Aufnahme festgelegt: am Handy, sobald ein Claude-Schlüssel eingetragen ist.
 *
 * Zustand je Aufnahme in handy.json: phase = text (wartet auf Erkennung) | claude (Text fertig, wartet auf Claude) |
 * hochladen (fertig, Ergebnis noch nicht im Drive) | fertig | laptop (Claude ging nicht, der Laptop soll es mit dem
 * Handy-Text machen).
 */
object HandyAuswertung {
    const val AKKU_GRENZE = 30 // Prozent (Frage 55): darunter wartet die Erkennung aufs Laden
    // so viel Audio auf einmal an Whisper. Klein genug, dass ein Stück in unter 10 min fertig ist: Läuft der Auftrag
    // ausnahmsweise ohne Vordergrund, beendet Android ihn nach 10 min, und der Zwischenstand bleibt trotzdem.
    private const val GRUPPE_S = 5 * 60
    private const val ID_ARBEIT = 21
    private const val ID_DECKEL = 22

    fun sollAmHandy(context: Context): Boolean = Schluessel.vorhanden(context)

    fun amHandy(ordner: File): Boolean = Ablage.infoLesen(ordner)?.optString("auswertung") == "handy"

    // --- Zustand ---

    private fun zustandDatei(ordner: File) = File(ordner, "handy.json")

    fun phase(ordner: File): String? = try {
        JSONObject(zustandDatei(ordner).readText()).optString("phase").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun phaseSetzen(ordner: File, phase: String, meldung: String? = null) {
        val j = JSONObject().put("phase", phase).put("meldung", meldung ?: JSONObject.NULL)
            .put("geaendert", System.currentTimeMillis())
        val tmp = File(ordner, "handy.json.tmp")
        tmp.writeText(j.toString(1))
        tmp.renameTo(zustandDatei(ordner))
    }

    /** Darf der Ergebnis-Abruf die lokale ergebnis.json mit der aus dem Drive überschreiben? */
    fun abrufErlaubt(ordner: File): Boolean = !amHandy(ordner) || phase(ordner) in setOf("fertig", "laptop")

    /** Wartet die Aufnahme noch auf die Handy-Auswertung (nicht auf den Laptop)? */
    fun inArbeit(ordner: File): Boolean = amHandy(ordner) && phase(ordner) !in setOf("fertig", "laptop")

    // --- Aufträge anstoßen ---

    fun anstossen(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            "braininger-erkennung", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ErkennungAuftrag>().build(),
        )
        claudeAnstossen(context)
    }

    fun claudeAnstossen(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "braininger-claude", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ClaudeAuftrag>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Wartet, bis das Handy am Strom hängt (Akku unter der Grenze). */
    fun beimLadenAnstossen(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "braininger-erkennung-laden", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ErkennungAuftrag>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build(),
        )
    }

    fun akkuReicht(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val laedt = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val skala = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || skala <= 0) return true
        return laedt || level * 100 / skala >= AKKU_GRENZE
    }

    fun offeneAufnahmen(context: Context): List<Aufnahme> {
        val ausgeblendet = Ordnung.ausgeblendet(context)
        return Ablage.alle(context).filter {
            (it.status == "fertig" || it.status == "unterbrochen") && amHandy(it.ordner) &&
                it.ordner.name !in ausgeblendet && phase(it.ordner) !in setOf("fertig", "laptop")
        }.sortedBy { it.start }
    }

    // --- Schritt 1: Text ---

    private fun textDatei(ordner: File) = File(ordner, "text.json")

    fun textVorhanden(ordner: File) = textDatei(ordner).exists()

    /**
     * Erkennt den Text aller Stücke einer Aufnahme. Je Gruppe (ca. 5 min Ton) wird das Zwischenergebnis gesichert,
     * damit ein Abbruch (neue Aufnahme, Akku, Android beendet den Auftrag) nicht von vorn anfängt.
     */
    suspend fun textErkennen(context: Context, a: Aufnahme, fortschritt: (Int) -> Unit) {
        val info = Ablage.infoLesen(a.ordner) ?: return
        val stuecke = info.optJSONArray("stuecke")?.let { s -> (0 until s.length()).map { s.getString(it) } }.orEmpty()
            .map { File(a.ordner, it) }.filter { it.exists() && it.length() > 0 }
        val teilDatei = File(a.ordner, "text_teil.json")
        val teil = try { JSONObject(teilDatei.readText()) } catch (_: Exception) { JSONObject() }
        var erledigt = teil.optInt("stuecke_fertig", 0)
        var versatz = teil.optDouble("versatz_s", 0.0)
        val abschnitte = teil.optJSONArray("abschnitte") ?: JSONArray()
        val modell = Modelle.datei(context, Modelle.WHISPER)
        val vad = Modelle.datei(context, Modelle.VAD)
        val t0 = System.currentTimeMillis()

        while (erledigt < stuecke.size) {
            // nächste Gruppe von Stücken zusammensetzen
            val puffer = ArrayList<FloatArray>()
            var laenge = 0
            var bis = erledigt
            while (bis < stuecke.size && laenge < GRUPPE_S * AudioDekoder.ZIELRATE) {
                // ein beschädigtes Stück (z. B. das letzte nach einem Absturz) wird übersprungen, wie am Laptop
                val werte = try {
                    AudioDekoder.dekodieren(stuecke[bis])
                } catch (e: Exception) {
                    FloatArray(0)
                }
                puffer += werte
                laenge += werte.size
                bis++
            }
            val pcm = FloatArray(laenge)
            var pos = 0
            for (w in puffer) {
                System.arraycopy(w, 0, pcm, pos, w.size)
                pos += w.size
            }
            puffer.clear()

            val anteilVorher = erledigt.toDouble() / stuecke.size
            val anteilGruppe = (bis - erledigt).toDouble() / stuecke.size
            val erkannt = coroutineScope {
                val anzeige = launch {
                    while (true) {
                        fortschritt(((anteilVorher + anteilGruppe * Whisper.fortschritt() / 100.0) * 100).toInt())
                        delay(5_000)
                    }
                }
                try {
                    Whisper.erkennen(modell, vad, pcm, versatz, hinweis = de.braininger.app.Einstellungen.erkennungsHinweis(context))
                } finally {
                    anzeige.cancel()
                }
            }
            for (e in erkannt) {
                abschnitte.put(JSONObject().put("start", runde(e.start)).put("ende", runde(e.ende))
                    .put("sprecher", JSONObject.NULL).put("text", e.text).put("pause", false))
            }
            versatz += laenge.toDouble() / AudioDekoder.ZIELRATE
            erledigt = bis
            val tmp = File(a.ordner, "text_teil.json.tmp")
            tmp.writeText(JSONObject().put("stuecke_fertig", erledigt).put("versatz_s", versatz).put("abschnitte", abschnitte).toString())
            tmp.renameTo(teilDatei)
        }

        val text = JSONObject()
            .put("abschnitte", abschnitte)
            .put("sprache", "de")
            .put("sprache_sicherheit", 1.0)
            .put("sprecher_anzahl", JSONObject.NULL)
            .put("dauer_audio_s", runde(versatz))
            .put("rechenzeit_s", runde((System.currentTimeMillis() - t0) / 1000.0))
            .put("quelle", "handy")
        val tmp = File(a.ordner, "text.json.tmp")
        tmp.writeText(text.toString(1))
        tmp.renameTo(textDatei(a.ordner))
        teilDatei.delete()
        // Text ist gesichert: ab jetzt darf das Audio nach 3 Tagen weg (text_fertig), Notizen folgen
        ergebnisSchreiben(a.ordner, basis(a, text).put("status", "in_arbeit").put("schritt", "auswertung"))
        phaseSetzen(a.ordner, "claude")
    }

    private fun runde(x: Double) = Math.round(x * 10) / 10.0

    /** Grundfelder der ergebnis.json (gleiches Format wie am Laptop, laptop/braininger/ergebnis.py). */
    private fun basis(a: Aufnahme, text: JSONObject): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", a.ordner.name)
        .put("schritt", JSONObject.NULL)
        .put("fortschritt", 0.0)
        .put("meldung", JSONObject.NULL)
        .put("weiter_ab", JSONObject.NULL)
        .put("text_fertig", true)
        .put("kurz", JSONObject.NULL)
        .put("notizen", JSONArray())
        .put("sprecher", JSONArray())
        .put("abschnitte", text.optJSONArray("abschnitte") ?: JSONArray())
        .put("sprache", text.optString("sprache", "de"))
        .put("hinweise", Ablage.infoLesen(a.ordner)?.optJSONArray("hinweise") ?: JSONArray())
        .put("dauer_audio_s", text.optDouble("dauer_audio_s", 0.0))
        .put("auftrag_erledigt", Ergebnis.lesen(a.ordner)?.auftragErledigt ?: 0)
        .put("quelle", "handy")

    private fun ergebnisSchreiben(ordner: File, j: JSONObject) {
        j.put("aktualisiert", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date()))
        val tmp = File(ordner, "ergebnis.json.tmp")
        tmp.writeText(j.toString(1))
        tmp.renameTo(File(ordner, "ergebnis.json"))
    }

    // --- Schritt 2: Claude ---

    /** Rückgabe: true, wenn die Aufnahme fertig ausgewertet ist. */
    suspend fun claudeAuswerten(context: Context, a: Aufnahme): Boolean {
        val text = try { JSONObject(textDatei(a.ordner).readText()) } catch (_: Exception) { return false }
        val info = Ablage.infoLesen(a.ordner) ?: return false
        if (!Schluessel.vorhanden(context)) {
            anLaptop(a, text, "Kein Claude-Schlüssel am Handy, der Laptop schreibt die Notizen.")
            return false
        }
        val abschnitte = text.optJSONArray("abschnitte") ?: JSONArray()
        val liste = (0 until abschnitte.length()).map { abschnitte.getJSONObject(it) }.map {
            Auswertung.Abschnitt(it.optDouble("start"), it.optDouble("ende"), it.optString("text"))
        }
        val markierungen = info.optJSONArray("markierungen")?.let { m -> (0 until m.length()).map { m.getLong(it) } }.orEmpty()
        val fotos = Ablage.fotosLesen(a.ordner).map { (d, s) -> File(a.ordner, d) to s }
        try {
            val e = Auswertung.auswerten(
                context, a.modus.kennung, info.optString("start"), text.optDouble("dauer_audio_s", a.dauerMs / 1000.0),
                text.optString("sprache", "de"),
                info.optJSONArray("hinweise")?.let { h -> (0 until h.length()).map { h.getString(it) } }.orEmpty(),
                markierungen, fotos, AufgabenSpeicher.offene(context), liste,
            )
            val d = e.daten
            val j = basis(a, text)
                .put("status", "fertig").put("fortschritt", 1.0)
                .put("kurz", d.optString("kurz"))
                .put("bereich", d.optString("bereich"))
                .put("notizen", d.getJSONArray("notizen"))
                .put("wichtig", d.getJSONArray("wichtig"))
                .put("aufgaben", d.getJSONArray("aufgaben"))
                .put("vorlage_hinweis", d.opt("vorlage_hinweis") ?: JSONObject.NULL)
                .put("modell", de.braininger.app.claude.ClaudeAufruf.MODELL)
                .put("kosten_usd", e.dollar)
            ergebnisSchreiben(a.ordner, j)
            phaseSetzen(a.ordner, "hochladen")
            return true
        } catch (_: DeckelErreicht) {
            anLaptop(a, text, "Monatsdeckel für Claude erreicht, der Laptop schreibt die Notizen.")
            if (Kosten.deckelHinweisNoetig(context)) {
                Hinweise.zeigen(context, ID_DECKEL, "Claude-Deckel erreicht",
                    "Die 5 \$ für diesen Monat sind verbraucht. Neue Aufnahmen wertet der Laptop aus, sobald er läuft.")
            }
            return false
        } catch (e: ClaudeFehler) {
            anLaptop(a, text, (e.message ?: "Claude-Fehler") + " Der Laptop übernimmt.")
            return false
        }
    }

    /** Fehlversuche der Erkennung zählen; nach dem zweiten macht der Laptop alles (er erkennt den Text selbst). */
    fun erkennungFehlgeschlagen(a: Aufnahme, meldung: String) {
        val j = try { JSONObject(zustandDatei(a.ordner).readText()) } catch (_: Exception) { JSONObject() }
        val versuche = j.optInt("fehler", 0) + 1
        if (versuche < 2) {
            j.put("phase", phase(a.ordner) ?: "text").put("fehler", versuche).put("meldung", meldung)
            zustandDatei(a.ordner).writeText(j.toString(1))
            return
        }
        ergebnisSchreiben(a.ordner, JSONObject().put("version", 1).put("id", a.ordner.name).put("status", "hochgeladen")
            .put("auswertung", "laptop_ganz").put("text_fertig", false).put("quelle", "handy")
            .put("meldung", "Die Spracherkennung am Handy ging nicht ($meldung). Der Laptop übernimmt."))
        phaseSetzen(a.ordner, "laptop_hochladen", meldung)
    }

    /** Claude ging am Handy nicht: Text bleibt, der Laptop soll die Notizen schreiben (ergebnis.json „laptop_bitte“). */
    private fun anLaptop(a: Aufnahme, text: JSONObject, meldung: String) {
        ergebnisSchreiben(a.ordner, basis(a, text).put("status", "hochgeladen").put("auswertung", "laptop_bitte").put("meldung", meldung))
        phaseSetzen(a.ordner, "laptop_hochladen", meldung)
    }

    /** Lokales Ergebnis ins Drive (ergebnis.json, die die App beim Hochladen angelegt hat). */
    suspend fun hochladen(context: Context): List<Pair<File, Ergebnis>> {
        val fertig = mutableListOf<Pair<File, Ergebnis>>()
        var api: DriveApi? = null
        for (a in Ablage.alle(context)) {
            val p = phase(a.ordner)
            if (p != "hochladen" && p != "laptop_hochladen") continue
            val stand = UploadStand.lesen(a.ordner)
            val id = stand.ergebnisId ?: continue // noch nicht hochgeladen: beim nächsten Mal
            if (!stand.abgeschlossen) continue
            val inhalt = File(a.ordner, "ergebnis.json").readBytes()
            (api ?: DriveApi(context).also { api = it }).inhaltErsetzen(id, "application/json", inhalt)
            if (p == "hochladen") {
                phaseSetzen(a.ordner, "fertig")
                Ergebnis.lesen(a.ordner)?.let { e ->
                    fertig += a.ordner to e
                    if (e.vorschlaege.isNotEmpty()) {
                        AufgabenSpeicher.ausErgebnis(context, a.ordner.name,
                            e.vorschlaege.map { it.copy(id = "${a.ordner.name}#${it.id}", aufnahme = a.ordner.name) })
                    }
                }
            } else {
                phaseSetzen(a.ordner, "laptop")
            }
        }
        return fertig
    }

    // --- Anzeige während der Arbeit ---

    fun arbeitsHinweis(context: Context, text: String, prozent: Int?): android.app.Notification {
        val oeffnen = PendingIntent.getActivity(
            context, 21, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Braininger erkennt Text")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(oeffnen)
            .apply { if (prozent != null) setProgress(100, prozent.coerceIn(0, 100), false) }
            .build()
    }

    fun arbeitsHinweisId() = ID_ARBEIT

    /** Zustand für die Liste, solange das Handy an der Aufnahme arbeitet; sonst null. */
    fun statusText(context: Context, a: Aufnahme): String? {
        if (a.status == "laeuft" || !inArbeit(a.ordner)) return null
        return when {
            !textVorhanden(a.ordner) && !Modelle.bereit(context) -> "lädt Spracherkennung"
            !textVorhanden(a.ordner) -> if (akkuReicht(context)) "Handy erkennt Text" else "wartet aufs Laden"
            else -> "Claude schreibt"
        }
    }
}

/** Schritt 1 am Handy: Sprache → Text (ohne Netz). Läuft als Vordergrund-Dienst „mediaProcessing“ (bis 6 h am Tag). */
class ErkennungAuftrag(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val offen = HandyAuswertung.offeneAufnahmen(ctx).filter { !HandyAuswertung.textVorhanden(it.ordner) }
        if (offen.isEmpty()) return Result.success()
        if (!Modelle.bereit(ctx)) {
            Modelle.laden(ctx) // nach dem Laden stößt ModellLaden diese Arbeit wieder an
            return Result.success()
        }
        if (!HandyAuswertung.akkuReicht(ctx)) {
            HandyAuswertung.beimLadenAnstossen(ctx)
            return Result.success()
        }
        for (a in offen) {
            if (AufnahmeStatus.zustand.value.laeuft) return Result.retry() // nicht während einer Aufnahme rechnen
            if (!HandyAuswertung.akkuReicht(ctx)) {
                HandyAuswertung.beimLadenAnstossen(ctx)
                return Result.success()
            }
            try {
                setForeground(ForegroundInfo(
                    HandyAuswertung.arbeitsHinweisId(), HandyAuswertung.arbeitsHinweis(ctx, "Text wird erkannt …", 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                ))
            } catch (_: Exception) {
                // Android erlaubt den Vordergrund gerade nicht: trotzdem rechnen (Zwischenstände werden gesichert)
            }
            try {
                HandyAuswertung.textErkennen(ctx, a) { prozent ->
                    try {
                        ctx.getSystemService(NotificationManager::class.java).notify(
                            HandyAuswertung.arbeitsHinweisId(),
                            HandyAuswertung.arbeitsHinweis(ctx, "Text wird erkannt … $prozent %", prozent),
                        )
                    } catch (_: SecurityException) {
                    }
                }
            } catch (e: CancellationException) {
                Whisper.abbrechen()
                throw e
            } catch (_: ErkennungAbgebrochen) {
                return Result.retry()
            } catch (e: Exception) {
                HandyAuswertung.erkennungFehlgeschlagen(a, e.message ?: e.javaClass.simpleName)
                continue
            }
            HandyAuswertung.claudeAnstossen(ctx)
        }
        return Result.success()
    }
}

/** Schritt 2 am Handy: Notizen mit Claude, dann Ergebnis ins Drive. Braucht Netz. */
class ClaudeAuftrag(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            for (a in HandyAuswertung.offeneAufnahmen(ctx)) {
                if (HandyAuswertung.phase(a.ordner) == "claude" ||
                    (HandyAuswertung.phase(a.ordner) == null && HandyAuswertung.textVorhanden(a.ordner))
                ) {
                    HandyAuswertung.claudeAuswerten(ctx, a)
                }
            }
            val fertig = HandyAuswertung.hochladen(ctx)
            ErgebnisAbruf.benachrichtigen(ctx, fertig)
            de.braininger.app.drive.GoogleDocs.aktualisieren(ctx)
            Result.success()
        } catch (_: ClaudeSpaeter) {
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: de.braininger.app.drive.AnmeldungNoetig) {
            Result.success() // meldet der Upload
        }
    }
}
