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
    /** Mit „Weitersprechen“ aufgenommen: ID des Gedankens, zu dem das ein Nachtrag ist (siehe Daten.nachtragsZiel). */
    val nachtragZu: String? = info.optString("nachtrag_zu").takeIf { it.isNotBlank() }
    val kurz: String? get() = erg?.optString("kurz")?.takeIf { it.isNotBlank() && it != "null" }

    /** Etwas zum Merken drin? Sonst ist es eine leere Aufnahme (z. B. nur Rauschen). */
    val hatInhalt: Boolean
        get() {
            val e = erg ?: return false
            val notiz = e.optJSONArray("notizen")?.optJSONObject(0)?.optString("titel").orEmpty()
            return (notiz.isNotBlank() && !notiz.startsWith("Kein verwertbarer")) ||
                (e.optJSONArray("aufgaben")?.length() ?: 0) > 0 || (e.optJSONArray("einkauf")?.length() ?: 0) > 0
        }

    /**
     * Nur eine Einkaufs-Ansage („Einkauf: Milch, Eier“, Grill Einkauf 2.11.: nur Liste, keine Notiz): Einkäufe, keine
     * Aufgabe, und die Aufnahme beginnt mit „Einkauf“ oder hat sonst nichts Verwertbares. Im Tagebuch eine leise Zeile.
     */
    val nurEinkauf: Boolean
        get() {
            val e = erg ?: return false
            if ((e.optJSONArray("einkauf")?.length() ?: 0) == 0) return false
            val aufgaben = e.optJSONArray("aufgaben")
            if (aufgaben != null && (0 until aufgaben.length()).any { aufgaben.optJSONObject(it)?.optString("art") != "erledigt" }) return false
            val anfang = e.optJSONArray("abschnitte")?.optJSONObject(0)?.optString("text").orEmpty().trim().lowercase()
            val notiz = e.optJSONArray("notizen")?.optJSONObject(0)?.optString("titel").orEmpty()
            return anfang.startsWith("einkauf") || notiz.isBlank() || notiz.startsWith("Kein verwertbarer")
        }

    /** „Milch, Eier, Hafermilch“ */
    fun einkaufText(): String {
        val a = erg?.optJSONArray("einkauf") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("sache")?.takeIf { s -> s.isNotBlank() } }
            .joinToString(", ")
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
            // meist ist das Claude-Abo gerade ausgeschöpft; der Laptop versucht es selbst wieder (weiter_ab)
            roh.optString("status") == "wartet" -> {
                val ab = roh.optString("weiter_ab").takeIf { it.length >= 16 }?.substring(11, 16)
                if (roh.optString("meldung").contains("Claude")) "wartet auf Claude" + (ab?.let { ", weiter ab $it" } ?: "")
                else "Laptop wartet" + (ab?.let { ", weiter ab $it" } ?: "")
            }
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
class Sache(
    val schluessel: String, val sache: String, val details: String, val abteilung: Int, val abgehakt: Boolean,
    val laden: String = "Supermarkt",
    /** frühere Form des Häkchen-Schlüssels (Stelle in der Liste), wird beim nächsten Antippen aufgeräumt */
    val alterSchluessel: String? = null,
)

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
    // Supermärkte (auch Discounter): darin wird nach Gängen sortiert. Andere Läden (dm, Bäcker, Baumarkt) nicht.
    private val SUPERMAERKTE = listOf(
        "supermarkt", "discounter", "aldi", "lidl", "rewe", "edeka", "penny", "netto", "kaufland", "norma", "globus",
        "tegut", "real", "marktkauf", "nahkauf", "famila", "combi", "hit", "denns", "alnatura", "bio",
    )

    fun istSupermarkt(laden: String): Boolean {
        // Wortweise: „Rewe City“, „Biomarkt“, „Aldi Süd“ zählen, „Bäckerei Schmidt“ nicht
        return laden.lowercase(Locale.GERMANY).split(" ", "-", "/").any { w -> SUPERMAERKTE.any { w == it || w.startsWith(it) } }
    }

    /** 0 = „Supermarkt“ ohne Namen, 1 = genannter Supermarkt (Aldi, Rewe …), 2 = anderer Laden. */
    fun ladenRang(laden: String): Int = when {
        laden == "Supermarkt" -> 0
        istSupermarkt(laden) -> 1
        else -> 2
    }

    // so heißen die Abteilungen in ergebnis.json (laptop/braininger/auswertung.py ABTEILUNGEN), gleiche Reihenfolge
    private val KENNUNGEN = listOf(
        "obst_gemuese", "brot", "kuehlregal", "fleisch_fisch", "vorrat", "suesses", "getraenke", "tiefkuehl",
        "drogerie_haushalt", "sonstiges",
    )

    fun eintraege(c: Context): List<Eintrag> = Lager.aufnahmen(c).mapNotNull { o ->
        Lager.json(File(o, "info.json"))?.let { Eintrag(o, it, Lager.json(File(o, "ergebnis.json"))) }
    }

    /**
     * Gehört e als Nachtrag unter einen anderen Gedanken? Nur, wenn der noch da ist und Jan den Nachtrag nicht gelöst
     * hat („Als eigenen Gedanken führen“). Gelöst wird nur hier am Kompakt vermerkt, die Dateien bleiben unverändert.
     */
    fun nachtragsZiel(c: Context, e: Eintrag, alle: List<Eintrag>): String? {
        val zu = e.nachtragZu ?: return null
        if (c.getSharedPreferences(PREFS, 0).getBoolean("los:${e.id}", false)) return null
        return zu.takeIf { z -> alle.any { it.id == z } }
    }

    fun nachtraegeVon(c: Context, original: Eintrag, alle: List<Eintrag>): List<Eintrag> =
        alle.filter { nachtragsZiel(c, it, alle) == original.id }.sortedBy { it.id }

    fun nachtragLoesen(c: Context, e: Eintrag, los: Boolean) =
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("los:${e.id}", los).apply()

    private fun heute() =SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

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

    /**
     * Häkchen hängen an der Sache selbst („e:<Aufnahme>:milch“), nicht an ihrer Stelle in der Liste: wertet der Laptop
     * eine Aufnahme neu aus, verrutscht sonst ein Häkchen zur Nachbarsache. Ältere Häkchen („e:<Aufnahme>:3“) gelten
     * weiter, bis die Sache das nächste Mal angetippt wird.
     */
    fun einkauf(c: Context, alle: List<Eintrag>): List<Sache> {
        val p = c.getSharedPreferences(PREFS, 0)
        val liste = mutableListOf<Sache>()
        val schonDa = mutableSetOf<String>()
        // von Hand Hinzugefügtes zuerst: es ist meist das Neueste
        for (x in eigene(c)) {
            val name = x.optString("sache").trim()
            if (name.isBlank() || !schonDa.add(name.lowercase(Locale.GERMANY))) continue
            val k = x.optString("k")
            liste += Sache(k, name, "", x.optInt("abteilung", ABTEILUNGEN.size - 1), p.getString(k, null) == "ja",
                x.optString("laden").ifBlank { "Supermarkt" })
        }
        for (e in alle) {
            val a = e.erg?.optJSONArray("einkauf") ?: continue
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val name = x.optString("sache").trim()
                // die neueste Nennung zählt (Liste ist neu → alt); ältere gleiche Sachen fallen weg
                if (name.isBlank() || !schonDa.add(name.lowercase(Locale.GERMANY))) continue
                val s = "e:${e.id}:${name.lowercase(Locale.GERMANY)}"
                val alt = "e:${e.id}:$i"
                val zustand = p.getString(s, null) ?: p.getString(alt, null)
                if (zustand == "weg") continue
                val details = listOf(x.optString("menge"), x.optString("details"), x.optString("wer_holt").let { if (it.isNotBlank()) "$it holt" else "" })
                    .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
                val roh = x.optString("abteilung")
                val abt = KENNUNGEN.indexOf(roh).takeIf { it >= 0 } ?: ABTEILUNGEN.indexOf(roh).takeIf { it >= 0 } ?: (ABTEILUNGEN.size - 1)
                val laden = x.optString("laden").takeIf { it.isNotBlank() && it != "null" } ?: "Supermarkt"
                liste += Sache(s, name, details, abt, zustand == "ja", laden, alt)
            }
        }
        // Der Korb leert sich am Morgen von selbst: was vor 4 Uhr heute abgehakt wurde, kommt weg (Jan, 9.11.2026).
        // Häkchen ohne Uhrzeit (von vor dieser Version) bekommen jetzt eine und gehen am nächsten Morgen.
        val grenze = morgenGrenze()
        val jetzt = System.currentTimeMillis()
        val zeiten = p.edit()
        val abgelaufen = liste.filter { s ->
            if (!s.abgehakt) return@filter false
            val t = p.getLong(zeitSchluessel(s.schluessel), 0L)
            if (t == 0L) { zeiten.putLong(zeitSchluessel(s.schluessel), jetzt); false } else t < grenze
        }
        zeiten.apply()
        if (abgelaufen.isNotEmpty()) {
            abgehakteWeg(c, abgelaufen)
            liste.removeAll(abgelaufen)
        }
        // erst der Supermarkt (nach Gang), dann die anderen Läden nach Namen, Abgehaktes ans Ende
        return liste.sortedWith(compareBy<Sache>({ it.abgehakt }, { ladenRang(it.laden) }, { it.laden.lowercase() }, { it.abteilung }))
    }

    private fun zeitSchluessel(schluessel: String) = "$schluessel@zeit"

    /** Heute 4 Uhr früh, oder gestern 4 Uhr, wenn es noch nicht so spät ist. */
    private fun morgenGrenze(): Long = java.util.Calendar.getInstance().apply {
        val jetzt = timeInMillis
        set(java.util.Calendar.HOUR_OF_DAY, 4)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis > jetzt) add(java.util.Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    fun sacheUmschalten(c: Context, s: Sache) {
        c.getSharedPreferences(PREFS, 0).edit().apply {
            s.alterSchluessel?.let { remove(it) }
            if (s.abgehakt) {
                remove(s.schluessel)
                remove(zeitSchluessel(s.schluessel))
            } else {
                putString(s.schluessel, "ja")
                putLong(zeitSchluessel(s.schluessel), System.currentTimeMillis())
            }
        }.apply()
    }

    /** „Rückgängig“ im Laden: die Sache ist wieder offen. */
    fun sacheOeffnen(c: Context, s: Sache) = c.getSharedPreferences(PREFS, 0).edit().apply {
        remove(s.schluessel)
        remove(zeitSchluessel(s.schluessel))
        s.alterSchluessel?.let { remove(it) }
    }.apply()

    fun abgehakteWeg(c: Context, alle: List<Sache>) {
        val weg = alle.filter { it.abgehakt }
        c.getSharedPreferences(PREFS, 0).edit().apply {
            weg.forEach { s ->
                s.alterSchluessel?.let { remove(it) }
                remove(zeitSchluessel(s.schluessel))
                if (s.schluessel.startsWith("m:")) remove(s.schluessel) else putString(s.schluessel, "weg")
            }
        }.apply()
        // von Hand Hinzugefügtes ganz entfernen
        val schluessel = weg.map { it.schluessel }.filter { it.startsWith("m:") }.toSet()
        if (schluessel.isNotEmpty()) eigeneSpeichern(c, eigene(c).filter { it.optString("k") !in schluessel })
    }

    // ----- von Hand hinzugefügte Einkäufe (Plus am Kompakt), nur hier gespeichert

    private const val EIGENE = "lite_eigene"

    private fun eigene(c: Context): List<JSONObject> = try {
        val a = org.json.JSONArray(c.getSharedPreferences(EIGENE, 0).getString("liste", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    private fun eigeneSpeichern(c: Context, liste: List<JSONObject>) =
        c.getSharedPreferences(EIGENE, 0).edit().putString("liste", org.json.JSONArray(liste).toString()).apply()

    fun hinzufuegen(c: Context, sache: String, abteilung: Int) {
        val neu = JSONObject().put("k", "m:${System.currentTimeMillis()}").put("sache", sache.trim())
            .put("abteilung", abteilung).put("laden", "Supermarkt")
        eigeneSpeichern(c, listOf(neu) + eigene(c))
    }

    /**
     * Gang für eine eingetippte Sache raten. Das längste passende Stichwort gewinnt („Erdbeermarmelade“ → Vorrat,
     * „Fischstäbchen“ → Tiefkühl). Unbekanntes: Sonstiges. Jan kann den Gang beim Hinzufügen mit einem Tipp ändern.
     */
    fun abteilungRaten(sache: String): Int {
        val s = sache.lowercase(Locale.GERMANY)
        var beste = ABTEILUNGEN.size - 1
        var laenge = 0
        STICHWORTE.forEachIndexed { abt, woerter ->
            woerter.forEach { w -> if (w.length > laenge && s.contains(w)) { beste = abt; laenge = w.length } }
        }
        return beste
    }

    // gleiche Reihenfolge wie ABTEILUNGEN
    private val STICHWORTE = listOf(
        listOf("apfel", "äpfel", "banane", "birne", "kirsch", "erdbeer", "traube", "zitrone", "orange", "mandarine",
            "kiwi", "obst", "gemüse", "salat", "gurke", "tomate", "paprika", "zwiebel", "knoblauch", "kartoffel", "möhre",
            "karotte", "kohlrabi", "brokkoli", "blumenkohl", "zucchini", "aubergine", "pilz", "champignon", "lauch",
            "basilikum", "petersilie", "schnittlauch", "kräuter", "avocado", "ingwer", "radieschen", "sellerie", "kohl",
            "mango", "ananas", "beeren", "melone", "spinat", "limette", "feige", "pflaume"),
        listOf("brot", "brötchen", "semmel", "toast", "baguette", "croissant", "laugen", "brezel", "weck", "zwieback",
            "kuchen", "stuten"),
        listOf("milch", "joghurt", "quark", "skyr", "käse", "butter", "sahne", "schmand", "frischkäse", "mozzarella",
            "parmesan", "feta", "eier", "wurst", "salami", "schinken", "lyoner", "aufschnitt", "margarine", "pudding",
            "tofu", "kefir", "buttermilch", "hefeteig", "blätterteig"),
        listOf("fleisch", "hack", "hähnchen", "huhn", "pute", "schnitzel", "steak", "rind", "schwein", "lachs", "fisch",
            "garnele", "bratwurst", "würstchen", "gulasch", "forelle", "thunfisch"),
        listOf("mehl", "zucker", "salz", "pfeffer", "nudel", "pasta", "spaghetti", "reis", "linsen", "bohnen", "konserve",
            "dose", "tomatenmark", "öl", "essig", "kaffee", "tee", "kakao", "müsli", "haferflocken", "cornflakes", "honig",
            "marmelade", "nutella", "gewürz", "zimt", "kapern", "brühe", "soße", "sauce", "senf", "ketchup", "mayo",
            "backpulver", "hefe", "kuvertüre", "nüsse", "mandeln", "rosinen", "oliven", "passierte"),
        listOf("schokolade", "chips", "kekse", "keks", "gummibär", "bonbon", "süßigkeit", "knabber", "salzstangen",
            "popcorn", "riegel", "lakritz"),
        listOf("wasser", "saft", "bier", "wein", "cola", "limo", "sprudel", "schorle", "sekt", "eistee", "mate"),
        listOf("tiefkühl", "pizza", "fischstäbchen", "eis", "pommes", "rahmspinat", "tk-", "gefrier"),
        listOf("klopapier", "toilettenpapier", "küchenrolle", "taschentücher", "spülmittel", "spülmaschine", "tabs",
            "waschmittel", "weichspüler", "shampoo", "duschgel", "zahnpasta", "zahnbürste", "seife", "deo", "müllbeutel",
            "schwamm", "putzmittel", "windel", "creme", "rasier", "batterie", "glühbirne", "alufolie", "frischhaltefolie",
            "backpapier"),
        emptyList(),
    )
}
