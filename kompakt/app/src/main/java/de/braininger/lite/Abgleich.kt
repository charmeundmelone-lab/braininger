package de.braininger.lite

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Abgleich mit dem Laptop (laptop/kompakt_empfang.py): fertige Aufnahmen hinschicken, Ergebnisse abholen.
 * Weg: WLAN-Adresse des Laptops oder per USB-Kabel (adb reverse auf 127.0.0.1:8765).
 * Der Laptop legt die Aufnahme in den Drive-Eingang und wertet sie wie früher ganz selbst aus.
 */
object Abgleich {
    private val laeuft = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Gibt eine kurze Meldung zurück (für die Startseite und das Protokoll). */
    fun laufen(c: Context): String {
        if (!laeuft.compareAndSet(false, true)) return "läuft schon"
        try {
            val schluessel = Lager.schluessel(c)
            if (schluessel.isBlank()) return "Noch nicht mit dem Laptop eingerichtet"
            val basis = Lager.adressen(c).firstOrNull { erreichbar(it, schluessel) }
                ?: return "Laptop nicht erreichbar".also { Lager.protokoll(c, it) }
            Lager.kontaktMerken(c)
            var gesendet = 0
            var geholt = 0
            for (o in Lager.aufnahmen(c)) {
                val info = Lager.json(File(o, "info.json")) ?: continue
                val status = info.optString("status")
                if (status != "fertig" && status != "unterbrochen") continue
                if (!Lager.gesendet(o)) {
                    if (senden(basis, schluessel, o, info)) {
                        Lager.alsGesendet(o)
                        gesendet++
                    } else continue
                }
                val bisher = Lager.json(File(o, "ergebnis.json"))
                if (bisher?.optString("status") == "fertig") continue
                val text = holen("$basis/lite/ergebnis/${o.name}", schluessel) ?: continue
                if (text.isNotBlank() && text != bisher?.toString()) {
                    Lager.schreiben(File(o, "ergebnis.json"), text)
                    geholt++
                }
            }
            val meldung = "Laptop erreicht ($basis): $gesendet gesendet, $geholt Ergebnisse"
            Lager.protokoll(c, meldung)
            return meldung
        } catch (e: Exception) {
            Lager.protokoll(c, "Abgleich-Fehler: $e")
            return "Fehler: ${e.message}"
        } finally {
            laeuft.set(false)
        }
    }

    private fun verbindung(url: String, schluessel: String, methode: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = methode
            connectTimeout = 3000
            readTimeout = 30_000
            setRequestProperty("X-Braininger-Schluessel", schluessel)
        }

    private fun erreichbar(basis: String, schluessel: String): Boolean = try {
        val v = verbindung("$basis/lite/hallo", schluessel, "GET")
        val ok = v.responseCode == 200
        v.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun holen(url: String, schluessel: String): String? = try {
        val v = verbindung(url, schluessel, "GET")
        val text = if (v.responseCode == 200) v.inputStream.bufferedReader().readText() else null
        v.disconnect()
        text
    } catch (_: Exception) {
        null
    }

    private fun hochladen(url: String, schluessel: String, datei: File): Boolean = try {
        val v = verbindung(url, schluessel, "PUT")
        v.doOutput = true
        v.setFixedLengthStreamingMode(datei.length())
        v.setRequestProperty("Content-Type", "application/octet-stream")
        v.outputStream.use { aus -> datei.inputStream().use { it.copyTo(aus) } }
        val ok = v.responseCode == 200
        v.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    /** Erst alle Stücke, die info.json zuletzt: der Laptop fängt erst an, wenn sie da ist. */
    private fun senden(basis: String, schluessel: String, o: File, info: org.json.JSONObject): Boolean {
        val stuecke = info.optJSONArray("stuecke") ?: return false
        for (i in 0 until stuecke.length()) {
            val name = stuecke.getString(i)
            val datei = File(o, name)
            if (!datei.exists()) continue
            if (!hochladen("$basis/lite/aufnahme/${o.name}/$name", schluessel, datei)) return false
        }
        return hochladen("$basis/lite/aufnahme/${o.name}/info.json", schluessel, File(o, "info.json"))
    }
}
