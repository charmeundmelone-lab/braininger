package de.braininger.app.drive

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eine Tagesübersicht, die der Laptop in Braininger/Uebersichten/<tag>.json schreibt. */
data class Uebersicht(
    val tag: String,
    val status: String,
    val titel: String?,
    val inhalt: String?,
    val meldung: String?,
    val aufnahmen: Int,
    val ids: List<String> = emptyList(),
) {
    val anzeige: String
        get() = when (status) {
            "offen" -> "kommt morgen früh"
            "in_arbeit" -> "Laptop schreibt …"
            "wartet" -> "wartet"
            "fehler" -> "Fehler"
            else -> ""
        }

    companion object {
        fun aus(tag: String, text: String): Uebersicht? = try {
            val j = JSONObject(text)
            fun s(name: String) = j.optString(name).takeUnless { j.isNull(name) || it.isBlank() }
            val ids = j.optJSONArray("aufnahmen")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Uebersicht(tag, j.optString("status", "offen"), s("titel"), s("inhalt"), s("meldung"), ids.size, ids)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Die App legt für jeden Tag mit Aufnahmen eine leere <tag>.json an (drive.file: nur so kann sie die Datei
 * später lesen) und bittet über Uebersichten/auftrag.json um eine sofortige Übersicht.
 * Lokal: files/uebersichten/<tag>.json (letzter Stand) und stand.json (Drive-IDs, Auftragsnummer).
 */
object Uebersichten {
    private const val VORLAGE = "{\"version\": 1, \"status\": \"offen\"}\n"

    private fun ordner(context: Context) = File(context.filesDir, "uebersichten").apply { mkdirs() }
    private fun standDatei(context: Context) = File(ordner(context), "stand.json")

    private fun stand(context: Context): JSONObject =
        try { JSONObject(standDatei(context).readText()) } catch (_: Exception) { JSONObject() }

    private fun speichern(context: Context, j: JSONObject) {
        val tmp = File(ordner(context), "stand.json.tmp")
        tmp.writeText(j.toString(1))
        tmp.renameTo(standDatei(context))
    }

    fun heute(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    /** Stellt sicher, dass Braininger/Uebersichten/<tag>.json im Drive existiert. */
    suspend fun tagSichern(context: Context, api: DriveApi, tag: String): String {
        val s = stand(context)
        val ids = s.optJSONObject("tage") ?: JSONObject().also { s.put("tage", it) }
        ids.optString(tag).takeIf { it.isNotBlank() }?.let { return it }
        val ordnerId = s.optString("ordner_id").ifBlank {
            api.ordnerSichern("Uebersichten", api.ordnerSichern("Braininger", null)).also { s.put("ordner_id", it) }
        }
        val id = api.dateiFinden("$tag.json", ordnerId)?.id
            ?: api.hochladen("$tag.json", ordnerId, "application/json", VORLAGE.toByteArray())
        ids.put(tag, id)
        speichern(context, s)
        return id
    }

    /** Vom Tag ist nichts mehr übrig (alles endgültig gelöscht): Übersicht im Drive und auf dem Handy entfernen. */
    suspend fun tagEntfernen(context: Context, api: DriveApi, tag: String) {
        val s = stand(context)
        val ids = s.optJSONObject("tage")
        ids?.optString(tag)?.takeIf { it.isNotBlank() }?.let { api.loeschen(it) }
        ids?.remove(tag)
        speichern(context, s)
        File(ordner(context), "$tag.json").delete()
    }

    fun auftragOffen(context: Context): Boolean {
        val s = stand(context)
        val nummer = s.optInt("auftrag_nummer")
        if (nummer == 0) return false
        val tag = s.optString("auftrag_tag")
        val erledigt = try {
            JSONObject(File(ordner(context), "$tag.json").readText()).optInt("auftrag_erledigt")
        } catch (_: Exception) {
            0
        }
        return erledigt < nummer
    }

    /** Bittet den Laptop um die Übersicht für heute (bisherige Aufnahmen). */
    suspend fun jetztErstellen(context: Context) {
        val api = DriveApi(context)
        val tag = heute()
        tagSichern(context, api, tag)
        val s = stand(context)
        val nummer = s.optInt("auftrag_nummer") + 1
        val inhalt = JSONObject().put("nummer", nummer).put("datum", tag).toString(1).toByteArray()
        val ordnerId = s.getString("ordner_id")
        val id = s.optString("auftrag_id").ifBlank { null } ?: api.dateiFinden("auftrag.json", ordnerId)?.id
        val neueId = if (id != null) {
            api.inhaltErsetzen(id, "application/json", inhalt); id
        } else {
            api.hochladen("auftrag.json", ordnerId, "application/json", inhalt)
        }
        s.put("auftrag_id", neueId).put("auftrag_nummer", nummer).put("auftrag_tag", tag)
        speichern(context, s)
    }

    /** Holt die Übersichten der letzten Tage, die noch nicht fertig sind oder sich noch ändern können. */
    suspend fun abrufen(context: Context) {
        val api = DriveApi(context)
        val s = stand(context)
        val ids = s.optJSONObject("tage") ?: return
        val grenze = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(System.currentTimeMillis() - 3 * 86_400_000L))
        for (tag in ids.keys().asSequence().toList()) {
            // Ältere Tage ändern sich nicht mehr, sobald sie fertig sind (außer nach Löschen oder Zurückholen)
            val bisher = lesen(context, tag)
            if (tag < grenze && bisher?.status == "fertig" && !veraltet(context, bisher)) continue
            val inhalt = try {
                api.herunterladen(ids.getString(tag))
            } catch (e: DriveFehler) {
                if (e.code == 404) continue else throw e
            }
            if (Uebersicht.aus(tag, inhalt.decodeToString()) == null) continue
            val tmp = File(ordner(context), "$tag.json.tmp")
            tmp.writeBytes(inhalt)
            tmp.renameTo(File(ordner(context), "$tag.json"))
        }
    }

    /** Fertige, nicht gelöschte Aufnahmen eines Tages auf dem Handy. */
    private fun sichtbareFertige(context: Context, tag: String): Set<String> {
        val papierkorb = de.braininger.app.Ordnung.papierkorb(context)
        return de.braininger.app.Ablage.alle(context)
            .filter { it.ordner.name.startsWith(tag) && it.ordner.name !in papierkorb }
            .filter { Ergebnis.lesen(it.ordner)?.status == "fertig" }
            .map { it.ordner.name }.toSet()
    }

    /** Die Übersicht enthält Gelöschte oder es fehlen zurückgeholte Aufnahmen: der Laptop schreibt sie neu. */
    private fun veraltet(context: Context, u: Uebersicht): Boolean = u.ids.toSet() != sichtbareFertige(context, u.tag)

    /** Tage, an denen alle Aufnahmen im Papierkorb liegen, zeigt die App nicht. */
    fun sichtbar(context: Context): List<Uebersicht> {
        val papierkorb = de.braininger.app.Ordnung.papierkorb(context)
        val aufnahmen = de.braininger.app.Ablage.alle(context).map { it.ordner.name }
        return alle(context).filter { u ->
            val tagesIds = aufnahmen.filter { it.startsWith(u.tag) }
            tagesIds.isEmpty() || tagesIds.any { it !in papierkorb }
        }
    }

    fun lesen(context: Context, tag: String): Uebersicht? =
        File(ordner(context), "$tag.json").takeIf { it.exists() }?.let { Uebersicht.aus(tag, it.readText()) }

    fun alle(context: Context): List<Uebersicht> =
        ordner(context).listFiles { f -> f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.json")) }.orEmpty()
            .mapNotNull { lesen(context, it.name.removeSuffix(".json")) }
            .sortedByDescending { it.tag }
}
