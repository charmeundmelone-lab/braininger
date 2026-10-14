package de.braininger.app.drive

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frühtest a: Sieht die Handy-App Dateien, die der Laptop über Google Drive für Desktop schreibt?
 *
 * Schritt 1 (Handy): legt „Braininger/Test“ an, darin „vorab_angelegt.txt“ (Weg 2 im Bauplan)
 *                    und „vom_handy.txt“.
 * Laptop:            schreibt „vom_laptop.txt“ neu in den Ordner und überschreibt „vorab_angelegt.txt“.
 * Schritt 2 (Handy): listet den Ordner, liest „vorab_angelegt.txt“ und schreibt alles in „bericht.txt“,
 *                    die der Laptop wieder lesen kann.
 */
object DriveTest {
    private fun jetzt() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    private suspend fun testOrdner(api: DriveApi) =
        api.ordnerSichern("Test", api.ordnerSichern("Braininger", null))

    suspend fun vorbereiten(context: Context): String {
        val api = DriveApi(context)
        val ordner = testOrdner(api)
        val vorab = api.dateiFinden("vorab_angelegt.txt", ordner)
        if (vorab == null) {
            api.hochladen("vorab_angelegt.txt", ordner, "text/plain", "LEER - vom Handy angelegt ${jetzt()}\n".toByteArray())
        }
        val handy = api.dateiFinden("vom_handy.txt", ordner)
        val text = "Vom Handy geschrieben ${jetzt()}\n".toByteArray()
        if (handy == null) api.hochladen("vom_handy.txt", ordner, "text/plain", text)
        else api.inhaltErsetzen(handy.id, "text/plain", text)
        return "Testordner bereit. Jetzt schreibt der Laptop hinein, danach „Prüfen“ tippen."
    }

    suspend fun pruefen(context: Context): String {
        val api = DriveApi(context)
        val ordner = testOrdner(api)
        val dateien = api.liste(ordner)
        val bericht = buildString {
            appendLine("Braininger Frühtest a, Bericht vom Handy, ${jetzt()}")
            appendLine("Sichtbare Dateien im Ordner Braininger/Test: ${dateien.size}")
            for (d in dateien.sortedBy { it.name }) {
                appendLine("- ${d.name} (geändert ${d.geaendert}, ${d.groesse} Bytes)")
            }
            appendLine()
            appendLine("vom_laptop.txt sichtbar (Laptop legt neue Datei an): ${if (dateien.any { it.name == "vom_laptop.txt" }) "JA" else "NEIN"}")
            val vorab = dateien.firstOrNull { it.name == "vorab_angelegt.txt" }
            if (vorab == null) {
                appendLine("vorab_angelegt.txt: nicht mehr sichtbar (Laptop hat sie vermutlich ersetzt statt überschrieben)")
            } else {
                val inhalt = try { api.herunterladen(vorab.id).decodeToString().trim() } catch (e: Exception) { "FEHLER: ${e.message}" }
                appendLine("vorab_angelegt.txt Inhalt: $inhalt")
                appendLine("Laptop-Text in vorab angelegter Datei angekommen: ${if (inhalt.contains("LAPTOP")) "JA" else "NEIN"}")
            }
        }
        val alt = api.dateiFinden("bericht.txt", ordner)
        if (alt == null) api.hochladen("bericht.txt", ordner, "text/plain", bericht.toByteArray())
        else api.inhaltErsetzen(alt.id, "text/plain", bericht.toByteArray())
        return bericht
    }
}
