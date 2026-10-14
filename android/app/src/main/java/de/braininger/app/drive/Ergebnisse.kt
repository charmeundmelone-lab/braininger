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
import de.braininger.app.MainActivity
import de.braininger.app.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Eine Notiz, die Claude am Laptop nach einer Vorlage geschrieben hat. */
data class Notiz(val vorlage: String, val titel: String, val inhalt: String)

data class TextAbschnitt(val start: Double, val sprecher: String?, val text: String)

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
    val auftragErledigt: Int,
) {
    val endgueltig get() = status == "fertig" || status == "fehler"

    /** Kurzer Zustand für die Liste. */
    val anzeige: String
        get() = when (status) {
            "hochgeladen" -> "wartet auf Laptop"
            "in_arbeit" -> when (schritt) {
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
                sprecher = (0 until (sprecher?.length() ?: 0)).associate {
                    val s = sprecher!!.getJSONObject(it)
                    s.optString("kennung") to s.optString("name")
                },
                auftragErledigt = j.optInt("auftrag_erledigt", 0),
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

    suspend fun vorlageAendern(context: Context, ordner: File, notiz: Int, vorlage: String) {
        val stand = UploadStand.lesen(ordner)
        val ordnerId = stand.ordnerId ?: throw IOException("Aufnahme ist noch nicht im Drive.")
        val bisher = try { JSONObject(datei(ordner).readText()).optInt("nummer") } catch (_: Exception) { 0 }
        val erledigt = Ergebnis.lesen(ordner)?.auftragErledigt ?: 0
        val j = JSONObject()
            .put("nummer", maxOf(bisher, erledigt) + 1)
            .put("art", "vorlage_aendern")
            .put("notiz", notiz)
            .put("vorlage", vorlage)
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
        for (a in Ablage.alle(context)) {
            val stand = UploadStand.lesen(a.ordner)
            val id = stand.ergebnisId ?: continue
            if (!stand.abgeschlossen) continue
            val alt = Ergebnis.lesen(a.ordner)
            if (alt != null && alt.endgueltig && Auftraege.offen(a.ordner) == null) continue

            val inhalt = try {
                api.herunterladen(id)
            } catch (e: DriveFehler) {
                if (e.code == 404) continue else throw e // Datei im Drive gelöscht: überspringen
            }
            val neu = Ergebnis.aus(inhalt.decodeToString()) ?: continue
            val tmp = File(a.ordner, "ergebnis.json.tmp")
            tmp.writeBytes(inhalt)
            tmp.renameTo(File(a.ordner, "ergebnis.json"))
            if (neu.status == "fertig" && alt?.status != "fertig") neuFertig += a.ordner to neu
        }
        return neuFertig
    }

    fun benachrichtigen(context: Context, fertig: List<Pair<File, Ergebnis>>) {
        if (fertig.isEmpty()) return
        val text = if (fertig.size == 1) fertig[0].second.kurz ?: "Die Notiz ist fertig."
                   else "${fertig.size} Aufnahmen sind ausgewertet."
        val oeffnen = PendingIntent.getActivity(
            context, 6, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Ergebnis fertig")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(4, n)
        } catch (_: SecurityException) {
        }
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
        ErgebnisAbruf.benachrichtigen(applicationContext, ErgebnisAbruf.abrufen(applicationContext))
        Result.success()
    } catch (_: AnmeldungNoetig) {
        Result.success() // Anmeldung meldet schon der Upload
    } catch (_: IOException) {
        Result.retry()
    }
}
