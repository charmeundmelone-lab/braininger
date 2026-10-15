package de.braininger.app.drive

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.braininger.app.MainActivity
import de.braininger.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Quelle(val id: String, val datum: String, val kurz: String?)

/** Antwort des Laptops auf eine Frage (siehe laptop/braininger/fragen.py). */
data class Antwort(
    val nummer: Int,
    val frage: String,
    val status: String,
    val antwort: String?,
    val quellen: List<Quelle>,
    val meldung: String?,
) {
    val fertig get() = status == "fertig" || status == "fehler"

    fun alsJson(): JSONObject = JSONObject()
        .put("nummer", nummer).put("frage", frage).put("status", status).put("antwort", antwort ?: JSONObject.NULL)
        .put("meldung", meldung ?: JSONObject.NULL)
        .put("quellen", JSONArray(quellen.map { JSONObject().put("id", it.id).put("datum", it.datum).put("kurz", it.kurz ?: "") }))

    companion object {
        fun aus(j: JSONObject): Antwort {
            fun s(n: String) = j.optString(n).takeUnless { j.isNull(n) || it.isBlank() }
            val q = j.optJSONArray("quellen")
            return Antwort(
                j.optInt("nummer"), j.optString("frage"), j.optString("status", "offen"), s("antwort"),
                (0 until (q?.length() ?: 0)).map {
                    val o = q!!.getJSONObject(it)
                    Quelle(o.optString("id"), o.optString("datum"), o.optString("kurz").takeIf { k -> k.isNotBlank() })
                },
                s("meldung"),
            )
        }
    }
}

/**
 * Fragen an alle Aufnahmen. Die App legt Braininger/Fragen/frage.json und antwort.json an (drive.file),
 * schreibt die Frage mit steigender Nummer in frage.json, der Laptop beantwortet sie in antwort.json.
 * Lokal: files/fragen/stand.json (Drive-IDs, letzte Nummer) und verlauf.json (beantwortete Fragen, neueste zuerst).
 */
object Fragen {
    private const val VORLAGE = "{\"version\": 1, \"nummer\": 0, \"status\": \"offen\"}\n"

    private fun ordner(context: Context) = File(context.filesDir, "fragen").apply { mkdirs() }
    private fun datei(context: Context, name: String) = File(ordner(context), name)

    private fun lesen(context: Context, name: String): JSONObject? =
        try { JSONObject(datei(context, name).readText()) } catch (_: Exception) { null }

    private fun schreiben(context: Context, name: String, inhalt: String) {
        val tmp = datei(context, "$name.tmp")
        tmp.writeText(inhalt)
        tmp.renameTo(datei(context, name))
    }

    /** Die gerade gestellte Frage mit dem letzten Stand (oder null, wenn keine offen ist). */
    fun aktuell(context: Context): Antwort? = lesen(context, "aktuell.json")?.let { Antwort.aus(it) }

    fun verlauf(context: Context): List<Antwort> {
        val a = try { JSONArray(datei(context, "verlauf.json").readText()) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { Antwort.aus(a.getJSONObject(it)) }
    }

    suspend fun stellen(context: Context, frage: String) {
        val api = DriveApi(context)
        val s = lesen(context, "stand.json") ?: JSONObject()
        val ordnerId = s.optString("ordner_id").ifBlank {
            api.ordnerSichern("Fragen", api.ordnerSichern("Braininger", null)).also { s.put("ordner_id", it) }
        }
        // antwort.json zuerst anlegen, damit der Laptop sie beschreiben kann, sobald die Frage da ist
        if (s.optString("antwort_id").isBlank()) {
            val id = api.dateiFinden("antwort.json", ordnerId)?.id
                ?: api.hochladen("antwort.json", ordnerId, "application/json", VORLAGE.toByteArray())
            s.put("antwort_id", id)
        }
        val nummer = s.optInt("nummer") + 1
        val inhalt = JSONObject().put("nummer", nummer).put("frage", frage.trim()).toString(1).toByteArray()
        val frageId = s.optString("frage_id").ifBlank { null } ?: api.dateiFinden("frage.json", ordnerId)?.id
        s.put("frage_id", if (frageId != null) {
            api.inhaltErsetzen(frageId, "application/json", inhalt); frageId
        } else {
            api.hochladen("frage.json", ordnerId, "application/json", inhalt)
        })
        s.put("nummer", nummer)
        schreiben(context, "stand.json", s.toString(1))
        schreiben(context, "aktuell.json", Antwort(nummer, frage.trim(), "gesendet", null, emptyList(), null).alsJson().toString(1))
    }

    fun benachrichtigen(context: Context) {
        val a = aktuell(context) ?: return
        val oeffnen = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Antwort auf deine Frage")
            .setContentText(a.frage)
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(8, n)
        } catch (_: SecurityException) {
        }
    }

    /** Holt die Antwort, solange eine Frage offen ist. Rückgabe: true, wenn sie gerade fertig geworden ist. */
    suspend fun abrufen(context: Context): Boolean {
        val offen = aktuell(context) ?: return false
        if (offen.fertig) return false
        val id = lesen(context, "stand.json")?.optString("antwort_id")?.ifBlank { null } ?: return false
        val j = JSONObject(DriveApi(context).herunterladen(id).decodeToString())
        if (j.optInt("nummer") != offen.nummer) return false // Laptop hat die Frage noch nicht angefangen
        val neu = Antwort.aus(j)
        schreiben(context, "aktuell.json", neu.alsJson().toString(1))
        if (!neu.fertig) return false
        val v = JSONArray().put(neu.alsJson())
        verlauf(context).take(49).forEach { v.put(it.alsJson()) }
        schreiben(context, "verlauf.json", v.toString(1))
        return true
    }
}
