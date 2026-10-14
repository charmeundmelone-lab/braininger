package de.braininger.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Modus(val kennung: String, val anzeige: String) {
    GEDANKE("gedanke", "Gedanke"),
    GESPRAECH("gespraech", "Gespräch");

    companion object {
        fun aus(kennung: String?) = entries.firstOrNull { it.kennung == kennung } ?: GEDANKE
    }
}

/** Eine Aufnahme auf dem Handy: ein Ordner mit den Stücken und einer info.json. */
data class Aufnahme(
    val ordner: File,
    val modus: Modus,
    val start: Long,
    val ende: Long?,
    val stuecke: Int,
    val mikrofon: String,
    val status: String,
) {
    val dauerMs: Long get() = (ende ?: System.currentTimeMillis()) - start
}

/**
 * Speicherort der Aufnahmen im App-eigenen Bereich des Handys.
 * info.json beschreibt die Aufnahme für den Laptop (Modus, Zeiten, Stücke, Mikrofon).
 */
object Ablage {
    private val idFormat get() = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT)
    private val isoFormat get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    fun wurzel(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "aufnahmen").apply { mkdirs() }

    fun neueAufnahme(context: Context, modus: Modus, start: Long): File =
        File(wurzel(context), "${idFormat.format(Date(start))}_${modus.kennung}").apply { mkdirs() }

    fun infoSchreiben(
        ordner: File, modus: Modus, start: Long, ende: Long?,
        stuecke: List<String>, mikrofon: String, status: String, hinweise: List<String>,
    ) {
        val json = JSONObject().apply {
            put("version", 1)
            put("id", ordner.name)
            put("modus", modus.kennung)
            put("start", isoFormat.format(Date(start)))
            put("ende", ende?.let { isoFormat.format(Date(it)) } ?: JSONObject.NULL)
            put("dauer_s", ende?.let { (it - start) / 1000 } ?: JSONObject.NULL)
            put("stuecke", JSONArray(stuecke))
            put("mikrofon", mikrofon)
            put("status", status)
            put("hinweise", JSONArray(hinweise))
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val tmp = File(ordner, "info.json.tmp")
        tmp.writeText(json.toString(2))
        tmp.renameTo(File(ordner, "info.json"))
    }

    fun alle(context: Context): List<Aufnahme> =
        wurzel(context).listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { lesen(it) }
            .sortedByDescending { it.start }

    /**
     * Aufnahmen, die noch als „läuft“ markiert sind, obwohl keine Aufnahme läuft, wurden
     * unterbrochen (z. B. Absturz oder Neustart). Ihre fertigen Stücke bleiben erhalten.
     */
    fun unterbrocheneAbschliessen(context: Context) {
        if (AufnahmeStatus.zustand.value.laeuft) return
        for (a in alle(context).filter { it.status == "laeuft" }) {
            val teile = a.ordner.listFiles { f -> f.name.matches(Regex("teil_\\d{3}\\.m4a")) && f.length() > 0 }
                .orEmpty().map { it.name }.sorted()
            val letzte = a.ordner.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: a.start
            infoSchreiben(
                a.ordner, a.modus, a.start, letzte, teile, a.mikrofon, "unterbrochen",
                listOf("Aufnahme wurde unterbrochen, ${teile.size} Stücke gerettet."),
            )
        }
    }

    fun infoLesen(ordner: File): JSONObject? = try {
        JSONObject(File(ordner, "info.json").readText())
    } catch (_: Exception) {
        null
    }

    private fun lesen(ordner: File): Aufnahme? = try {
        val j = JSONObject(File(ordner, "info.json").readText())
        Aufnahme(
            ordner = ordner,
            modus = Modus.aus(j.optString("modus")),
            start = isoFormat.parse(j.getString("start"))!!.time,
            ende = if (j.isNull("ende")) null else isoFormat.parse(j.getString("ende"))!!.time,
            stuecke = j.optJSONArray("stuecke")?.length() ?: 0,
            mikrofon = j.optString("mikrofon"),
            status = j.optString("status"),
        )
    } catch (_: Exception) {
        null
    }
}

