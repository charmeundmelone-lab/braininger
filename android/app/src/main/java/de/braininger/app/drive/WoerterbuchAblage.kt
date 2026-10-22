package de.braininger.app.drive

import android.content.Context
import de.braininger.app.Einstellungen

/**
 * Das Wörterbuch aus den Einstellungen liegt zusätzlich als Braininger/woerterbuch.txt im Drive, damit der Laptop
 * dieselben Begriffe benutzt (laptop/braininger/woerterbuch.py liest die Datei über Drive für Desktop). Die App legt
 * die Datei selbst an, denn mit drive.file sieht sie nur eigene Dateien.
 */
object WoerterbuchAblage {
    private const val NAME = "woerterbuch.txt"

    private fun vorlieben(context: Context) = context.getSharedPreferences("woerterbuch_drive", Context.MODE_PRIVATE)

    suspend fun hochladen(context: Context) {
        val api = DriveApi(context)
        val inhalt = (Einstellungen.woerterbuch(context) + "\n").toByteArray()
        val gemerkt = vorlieben(context).getString("id", null)
        if (gemerkt != null) {
            try {
                api.inhaltErsetzen(gemerkt, "text/plain", inhalt)
                return
            } catch (e: DriveFehler) {
                if (e.code != 404) throw e // im Drive gelöscht: neu anlegen
            }
        }
        val ordner = api.ordnerSichern("Braininger", null)
        val id = api.dateiFinden(NAME, ordner)?.id?.also { api.inhaltErsetzen(it, "text/plain", inhalt) }
            ?: api.hochladen(NAME, ordner, "text/plain", inhalt)
        vorlieben(context).edit().putString("id", id).apply()
    }
}
