package de.braininger.app.erkennung

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Die beiden Modelle für die Spracherkennung. Sie liegen nicht in der App (zu groß), sondern werden einmal
 * über WLAN geladen und bleiben im App-Speicher. Größen geprüft am 20.10.2026.
 * - Whisper large-v3-turbo q8_0 (MIT-Lizenz), huggingface.co/ggerganov/whisper.cpp
 * - Silero VAD v5.1.2 (MIT-Lizenz), huggingface.co/ggml-org/whisper-vad
 */
object Modelle {
    data class Modell(val datei: String, val url: String, val groesse: Long)

    val WHISPER = Modell(
        "ggml-large-v3-turbo-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
        874_188_075L,
    )
    val VAD = Modell(
        "ggml-silero-v5.1.2.bin",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
        885_098L,
    )

    fun ordner(context: Context) = File(context.filesDir, "modelle").apply { mkdirs() }
    fun datei(context: Context, m: Modell) = File(ordner(context), m.datei)
    fun vorhanden(context: Context, m: Modell) = datei(context, m).length() == m.groesse
    fun bereit(context: Context) = vorhanden(context, WHISPER) && vorhanden(context, VAD)

    /** Geladene Bytes beider Modelle zusammen, für die Anzeige in den Einstellungen. */
    fun geladen(context: Context): Long = listOf(WHISPER, VAD).sumOf { m ->
        val fertig = datei(context, m)
        if (fertig.exists()) fertig.length() else File(ordner(context), m.datei + ".teil").length()
    }

    val gesamt: Long get() = WHISPER.groesse + VAD.groesse

    fun laden(context: Context) {
        if (bereit(context)) return
        val auftrag = OneTimeWorkRequestBuilder<ModellLaden>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED) // nur WLAN, 874 MB
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("braininger-modelle", ExistingWorkPolicy.KEEP, auftrag)
    }

    /** Lädt ein Modell, setzt einen abgebrochenen Download fort und prüft am Ende die Größe. */
    suspend fun herunterladen(context: Context, m: Modell) = withContext(Dispatchers.IO) {
        val ziel = datei(context, m)
        if (ziel.length() == m.groesse) return@withContext
        val teil = File(ordner(context), m.datei + ".teil")
        if (teil.length() > m.groesse) teil.delete()
        val verbindung = URL(m.url).openConnection() as HttpURLConnection
        verbindung.instanceFollowRedirects = true
        verbindung.connectTimeout = 30_000
        verbindung.readTimeout = 60_000
        if (teil.length() > 0) verbindung.setRequestProperty("Range", "bytes=${teil.length()}-")
        val code = verbindung.responseCode
        val anhaengen = code == HttpURLConnection.HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !anhaengen) {
            verbindung.disconnect()
            throw IOException("Modell-Download: HTTP $code")
        }
        verbindung.inputStream.use { ein ->
            FileOutputStream(teil, anhaengen).use { aus -> ein.copyTo(aus, 1 shl 16) }
        }
        verbindung.disconnect()
        if (teil.length() != m.groesse) throw IOException("Modell unvollständig (${teil.length()} von ${m.groesse} Bytes)")
        if (!teil.renameTo(ziel)) throw IOException("Modell ließ sich nicht speichern")
    }
}

class ModellLaden(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Modelle.herunterladen(applicationContext, Modelle.VAD)
        Modelle.herunterladen(applicationContext, Modelle.WHISPER)
        HandyAuswertung.anstossen(applicationContext) // wartende Aufnahmen jetzt erkennen
        Result.success()
    } catch (_: IOException) {
        Result.retry()
    }
}
