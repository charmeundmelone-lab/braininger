package de.braininger.app

import android.content.Context
import de.braininger.app.claude.Auswertung
import de.braininger.app.claude.ClaudeFehler
import de.braininger.app.drive.DriveApi
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.GoogleDocs
import de.braininger.app.drive.UploadStand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notizen einer Aufnahme korrigieren (Wunsch 21.10.2026): Jan sagt, was falsch ist („Das war Lasse, nicht Naira“),
 * Claude Haiku schreibt die Notizen am Handy neu. Die neue ergebnis.json geht sofort ins Drive; der Laptop zieht sie
 * über das Feld „korrigiert“ nach (laptop/braininger/handy.py, nachziehen). Aufgaben-Vorschläge bleiben unverändert.
 */
object Korrektur {
    suspend fun ausfuehren(context: Context, a: Aufnahme, korrektur: String) {
        val datei = File(a.ordner, "ergebnis.json")
        val j = try { JSONObject(datei.readText()) } catch (_: Exception) { throw IOException("Kein Ergebnis zum Korrigieren.") }
        val notizen = j.optJSONArray("notizen") ?: JSONArray()
        if (notizen.length() == 0) throw ClaudeFehler("Diese Aufnahme hat keine Notiz.")
        val ergebnisId = UploadStand.lesen(a.ordner).ergebnisId ?: throw IOException("Aufnahme ist noch nicht im Drive.")

        val roh = j.optJSONArray("abschnitte") ?: JSONArray()
        val namen = Ergebnis.aus(j.toString())?.sprecher.orEmpty()
        val abschnitte = (0 until roh.length()).mapNotNull { roh.optJSONObject(it) }.map {
            val wer = it.optString("sprecher").takeUnless { _ -> it.isNull("sprecher") }?.takeIf { s -> s.isNotBlank() }
            val name = wer?.let { s -> namen[s] ?: s }
            Auswertung.Abschnitt(it.optDouble("start"), it.optDouble("ende"), (name?.let { n -> "$n: " } ?: "") + it.optString("text"))
        }
        val e = Auswertung.korrigieren(context, a.modus.kennung, notizen, abschnitte, korrektur)

        val jetzt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date())
        val liste = j.optJSONArray("korrekturen") ?: JSONArray()
        liste.put(JSONObject().put("zeit", jetzt).put("text", korrektur.trim()))
        j.put("notizen", e.daten.getJSONArray("notizen"))
            .put("kurz", e.daten.optString("kurz").ifBlank { j.optString("kurz") })
            .put("korrekturen", liste)
            .put("korrigiert", jetzt)
            .put("aktualisiert", jetzt)
            .put("kosten_usd", j.optDouble("kosten_usd", 0.0) + e.dollar)
        val inhalt = j.toString(1).toByteArray()
        // erst ins Drive, dann lokal: sonst holt der nächste Abruf die alte Fassung zurück
        DriveApi(context).inhaltErsetzen(ergebnisId, "application/json", inhalt)
        val tmp = File(a.ordner, "ergebnis.json.tmp")
        tmp.writeBytes(inhalt)
        tmp.renameTo(datei)
        try {
            GoogleDocs.aktualisieren(context)
        } catch (_: Exception) {
            // das Google Doc folgt beim nächsten Abruf
        }
    }
}
