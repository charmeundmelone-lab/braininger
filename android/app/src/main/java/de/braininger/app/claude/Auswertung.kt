package de.braininger.app.claude

import android.content.Context
import com.anthropic.models.messages.OutputConfig
import de.braininger.app.Aufgabe
import de.braininger.app.Einstellungen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Notizen aus dem erkannten Text, nach denselben Regeln wie am Laptop (laptop/braininger/auswertung.py).
 * Anweisungen, Vorlagen und Schema kommen aus assets/auswertung.json; die Datei erzeugt
 * laptop/werkzeuge/auswertung_exportieren.py. Das Wörterbuch kommt aus den Einstellungen; die Stimmerkennung gibt es
 * nur am Laptop.
 */
object Auswertung {
    /** Lebensbereiche wie in auswertung.py (BEREICHE), für die Farben. */
    val BEREICHE = setOf("familie", "musik", "ich", "projekte", "finanzen")

    private class Vorgaben(
        val systemPrompt: String,
        val vorlagenText: String,
        val schema: JSONObject,
        val kennungen: Set<String>,
        val standard: String,
    )

    @Volatile
    private var vorgaben: Vorgaben? = null

    private fun vorgaben(context: Context): Vorgaben = vorgaben ?: run {
        val j = JSONObject(context.assets.open("auswertung.json").use { it.readBytes().decodeToString() })
        val k = j.getJSONArray("kennungen")
        Vorgaben(
            j.getString("system_prompt"), j.getString("vorlagen_text"), j.getJSONObject("schema"),
            (0 until k.length()).map { k.getString(it) }.toSet(), j.getString("standard_gedanke"),
        ).also { vorgaben = it }
    }

    private fun systemPrompt(context: Context, v: Vorgaben): String {
        val name = Einstellungen.name(context).ifBlank { "Der Nutzer" }
        val beschreibung = Einstellungen.beschreibung(context)
        val wer = if (beschreibung.isNotBlank()) "$name, $beschreibung," else name
        return v.systemPrompt.replace("{wer}", wer).replace("{name}", name)
    }

    data class Abschnitt(val start: Double, val ende: Double, val text: String)

    private fun wochentag(startIso: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(startIso)!!
        " (" + SimpleDateFormat("EEEE", Locale.GERMAN).format(d) + ")"
    } catch (_: Exception) {
        ""
    }

    private fun marke(s: Double): String {
        val t = s.toInt()
        return String.format(Locale.ROOT, "%02d:%02d", t / 60, t % 60)
    }

    private fun minSek(s: Long) = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)

    /** Wie _woerterbuch_text() am Laptop. Steht in der Nachricht, nicht in den Anweisungen, damit der Zwischenspeicher hält. */
    private fun woerterbuchText(context: Context): String {
        val b = Einstellungen.begriffe(context)
        if (b.isEmpty()) return ""
        return "# Wörterbuch\n\nSo werden diese Namen und Begriffe geschrieben. Klingt etwas im Text ähnlich " +
            "und passt der Zusammenhang, ist dieser Begriff gemeint:\n" + b.joinToString(", ") + "\n\n"
    }

    /** Gleicher Aufbau wie prompt_bauen() am Laptop; die Vorlagen stehen hier in den Anweisungen (Zwischenspeicher). */
    private fun inhalt(
        modus: String, startIso: String, dauerS: Double, sprache: String, hinweise: List<String>,
        markierungen: List<Long>, fotos: List<Pair<String, Long>>, offene: List<Aufgabe>, abschnitte: List<Abschnitt>,
    ): String {
        val kopf = mutableListOf(
            "Aufnahme vom $startIso${wochentag(startIso)}, Dauer ${String.format(Locale.ROOT, "%.1f", dauerS / 60)} min, " +
                "Modus „$modus“, erkannte Sprache „$sprache“."
        )
        if (hinweise.isNotEmpty()) kopf += "Hinweise: " + hinweise.joinToString(" ")
        if (markierungen.isNotEmpty()) {
            kopf += "Markierungen (Markier-Knopf) bei: " + markierungen.sorted().joinToString(", ") { "${minSek(it)} ($it s)" } + "."
        }
        if (fotos.isNotEmpty()) {
            kopf += "Fotos (in dieser Reihenfolge beigefügt): " + fotos.joinToString(", ") { (d, s) -> "$d bei ${minSek(s)}" } + "."
        }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        val offen = offene.joinToString("\n") { a ->
            "- ${a.id}: ${a.text}" + (a.frist?.let { " (Frist ${iso.format(java.util.Date(it))})" } ?: "")
        }.ifBlank { "(keine)" }
        val text = abschnitte.joinToString("\n") { "[${marke(it.start)}] ${it.text}" }.ifBlank { "(kein Text erkannt)" }
        return "# Offene Aufgaben\n\n$offen\n\n# Aufnahme\n\n${kopf.joinToString("\n")}\n\n# Text\n\n$text\n\nSchreib jetzt die Notiz(en)."
    }

    data class Ergebnis(val daten: JSONObject, val dollar: Double)

    /**
     * Wertet eine Aufnahme aus. Rückgabe: geprüfte Felder kurz, notizen, aufgaben, vorlage_hinweis, wichtig, sprecher.
     * Wirft ClaudeFehler, ClaudeSpaeter oder DeckelErreicht.
     */
    suspend fun auswerten(
        context: Context, modus: String, startIso: String, dauerS: Double, sprache: String, hinweise: List<String>,
        markierungen: List<Long>, fotos: List<Pair<File, Long>>, offene: List<Aufgabe>, abschnitte: List<Abschnitt>,
    ): Ergebnis {
        val v = vorgaben(context)
        val antwort = ClaudeAufruf.frage(
            context,
            system = listOf(systemPrompt(context, v), "# Vorlagen\n\n" + v.vorlagenText),
            inhalt = woerterbuchText(context) + inhalt(modus, startIso, dauerS, sprache, hinweise, markierungen,
                fotos.map { it.first.name to it.second }, offene, abschnitte),
            schema = v.schema,
            bilder = fotos.map { it.first },
            geschaetzt = 0.01 + abschnitte.sumOf { it.text.length } / 3.2 * 0.10 / 1e6,
            // „high“ statt Standard „medium“: Haiku denkt dann gründlich nach und sortiert lange Aufnahmen sauber in die
            // Lebensbereiche, statt der Reihenfolge des Erzählten zu folgen (Test 20.10.2026: 3 von 3 richtig, < 1 Cent
            // für 18 min). Denken braucht bis ~11.000 Token, deshalb mehr Platz als die üblichen 16.000.
            maxTokens = 32_000,
            effort = OutputConfig.Effort.HIGH,
        )
        return Ergebnis(pruefen(antwort.daten, modus, v, offene), antwort.dollar)
    }

    /**
     * Knopf „Korrigieren“ (Wunsch 21.10.2026: „Naira“ statt „Lasse“): Jan sagt in eigenen Worten, was falsch ist,
     * Claude schreibt die Notizen damit neu und ändert nur, was die Korrektur betrifft.
     * Rückgabe: geprüfte Felder kurz und notizen.
     */
    suspend fun korrigieren(
        context: Context, modus: String, notizen: JSONArray, abschnitte: List<Abschnitt>, korrektur: String,
    ): Ergebnis {
        val v = vorgaben(context)
        val felder = v.schema.getJSONObject("properties")
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("kurz", felder.getJSONObject("kurz")).put("notizen", felder.getJSONObject("notizen")))
            .put("required", JSONArray().put("kurz").put("notizen"))
            .put("additionalProperties", false)
        val name = Einstellungen.name(context).ifBlank { "der Nutzer" }
        val text = abschnitte.joinToString("\n") { "[${marke(it.start)}] ${it.text}" }.ifBlank { "(kein Text erkannt)" }
        val inhalt = woerterbuchText(context) + "# Bisherige Notizen\n\n${notizen.toString(1)}\n\n# Text der Aufnahme\n\n$text\n\n" +
            "# Korrektur von $name\n\n${korrektur.trim()}\n\n" +
            "Schreib die Notizen mit dieser Korrektur neu. Die Korrektur hat Vorrang vor dem erkannten Text. " +
            "Ändere nur, was sie betrifft, und was direkt daraus folgt (z. B. „er“ statt „sie“, ein anderer Lebensbereich). " +
            "Alles andere bleibt Wort für Wort gleich, auch Vorlage, Reihenfolge und Zeiten. Den Titel änderst du nur, " +
            "wenn er falsch ist. Erwähne die Korrektur nicht in der Notiz."
        val antwort = ClaudeAufruf.frage(
            context,
            system = listOf(systemPrompt(context, v), "# Vorlagen\n\n" + v.vorlagenText),
            inhalt = inhalt,
            schema = schema,
            geschaetzt = 0.01 + (text.length + notizen.toString().length) / 3.2 * 0.10 / 1e6,
        )
        val roh = antwort.daten.optJSONArray("notizen") ?: JSONArray()
        var neu = (0 until roh.length()).mapNotNull { roh.optJSONObject(it) }
            .filter { it.optString("vorlage") in v.kennungen && it.optString("inhalt").isNotBlank() }
        if (modus == "gedanke" && neu.isNotEmpty() && neu[0].optString("vorlage") == v.standard) neu = neu.take(1)
        if (neu.isEmpty()) throw ClaudeFehler("Claude hat keine korrigierte Notiz geliefert.")
        return Ergebnis(JSONObject().put("kurz", antwort.daten.optString("kurz")).put("notizen", JSONArray(neu)), antwort.dollar)
    }

    /** Wie auswerten() am Laptop: was nicht zum Schema oder zu den Aufgaben passt, wird entfernt oder umgedeutet. */
    private fun pruefen(d: JSONObject, modus: String, v: Vorgaben, offene: List<Aufgabe>): JSONObject {
        val notizenRoh = d.optJSONArray("notizen") ?: JSONArray()
        var notizen = (0 until notizenRoh.length()).mapNotNull { notizenRoh.optJSONObject(it) }
            .filter { it.optString("vorlage") in v.kennungen && it.optString("inhalt").isNotBlank() }
        if (modus == "gedanke" && notizen.isNotEmpty() && notizen[0].optString("vorlage") == v.standard) notizen = notizen.take(1)

        val offen = offene.map { it.id }.toSet()
        val aufgabenRoh = d.optJSONArray("aufgaben") ?: JSONArray()
        val aufgaben = JSONArray()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        for (i in 0 until aufgabenRoh.length()) {
            val a = aufgabenRoh.optJSONObject(i) ?: continue
            val text = a.optString("text").trim()
            var art = a.optString("art")
            if (text.isEmpty() || art !in setOf("neu", "ergaenzen", "erledigt")) continue
            var bezug = a.optString("bezug")
            if (art != "neu" && bezug !in offen) {
                if (art == "erledigt") continue // unbekannte Aufgabe kann nicht abgehakt werden
                art = "neu"
                bezug = ""
            }
            val frist = a.optString("frist").takeIf { it.isNotBlank() }?.let { f ->
                try { iso.format(iso.parse(f.take(16))!!) } catch (_: Exception) { "" }
            } ?: ""
            aufgaben.put(JSONObject().put("art", art).put("text", text).put("frist", frist)
                .put("unklar_wer", a.optBoolean("unklar_wer")).put("bezug", if (art != "neu") bezug else "")
                .put("bereich", a.optString("bereich").takeIf { it in BEREICHE } ?: ""))
        }

        val hinweis = d.optJSONObject("vorlage_hinweis")
        val hinweisGilt = hinweis != null && hinweis.optString("vorlage") in v.kennungen && notizen.isNotEmpty() &&
            hinweis.optString("vorlage") != notizen[0].optString("vorlage")

        return JSONObject()
            .put("bereich", d.optString("bereich").takeIf { it in BEREICHE } ?: "")
            .put("kurz", d.optString("kurz"))
            .put("notizen", JSONArray(notizen))
            .put("aufgaben", aufgaben)
            .put("vorlage_hinweis", if (hinweisGilt) hinweis else JSONObject.NULL)
            .put("wichtig", d.optJSONArray("wichtig") ?: JSONArray())
            .put("sprecher", d.optJSONArray("sprecher") ?: JSONArray())
    }
}
