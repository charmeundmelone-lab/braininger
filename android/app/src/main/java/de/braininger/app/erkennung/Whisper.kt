package de.braininger.app.erkennung

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Ein erkannter Textabschnitt, Zeiten in Sekunden ab Beginn der Aufnahme. */
data class Erkannt(val start: Double, val ende: Double, val text: String)

class ErkennungAbgebrochen : Exception("Spracherkennung abgebrochen")

/**
 * Spracherkennung am Handy mit whisper.cpp (native Bibliothek braininger_whisper, siehe src/main/cpp).
 * Gemessen am 20.10.2026 auf dem Phone (3a): large-v3-turbo q8_0 mit 4 Threads braucht 0,75–0,9 × die Aufnahmelänge.
 */
object Whisper {
    const val THREADS = 4 // 8 Threads waren langsamer: die 4 sparsamen Kerne bremsen

    @Volatile
    private var geladen = false

    private fun laden() {
        if (!geladen) {
            System.loadLibrary("braininger_whisper")
            geladen = true
        }
    }

    private external fun erkennenNativ(
        modell: String, vad: String, pcm: FloatArray, threads: Int, sprache: String, hinweis: String,
    ): ByteArray
    private external fun fortschrittNativ(): Int
    private external fun abbrechenNativ()

    /** Fortschritt der laufenden Erkennung in Prozent (0–100). */
    fun fortschritt(): Int = if (geladen) fortschrittNativ() else 0

    /** Bricht eine laufende Erkennung ab, z. B. weil eine neue Aufnahme startet. */
    fun abbrechen() {
        if (geladen) abbrechenNativ()
    }

    /**
     * pcm: 16 kHz, mono, Werte −1…1. versatzS wird auf alle Zeiten addiert.
     * hinweis: Begriffe aus dem Wörterbuch („Lasse, Naira, Braininger.“), Whisper bevorzugt sie beim Hören.
     */
    suspend fun erkennen(
        modell: File, vad: File, pcm: FloatArray, versatzS: Double, sprache: String = "de", hinweis: String = "",
    ): List<Erkannt> =
        withContext(Dispatchers.Default) {
            laden()
            val antwort = JSONObject(
                erkennenNativ(modell.absolutePath, vad.absolutePath, pcm, THREADS, sprache, hinweis).decodeToString()
            )
            if (antwort.optBoolean("abgebrochen")) throw ErkennungAbgebrochen()
            antwort.optString("fehler").takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
            val a = antwort.optJSONArray("abschnitte")
            (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val o = a!!.getJSONObject(i)
                val text = o.optString("text").trim()
                if (text.isEmpty()) null
                else Erkannt(versatzS + o.optDouble("start"), versatzS + o.optDouble("ende"), text)
            }
        }
}
