package de.braininger.lite

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wo Braininger Lite seine Sachen ablegt (nur im App-Speicher, nichts in Google):
 *   files/aufnahmen/<id>/teil_001.m4a …   Audio
 *   files/aufnahmen/<id>/info.json        gleiches Format wie die große App, ohne „auswertung“: der Laptop macht alles
 *   files/aufnahmen/<id>/lite.json        {"gesendet": true}
 *   files/aufnahmen/<id>/ergebnis.json    was der Laptop zurückgeschickt hat
 */
object Lager {
    private const val PREFS = "lite"
    const val STANDARD_ADRESSE = "http://127.0.0.1:8765" // per USB-Kabel (adb reverse) zum Laptop

    fun ordner(c: Context): File = File(c.filesDir, "aufnahmen").apply { mkdirs() }

    fun aufnahmen(c: Context): List<File> =
        ordner(c).listFiles { f -> f.isDirectory && File(f, "info.json").exists() }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun json(datei: File): JSONObject? = try {
        if (datei.exists() && datei.length() > 0) JSONObject(datei.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Erst in eine Hilfsdatei schreiben, dann umbenennen: so ist eine Datei nie halb geschrieben. */
    fun schreiben(datei: File, text: String) {
        val tmp = File(datei.parentFile, datei.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(datei)) {
            datei.delete()
            tmp.renameTo(datei)
        }
    }

    fun gesendet(o: File): Boolean = json(File(o, "lite.json"))?.optBoolean("gesendet") == true
    fun alsGesendet(o: File) = schreiben(File(o, "lite.json"), JSONObject().put("gesendet", true).toString())

    fun adressen(c: Context): List<String> {
        val eigene = c.getSharedPreferences(PREFS, 0).getString("adresse", null)
        return listOfNotNull(eigene, STANDARD_ADRESSE).distinct()
    }

    fun schluessel(c: Context): String = c.getSharedPreferences(PREFS, 0).getString("schluessel", "") ?: ""

    fun einrichten(c: Context, adresse: String?, schluessel: String?) {
        c.getSharedPreferences(PREFS, 0).edit().apply {
            if (!adresse.isNullOrBlank()) putString("adresse", adresse.trim().trimEnd('/'))
            if (!schluessel.isNullOrBlank()) putString("schluessel", schluessel.trim())
        }.apply()
    }

    fun letzterKontakt(c: Context): Long = c.getSharedPreferences(PREFS, 0).getLong("kontakt", 0L)
    fun kontaktMerken(c: Context) = c.getSharedPreferences(PREFS, 0).edit().putLong("kontakt", System.currentTimeMillis()).apply()

    fun iso(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(d)

    /**
     * Kleines Protokoll (Tasten, Senden, Fehler) in /sdcard/Android/data/de.braininger.lite/files/lite.log:
     * dort kann Claude es per adb lesen. Es enthält keine Aufnahme-Inhalte.
     */
    fun protokoll(c: Context, text: String) {
        try {
            val f = File(c.getExternalFilesDir(null) ?: c.filesDir, "lite.log")
            if (f.length() > 200_000) f.delete()
            f.appendText(SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT).format(Date()) + " " + text + "\n")
        } catch (_: Exception) {
        }
    }
}
