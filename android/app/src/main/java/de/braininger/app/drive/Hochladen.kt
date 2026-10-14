package de.braininger.app.drive

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.braininger.app.Ablage
import de.braininger.app.MainActivity
import de.braininger.app.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Was von einer Aufnahme schon im Drive liegt. Gespeichert als upload.json im Aufnahme-Ordner. */
data class UploadStand(
    var ordnerId: String? = null,
    val dateien: MutableMap<String, String> = mutableMapOf(),
    var infoId: String? = null,
    var abgeschlossen: Boolean = false,
) {
    companion object {
        fun lesen(ordner: File): UploadStand = try {
            val j = JSONObject(File(ordner, "upload.json").readText())
            val d = j.optJSONObject("dateien") ?: JSONObject()
            UploadStand(
                ordnerId = j.optString("ordner_id").ifBlank { null },
                dateien = d.keys().asSequence().associateWith { d.getString(it) }.toMutableMap(),
                infoId = j.optString("info_id").ifBlank { null },
                abgeschlossen = j.optBoolean("abgeschlossen"),
            )
        } catch (_: Exception) {
            UploadStand()
        }
    }

    fun speichern(ordner: File) {
        val j = JSONObject()
            .put("ordner_id", ordnerId ?: "")
            .put("dateien", JSONObject(dateien as Map<*, *>))
            .put("info_id", infoId ?: "")
            .put("abgeschlossen", abgeschlossen)
        val tmp = File(ordner, "upload.json.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(File(ordner, "upload.json"))
    }
}

/**
 * Lädt alle fertigen Stücke und am Ende die info.json nach „Braininger/Eingang/<Aufnahme>“ hoch.
 * Läuft, sobald Netz da ist (auch mobile Daten), und wiederholt bei Fehlern mit wachsender Pause.
 * Jedes Stück wird nur einmal hochgeladen, auch wenn der Auftrag mehrfach läuft.
 */
class HochladenAuftrag(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val NAME = "braininger-hochladen"
        private const val ID_HINWEIS = 3
        private const val VORLIEBEN = "drive"
        private const val ANMELDUNG_FEHLT = "anmeldung_fehlt"

        fun anstossen(context: Context) {
            val auftrag = OneTimeWorkRequestBuilder<HochladenAuftrag>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, auftrag)
        }

        fun anmeldungFehlt(context: Context) =
            context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getBoolean(ANMELDUNG_FEHLT, true)

        fun anmeldungFehltSetzen(context: Context, fehlt: Boolean) =
            context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putBoolean(ANMELDUNG_FEHLT, fehlt).apply()
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val api = DriveApi(ctx)
        return try {
            var eingang: String? = null
            for (a in Ablage.alle(ctx)) {
                val stand = UploadStand.lesen(a.ordner)
                if (stand.abgeschlossen) continue
                val info = Ablage.infoLesen(a.ordner) ?: continue
                val stuecke = info.optJSONArray("stuecke")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
                val offen = stuecke.filter { it !in stand.dateien }
                val ende = a.status == "fertig" || a.status == "unterbrochen"
                if (offen.isEmpty() && !ende) continue

                if (eingang == null) eingang = api.ordnerSichern("Eingang", api.ordnerSichern("Braininger", null))
                val ordnerId = stand.ordnerId ?: api.ordnerSichern(a.ordner.name, eingang).also {
                    stand.ordnerId = it
                    stand.speichern(a.ordner)
                }

                for (name in offen) {
                    val datei = File(a.ordner, name)
                    if (!datei.exists()) continue
                    val id = api.dateiFinden(name, ordnerId)?.id
                        ?: api.hochladen(name, ordnerId, "audio/mp4", datei.readBytes())
                    stand.dateien[name] = id
                    stand.speichern(a.ordner)
                }

                // info.json zuletzt: Sie sagt dem Laptop, dass die Aufnahme vollständig ist.
                if (ende && stuecke.all { it in stand.dateien }) {
                    val inhalt = File(a.ordner, "info.json").readBytes()
                    val vorhanden = stand.infoId ?: api.dateiFinden("info.json", ordnerId)?.id
                    stand.infoId = if (vorhanden != null) {
                        api.inhaltErsetzen(vorhanden, "application/json", inhalt); vorhanden
                    } else {
                        api.hochladen("info.json", ordnerId, "application/json", inhalt)
                    }
                    stand.abgeschlossen = true
                    stand.speichern(a.ordner)
                }
            }
            anmeldungFehltSetzen(ctx, false)
            Result.success()
        } catch (e: AnmeldungNoetig) {
            anmeldungFehltSetzen(ctx, true)
            hinweis(ctx, "Bei Google anmelden", "Braininger kann nicht hochladen. Tippen, um dich bei Google anzumelden.")
            Result.failure()
        } catch (e: DriveFehler) {
            if (e.code in 400..499 && e.code != 429 && e.code != 408) {
                hinweis(ctx, "Hochladen fehlgeschlagen", e.message ?: "")
                Result.failure()
            } else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    private fun hinweis(ctx: Context, titel: String, text: String) {
        val oeffnen = PendingIntent.getActivity(
            ctx, 5, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_HINWEIS, n)
        } catch (_: SecurityException) {
        }
    }
}
