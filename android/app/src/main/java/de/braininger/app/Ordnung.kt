package de.braininger.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.braininger.app.drive.AnmeldungNoetig
import de.braininger.app.drive.DriveApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Was vorne auf der Startseite steht und was im Archiv liegt.
 * Vorne: Aufnahmen der letzten 7 Tage, die nicht von Hand archiviert sind. Alles andere steht im Archiv.
 * Archivieren nimmt eine Aufnahme nur aus der Liste: Suche, Fragen, Tagesübersicht und Notizdateien bleiben.
 *
 * Datei: files/ordnung/ordnung.json {version, aufnahmen: {<id>: {zustand: "archiv", seit}}}.
 * Sicherung im Drive unter Braininger/Ordnung/ordnung.json (die App legt sie an, siehe drive.file).
 */
object Ordnung {
    const val TAGE_VORNE = 7
    private const val TAG_MS = 24 * 3_600_000L
    private val iso get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    private val _stand = MutableStateFlow(0)
    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu liest. */
    val stand: StateFlow<Int> = _stand

    fun datei(context: Context) = File(File(context.filesDir, "ordnung").apply { mkdirs() }, "ordnung.json")

    private fun lesen(context: Context): JSONObject = try {
        JSONObject(datei(context).readText()).optJSONObject("aufnahmen") ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun speichern(context: Context, aufnahmen: JSONObject) {
        val inhalt = JSONObject().put("version", 1).put("aufnahmen", aufnahmen).toString(1)
        val d = datei(context)
        val tmp = File(d.path + ".tmp")
        tmp.writeText(inhalt)
        tmp.renameTo(d)
        _stand.value++
        OrdnungSicherung.anstossen(context)
    }

    fun archivierte(context: Context): Set<String> {
        val a = lesen(context)
        return a.keys().asSequence().filter { a.optJSONObject(it)?.optString("zustand") == "archiv" }.toSet()
    }

    /** Neu genug für vorne (laufende Aufnahmen immer). */
    fun frisch(a: Aufnahme, jetzt: Long = System.currentTimeMillis()) =
        a.status == "laeuft" || jetzt - a.start < TAGE_VORNE * TAG_MS

    fun vorne(a: Aufnahme, archiviert: Set<String>) = frisch(a) && a.ordner.name !in archiviert

    fun archivieren(context: Context, ids: Collection<String>) {
        val a = lesen(context)
        val seit = iso.format(Date())
        for (id in ids) a.put(id, JSONObject().put("zustand", "archiv").put("seit", seit))
        speichern(context, a)
    }

    fun zurueckholen(context: Context, ids: Collection<String>) {
        val a = lesen(context)
        for (id in ids) a.remove(id)
        speichern(context, a)
    }

    /** Aus der Sicherung im Drive (nach Neuinstallation). Nur wenn es lokal noch nichts gibt. */
    fun wiederherstellen(context: Context, inhalt: String) {
        if (datei(context).exists()) return
        val a = try { JSONObject(inhalt).optJSONObject("aufnahmen") } catch (_: Exception) { null } ?: return
        val d = datei(context)
        d.writeText(JSONObject().put("version", 1).put("aufnahmen", a).toString(1))
        _stand.value++
    }
}

/** Sichert ordnung.json in Braininger/Ordnung/ (wie die Aufgaben-Sicherung). */
class OrdnungSicherung(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val NAME = "braininger-ordnung-sichern"
        private const val VORLIEBEN = "ordnung_sicherung"

        fun anstossen(context: Context) {
            val auftrag = OneTimeWorkRequestBuilder<OrdnungSicherung>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, auftrag)
        }

        private suspend fun dateiId(context: Context, api: DriveApi): Pair<String?, String> {
            val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
            val ordner = v.getString("ordner", null)
                ?: api.ordnerSichern("Ordnung", api.ordnerSichern("Braininger", null)).also { v.edit().putString("ordner", it).apply() }
            val id = v.getString("datei", null) ?: api.dateiFinden("ordnung.json", ordner)?.id
            id?.let { v.edit().putString("datei", it).apply() }
            return id to ordner
        }

        /** Beim Start: gibt es lokal nichts, aber eine Sicherung im Drive, wird sie zurückgeholt. */
        suspend fun wiederherstellen(context: Context) {
            if (Ordnung.datei(context).exists()) return
            val api = DriveApi(context)
            val (id, _) = dateiId(context, api)
            if (id != null) Ordnung.wiederherstellen(context, api.herunterladen(id).decodeToString())
        }
    }

    override suspend fun doWork(): Result = try {
        val datei = Ordnung.datei(applicationContext)
        if (datei.exists()) {
            val api = DriveApi(applicationContext)
            val inhalt = datei.readBytes()
            val (id, ordner) = dateiId(applicationContext, api)
            val ersetzt = id != null && try {
                api.inhaltErsetzen(id, "application/json", inhalt); true
            } catch (e: de.braininger.app.drive.DriveFehler) {
                if (e.code != 404) throw e
                false // im Drive gelöscht: neu anlegen
            }
            if (!ersetzt) {
                val neu = api.hochladen("ordnung.json", ordner, "application/json", inhalt)
                applicationContext.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString("datei", neu).apply()
            }
        }
        Result.success()
    } catch (_: AnmeldungNoetig) {
        Result.success()
    } catch (_: IOException) {
        Result.retry()
    }
}
