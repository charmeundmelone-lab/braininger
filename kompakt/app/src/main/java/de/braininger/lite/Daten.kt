package de.braininger.lite

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eine Aufnahme mit allem, was die Seiten brauchen. */
class Eintrag(val ordner: File, val info: JSONObject, val roh: JSONObject?) {
    val id: String get() = ordner.name
    val erg: JSONObject? get() = roh?.takeIf { it.optString("status") == "fertig" }
    val start: Date? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(info.optString("start"))
    } catch (_: Exception) { null }
    val dauer: Long get() = info.optLong("dauer_s", 0)
    val laeuft: Boolean get() = info.optString("status") == "laeuft"
    val kurz: String? get() = erg?.optString("kurz")?.takeIf { it.isNotBlank() && it != "null" }

    /** Etwas zum Merken drin? Sonst ist es eine leere Aufnahme (z. B. nur Rauschen). */
    val hatInhalt: Boolean
        get() {
            val e = erg ?: return false
            val notiz = e.optJSONArray("notizen")?.optJSONObject(0)?.optString("titel").orEmpty()
            return (notiz.isNotBlank() && !notiz.startsWith("Kein verwertbarer")) ||
                (e.optJSONArray("aufgaben")?.length() ?: 0) > 0 || (e.optJSONArray("einkauf")?.length() ?: 0) > 0
        }

    val titel: String
        get() {
            if (laeuft) return "Aufnahme läuft"
            val e = erg ?: return "Gedanke"
            if (!hatInhalt) return "Ohne Inhalt"
            val notiz = e.optJSONArray("notizen")?.optJSONObject(0)?.optString("titel").orEmpty()
            if (notiz.isNotBlank() && !notiz.startsWith("Kein verwertbarer")) return notiz
            return kurz ?: "Merker"
        }

    /** Stand beim Laptop, null wenn fertig. */
    val stand: String?
        get() = when {
            laeuft -> null
            !Lager.gesendet(ordner) -> "wartet auf den Laptop"
            roh == null -> "beim Laptop"
            roh.optString("status") == "fertig" -> null
            roh.optString("status") == "fehler" -> "Fehler beim Laptop"
            roh.optString("status") == "wartet" -> "Laptop wartet"
            else -> "Laptop arbeitet"
        }

    fun wann(): String = start?.let { SimpleDateFormat("EE d.M. · HH:mm", Locale.GERMANY).format(it) } ?: id
    fun dauerText(): String = when {
        dauer >= 60 -> "${dauer / 60} Min"
        dauer > 0 -> "$dauer s"
        else -> ""
    }
}

class Aufgabe(
    val schluessel: String, val text: String, val frist: Date?, val erledigt: Boolean,
    val bezug: String, val quelle: Eintrag,
)
class Sache(val schluessel: String, val sache: String, val details: String, val abteilung: Int, val abgehakt: Boolean)

/**
 * Sammelt Aufgaben und Einkäufe aus allen Ergebnissen und merkt sich, was Jan am Kompakt abgehakt hat.
 * Abgehakte Aufgaben bleiben bis Mitternacht durchgestrichen sichtbar (wie in der großen App), dann sind sie weg.
 * Abgehakte Einkäufe bleiben durchgestrichen, bis „Liste leeren“.
 */
object Daten {
    private const val PREFS = "lite_haken"

    // gleiche Reihenfolge wie im Laden (Grill Einkauf 2.11.2026)
    val ABTEILUNGEN = listOf(
        "Obst & Gemüse", "Brot", "Kühlregal", "Fleisch & Fisch", "Vorrat", "Süßes & Knabbern",
        "Getränke", "Tiefkühl", "Drogerie & Haushalt", "Sonstiges",
    )

    fun eintraege(c: Context): List<Eintrag> = Lager.aufnahmen(c).mapNotNull { o ->
        Lager.json(File(o, "info.json"))?.let { Eintrag(o, it, Lager.json(File(o, "ergebnis.json"))) }
    }

    private fun heute() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    fun aufgaben(c: Context, alle: List<Eintrag>): List<Aufgabe> {
        val p = c.getSharedPreferences(PREFS, 0)
        val liste = mutableListOf<Aufgabe>()
        for (e in alle) {
            val a = e.erg?.optJSONArray("aufgaben") ?: continue
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                if (x.optString("art") == "erledigt") continue
                val s = "a:${e.id}:$i"
                val erledigtAm = p.getString(s, null)
                if (erledigtAm != null && erledigtAm != heute()) continue
                val frist = x.optString("frist").takeIf { it.length >= 10 }?.let {
                    try {
                        SimpleDateFormat(if (it.length >= 16) "yyyy-MM-dd'T'HH:mm" else "yyyy-MM-dd", Locale.ROOT).parse(it.take(16))
                    } catch (_: Exception) { null }
                }
                val bezug = x.optString("bezug").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                liste += Aufgabe(s, x.optString("text"), frist, erledigtAm != null, bezug, e)
            }
        }
        return liste.sortedWith(compareBy<Aufgabe>({ it.erledigt }, { it.frist == null }, { it.frist }))
    }

    fun aufgabeUmschalten(c: Context, a: Aufgabe) {
        c.getSharedPreferences(PREFS, 0).edit().apply {
            if (a.erledigt) remove(a.schluessel) else putString(a.schluessel, heute())
        }.apply()
    }

    fun einkauf(c: Context, alle: List<Eintrag>): List<Sache> {
        val p = c.getSharedPreferences(PREFS, 0)
        val liste = mutableListOf<Sache>()
        val schonDa = mutableSetOf<String>()
        for (e in alle) {
            val a = e.erg?.optJSONArray("einkauf") ?: continue
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val s = "e:${e.id}:$i"
                val name = x.optString("sache").trim()
                // die neueste Nennung zählt (Liste ist neu → alt); ältere gleiche Sachen fallen weg
                if (name.isBlank() || !schonDa.add(name.lowercase(Locale.GERMANY))) continue
                val zustand = p.getString(s, null)
                if (zustand == "weg") continue
                val details = listOf(x.optString("menge"), x.optString("details"), x.optString("wer_holt").let { if (it.isNotBlank()) "$it holt" else "" })
                    .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
                val abt = ABTEILUNGEN.indexOf(x.optString("abteilung")).let { if (it < 0) ABTEILUNGEN.size else it }
                liste += Sache(s, name, details, abt, zustand == "ja")
            }
        }
        return liste.sortedWith(compareBy<Sache>({ it.abgehakt }, { it.abteilung }))
    }

    fun sacheUmschalten(c: Context, s: Sache) {
        c.getSharedPreferences(PREFS, 0).edit().apply {
            if (s.abgehakt) remove(s.schluessel) else putString(s.schluessel, "ja")
        }.apply()
    }

    fun abgehakteWeg(c: Context, alle: List<Sache>) {
        c.getSharedPreferences(PREFS, 0).edit().apply {
            alle.filter { it.abgehakt }.forEach { putString(it.schluessel, "weg") }
        }.apply()
    }
}
