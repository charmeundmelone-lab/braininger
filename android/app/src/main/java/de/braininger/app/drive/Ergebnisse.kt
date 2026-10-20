package de.braininger.app.drive

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.braininger.app.Ablage
import de.braininger.app.AufgabenAktion
import de.braininger.app.AufgabenSpeicher
import de.braininger.app.Vorschlag
import de.braininger.app.MainActivity
import de.braininger.app.Ordnung
import de.braininger.app.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Eine Notiz, die Claude am Laptop nach einer Vorlage geschrieben hat. */
data class Notiz(val vorlage: String, val titel: String, val inhalt: String)

data class TextAbschnitt(val start: Double, val sprecher: String?, val text: String)

/** Ein Sprecher im Gespräch. quelle: bestaetigt (vom Nutzer) | stimme (an der Stimme erkannt) | gespraech | leer | null */
data class SprecherInfo(val kennung: String, val name: String?, val quelle: String?, val beispiel: String, val sprechzeitS: Int) {
    val vermutet get() = name != null && quelle != "bestaetigt"
}

/**
 * Inhalt der ergebnis.json, die der Laptop an Ort und Stelle beschreibt (siehe laptop/braininger/ergebnis.py).
 * status: hochgeladen (noch unberührt) | in_arbeit | wartet | fertig | fehler
 */
data class Ergebnis(
    val status: String,
    val schritt: String?,
    val fortschritt: Double,
    val meldung: String?,
    val textFertig: Boolean,
    val kurz: String?,
    val notizen: List<Notiz>,
    val abschnitte: List<TextAbschnitt>,
    val sprecher: Map<String, String>,
    val sprecherDetails: List<SprecherInfo>,
    val bekannteNamen: List<String>,
    val auftragErledigt: Int,
    /** Aufgaben-Vorschläge des Laptops; id = Position, aufnahme leer (setzt der Abruf). */
    val vorschlaege: List<Vorschlag> = emptyList(),
    /** Standard-Vorlage genommen, aber diese passt laut Claude besser: Kennung und Grund. */
    val vorlageHinweis: Pair<String, String>? = null,
    /** handy = am Handy ausgewertet (Whisper + Claude-API), sonst Laptop. */
    val quelle: String? = null,
) {
    val endgueltig get() = status == "fertig" || status == "fehler"

    /** Kurzer Zustand für die Liste. */
    val anzeige: String
        get() = when (status) {
            "hochgeladen" -> "wartet auf Laptop"
            "in_arbeit" -> if (quelle == "handy") "Claude schreibt" else when (schritt) {
                "sprecher" -> "Laptop: Sprecher"
                "text" -> "Laptop: Text ${(fortschritt * 100).toInt()} %"
                "auswertung" -> "Laptop: Claude schreibt"
                else -> "Laptop arbeitet"
            }
            "wartet" -> "wartet"
            "fertig" -> "fertig"
            "fehler" -> "Fehler"
            else -> status
        }

    companion object {
        fun aus(text: String): Ergebnis? = try {
            val j = JSONObject(text)
            val notizen = j.optJSONArray("notizen")
            val abschnitte = j.optJSONArray("abschnitte")
            val sprecher = j.optJSONArray("sprecher")
            val details = j.optJSONArray("sprecher_details")
            val bekannte = j.optJSONArray("bekannte_namen")
            val aufgaben = j.optJSONArray("aufgaben")
            val hinweis = j.optJSONObject("vorlage_hinweis")
            Ergebnis(
                status = j.optString("status", "hochgeladen"),
                schritt = j.optString("schritt").takeUnless { j.isNull("schritt") || it.isBlank() },
                fortschritt = j.optDouble("fortschritt", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                meldung = j.optString("meldung").takeUnless { j.isNull("meldung") || it.isBlank() },
                textFertig = j.optBoolean("text_fertig"),
                kurz = j.optString("kurz").takeUnless { j.isNull("kurz") || it.isBlank() },
                notizen = (0 until (notizen?.length() ?: 0)).map {
                    val n = notizen!!.getJSONObject(it)
                    Notiz(n.optString("vorlage"), n.optString("titel"), n.optString("inhalt"))
                },
                abschnitte = (0 until (abschnitte?.length() ?: 0)).map {
                    val a = abschnitte!!.getJSONObject(it)
                    TextAbschnitt(
                        a.optDouble("start", 0.0),
                        a.optString("sprecher").takeUnless { a.isNull("sprecher") || it.isBlank() },
                        a.optString("text"),
                    )
                },
                sprecher = (0 until (sprecher?.length() ?: 0)).mapNotNull {
                    val s = sprecher!!.getJSONObject(it)
                    if (s.isNull("name") || s.optString("name").isBlank()) null else s.optString("kennung") to s.optString("name")
                }.toMap(),
                sprecherDetails = (0 until (details?.length() ?: 0)).map {
                    val d = details!!.getJSONObject(it)
                    SprecherInfo(
                        d.optString("kennung"),
                        d.optString("name").takeUnless { d.isNull("name") || it.isBlank() },
                        d.optString("quelle").takeUnless { d.isNull("quelle") || it.isBlank() },
                        d.optString("beispiel"),
                        d.optInt("sprechzeit_s", 0),
                    )
                },
                bekannteNamen = (0 until (bekannte?.length() ?: 0)).map { bekannte!!.getString(it) },
                auftragErledigt = j.optInt("auftrag_erledigt", 0),
                vorschlaege = (0 until (aufgaben?.length() ?: 0)).mapNotNull {
                    val v = aufgaben!!.optJSONObject(it) ?: return@mapNotNull null
                    Vorschlag("$it", "", v.optString("art", "neu"), v.optString("text"),
                        AufgabenSpeicher.zeitLesen(v.optString("frist")), v.optBoolean("unklar_wer"),
                        v.optString("bezug").ifBlank { null })
                }.filter { it.text.isNotBlank() },
                vorlageHinweis = hinweis?.optString("vorlage")?.takeIf { it.isNotBlank() }
                    ?.let { it to hinweis.optString("grund") },
                quelle = j.optString("quelle").takeUnless { j.isNull("quelle") || it.isBlank() },
            )
        } catch (_: Exception) {
            null
        }

        fun lesen(ordner: File): Ergebnis? =
            File(ordner, "ergebnis.json").takeIf { it.exists() }?.let { aus(it.readText()) }
    }
}

/** Die 8 Vorlagen, gleiche Kennungen wie laptop/braininger/vorlagen.py. */
object Vorlagen {
    val alle = linkedMapOf(
        "gedanken" to "Gedanken",
        "tagebuch" to "Tagebuch",
        "kundengespraech" to "Kundengespräch / Gig-Anfrage",
        "besprechung" to "Besprechungsprotokoll",
        "songidee" to "Song- / Ideen-Notiz",
        "aufgaben" to "Aufgabenliste",
        "gig_nachbereitung" to "Gig-Nachbereitung",
        "telefonat" to "Aktennotiz Telefonat",
        "haushalt" to "Haushalt & Familie",
    )

    fun name(kennung: String) = alle[kennung] ?: kennung
}

/**
 * Auftrag an den Laptop, z. B. „Notiz 2 mit anderer Vorlage neu schreiben“.
 * Liegt als auftrag.json im Aufnahme-Ordner. Die App legt sie einmal an und ersetzt danach nur den Inhalt,
 * die Nummer steigt mit jedem Auftrag. Der Laptop meldet die erledigte Nummer in ergebnis.json zurück.
 */
object Auftraege {
    private fun datei(ordner: File) = File(ordner, "auftrag.json")

    fun offen(ordner: File): JSONObject? {
        val j = try { JSONObject(datei(ordner).readText()) } catch (_: Exception) { return null }
        val erledigt = Ergebnis.lesen(ordner)?.auftragErledigt ?: 0
        return j.takeIf { it.optInt("nummer") > erledigt }
    }

    suspend fun vorlageAendern(context: Context, ordner: File, notiz: Int, vorlage: String) =
        senden(context, ordner, JSONObject().put("art", "vorlage_aendern").put("notiz", notiz).put("vorlage", vorlage))

    /** name leer = Name entfernen. */
    suspend fun sprecherBenennen(context: Context, ordner: File, kennung: String, name: String) =
        senden(context, ordner, JSONObject().put("art", "sprecher_benennen").put("kennung", kennung).put("name", name.trim()))

    private suspend fun senden(context: Context, ordner: File, j: JSONObject) {
        val stand = UploadStand.lesen(ordner)
        val ordnerId = stand.ordnerId ?: throw IOException("Aufnahme ist noch nicht im Drive.")
        val bisher = try { JSONObject(datei(ordner).readText()).optInt("nummer") } catch (_: Exception) { 0 }
        val erledigt = Ergebnis.lesen(ordner)?.auftragErledigt ?: 0
        j.put("nummer", maxOf(bisher, erledigt) + 1)
        val inhalt = j.toString(1).toByteArray()
        val api = DriveApi(context)
        val id = stand.auftragId ?: api.dateiFinden("auftrag.json", ordnerId)?.id
        stand.auftragId = if (id != null) {
            api.inhaltErsetzen(id, "application/json", inhalt); id
        } else {
            api.hochladen("auftrag.json", ordnerId, "application/json", inhalt)
        }
        stand.speichern(ordner)
        datei(ordner).writeBytes(inhalt)
    }
}

/** Holt die ergebnis.json aller Aufnahmen, die noch nicht endgültig fertig sind (oder einen offenen Auftrag haben). */
object ErgebnisAbruf {
    /** Rückgabe: Aufnahmen, die seit dem letzten Abruf fertig geworden sind. */
    suspend fun abrufen(context: Context): List<Pair<File, Ergebnis>> {
        val api = DriveApi(context)
        val neuFertig = mutableListOf<Pair<File, Ergebnis>>()
        // Eine Anfrage für alle: wann wurde welche ergebnis.json zuletzt geändert (z. B. Neuauswertung am Laptop)
        val geaendert = try {
            api.suchen("name = 'ergebnis.json' and trashed = false").associate { it.id to it.geaendert }
        } catch (e: DriveFehler) {
            emptyMap()
        }
        for (a in Ablage.alle(context)) {
            val stand = UploadStand.lesen(a.ordner)
            val id = stand.ergebnisId ?: continue
            if (!stand.abgeschlossen) continue
            // Am Handy ausgewertet und noch nicht fertig im Drive: lokale Fassung ist die richtige
            if (!de.braininger.app.erkennung.HandyAuswertung.abrufErlaubt(a.ordner)) continue
            val alt = Ergebnis.lesen(a.ordner)
            val zeitDatei = File(a.ordner, "ergebnis_geaendert.txt")
            val bekannt = zeitDatei.takeIf { it.exists() }?.readText()
            val neuImDrive = geaendert[id]?.let { it.isNotBlank() && it != bekannt } ?: false
            if (alt != null && alt.endgueltig && Auftraege.offen(a.ordner) == null && !neuImDrive) continue

            val inhalt = try {
                api.herunterladen(id)
            } catch (e: DriveFehler) {
                if (e.code == 404) continue else throw e // Datei im Drive gelöscht: überspringen
            }
            val neu = Ergebnis.aus(inhalt.decodeToString()) ?: continue
            val tmp = File(a.ordner, "ergebnis.json.tmp")
            tmp.writeBytes(inhalt)
            tmp.renameTo(File(a.ordner, "ergebnis.json"))
            geaendert[id]?.let { zeitDatei.writeText(it) }
            if (neu.endgueltig && alt?.status != neu.status) neuFertig += a.ordner to neu
            if (neu.status == "fertig" && neu.vorschlaege.isNotEmpty()) {
                AufgabenSpeicher.ausErgebnis(context, a.ordner.name,
                    neu.vorschlaege.map { it.copy(id = "${a.ordner.name}#${it.id}", aufnahme = a.ordner.name) })
            }
        }
        return neuFertig
    }

    /** alle: Aufnahmen, die gerade fertig oder mit Fehler beendet wurden. */
    fun benachrichtigen(context: Context, alle: List<Pair<File, Ergebnis>>) {
        val fehler = alle.filter { it.second.status == "fehler" }
        if (fehler.isNotEmpty()) {
            Hinweise.zeigen(context, 9, "Fehler am Laptop",
                fehler.first().second.meldung ?: "Eine Aufnahme ließ sich nicht auswerten. Tippen zum Ansehen.")
        }
        val fertig = alle.filter { it.second.status == "fertig" }
        if (fertig.isEmpty()) return
        val aufnahmen = fertig.map { it.first.name }.toSet()
        val vorschlaege = AufgabenSpeicher.vorschlaege(context).filter { it.aufnahme in aufnahmen }
        val text = (if (fertig.size == 1) fertig[0].second.kurz ?: "Die Notiz ist fertig."
                   else "${fertig.size} Aufnahmen sind ausgewertet.") +
            vorschlaege.joinToString("") { "\n• " + it.text + (it.frist?.let { f -> " (" + AufgabenSpeicher.fristText(f) + ")" } ?: "") }
        val titel = when (vorschlaege.size) {
            0 -> "Ergebnis fertig"
            1 -> "Ergebnis fertig · 1 Aufgabe"
            else -> "Ergebnis fertig · ${vorschlaege.size} Aufgaben"
        }
        val oeffnen = PendingIntent.getActivity(
            context, 6, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .apply {
                if (vorschlaege.isNotEmpty()) {
                    val alle = PendingIntent.getBroadcast(
                        context, 5,
                        Intent(context, AufgabenAktion::class.java).setAction(AufgabenAktion.UEBERNEHMEN)
                            .putExtra(AufgabenAktion.EXTRA_VORSCHLAEGE, vorschlaege.map { it.id }.toTypedArray())
                            .putExtra(AufgabenAktion.EXTRA_MELDUNG, 4),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, "ALLE ÜBERNEHMEN", alle)
                    addAction(0, "ANSEHEN", oeffnen)
                }
            }
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(4, n)
        } catch (_: SecurityException) {
        }
    }
}

/**
 * Löscht die Audio-Stücke einer Aufnahme im Drive und auf dem Handy, frühestens 3 Tage nach ihrem Ende und nur,
 * wenn der Laptop gemeldet hat, dass der Text gesichert ist (text_fertig). info.json und ergebnis.json bleiben,
 * damit Notizen und Text in der App sichtbar bleiben.
 */
object AudioAufraeumen {
    private const val FRIST_MS = 3 * 24 * 3_600_000L

    suspend fun aufraeumen(context: Context) {
        val api = DriveApi(context)
        val jetzt = System.currentTimeMillis()
        for (a in Ablage.alle(context)) {
            val ende = a.ende ?: continue
            if (jetzt - ende < FRIST_MS) continue
            val stand = UploadStand.lesen(a.ordner)
            if (!stand.abgeschlossen || stand.audioGeloescht || stand.dateien.isEmpty()) continue
            if (Ergebnis.lesen(a.ordner)?.textFertig != true) continue
            // nur Ton: Fotos bleiben im Drive
            for ((name, id) in stand.dateien) if (name.endsWith(".m4a")) api.loeschen(id)
            stand.audioGeloescht = true
            stand.speichern(a.ordner)
            a.ordner.listFiles { f -> f.name.endsWith(".m4a") }.orEmpty().forEach { it.delete() }
        }
    }
}

/**
 * Nach 30 Tagen im Papierkorb ist eine Aufnahme überall weg. Die App löscht ihren Eingang-Ordner im Drive
 * endgültig, mit allem darin und ohne Google-Papierkorb (Drive API files.delete: bei einem Ordner auch alles darin,
 * was dem Nutzer gehört), dazu die Tagesübersicht, wenn vom Tag nichts mehr übrig ist, und den Ordner auf dem Handy.
 * In ordnung.json steht sie danach als "weg": Daran erkennt der Laptop, dass er seine Kopie löschen soll.
 */
object EndgueltigLoeschen {
    suspend fun aufraeumen(context: Context) {
        val faellig = Ordnung.faellig(context)
        if (faellig.isEmpty()) return
        val api = DriveApi(context)
        val alle = Ablage.alle(context).associateBy { it.ordner.name }
        val erledigt = mutableListOf<String>()
        try {
            for (id in faellig) {
                alle[id]?.let { a ->
                    UploadStand.lesen(a.ordner).ordnerId?.let { api.loeschen(it) }
                    a.ordner.deleteRecursively()
                }
                erledigt += id
            }
        } finally {
            // was schon gelöscht ist, gilt als weg, auch wenn danach ein Fehler kam (der Rest beim nächsten Mal)
            Ordnung.alsWeg(context, erledigt)
        }
        val uebrig = Ablage.alle(context).map { it.ordner.name.take(10) }.toSet()
        for (tag in erledigt.map { it.take(10) }.toSet() - uebrig) Uebersichten.tagEntfernen(context, api, tag)
    }
}

/** Schaut alle 15 Minuten (kürzer erlaubt Android nicht) nach neuen Ergebnissen, auch wenn die App zu ist. */
class ErgebnisAuftrag(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val NAME = "braininger-ergebnisse"

        fun planen(context: Context) {
            val auftrag = PeriodicWorkRequestBuilder<ErgebnisAuftrag>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, auftrag)
        }
    }

    override suspend fun doWork(): Result = try {
        de.braininger.app.TastenWache.pruefen(applicationContext) // braucht kein Netz, deshalb zuerst
        ErgebnisAbruf.benachrichtigen(applicationContext, ErgebnisAbruf.abrufen(applicationContext))
        // Handy-Auswertung: liegengebliebenes ins Drive, Wartendes wieder anstoßen
        nebenbei {
            ErgebnisAbruf.benachrichtigen(applicationContext, de.braininger.app.erkennung.HandyAuswertung.hochladen(applicationContext))
            if (de.braininger.app.erkennung.HandyAuswertung.offeneAufnahmen(applicationContext).isNotEmpty()) {
                de.braininger.app.erkennung.HandyAuswertung.anstossen(applicationContext)
            }
        }
        AudioAufraeumen.aufraeumen(applicationContext)
        WarteHinweis.pruefen(applicationContext)
        // Zusatzteile: ein Fehler hier darf die übrigen nicht aufhalten
        nebenbei { Uebersichten.abrufen(applicationContext) }
        nebenbei { if (Fragen.abrufen(applicationContext)) Fragen.benachrichtigen(applicationContext) }
        nebenbei { GoogleDocs.aktualisieren(applicationContext) }
        nebenbei { EndgueltigLoeschen.aufraeumen(applicationContext) }
        Result.success()
    } catch (_: AnmeldungNoetig) {
        Result.success() // Anmeldung meldet schon der Upload
    } catch (_: IOException) {
        Result.retry()
    }

    private suspend fun nebenbei(teil: suspend () -> Unit) {
        try {
            teil()
        } catch (e: AnmeldungNoetig) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // nächster Durchlauf in 15 Minuten
        }
    }
}
