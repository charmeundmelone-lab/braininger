package de.braininger.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Eine Aufgabe in Jans Liste. frist und erledigt: Millisekunden oder null. */
data class Aufgabe(
    val id: String,
    val text: String,
    val frist: Long?,
    val erledigt: Long?,
    val quelle: String?,
    val erstellt: Long,
    val unklarWer: Boolean = false,
)

/** Vorschlag des Laptops aus einer Aufnahme. art: neu | ergaenzen | erledigt; bezug = Kennung einer Aufgabe. */
data class Vorschlag(
    val id: String,
    val aufnahme: String,
    val art: String,
    val text: String,
    val frist: Long?,
    val unklarWer: Boolean,
    val bezug: String?,
)

/**
 * Jans Aufgabenliste. Führend ist das Handy (files/aufgaben/). Jede Änderung plant die Erinnerungen neu und sichert
 * die Liste in Braininger/Aufgaben/aufgaben.json (zum Wiederherstellen und für die Lese-Ansicht am Laptop).
 */
object AufgabenSpeicher {
    private val iso get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

    private val _stand = MutableStateFlow(0)
    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu liest. */
    val stand: StateFlow<Int> = _stand

    private fun ordner(context: Context) = File(context.filesDir, "aufgaben").apply { mkdirs() }
    fun listenDatei(context: Context) = File(ordner(context), "aufgaben.json")
    private fun vorschlagDatei(context: Context) = File(ordner(context), "vorschlaege.json")
    private fun gesehenDatei(context: Context) = File(ordner(context), "gesehen.json")

    fun zeitLesen(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } }
    private fun zeitText(ms: Long?): String = ms?.let { iso.format(Date(it)) } ?: ""

    private fun schreiben(datei: File, inhalt: String) {
        val tmp = File(datei.path + ".tmp")
        tmp.writeText(inhalt)
        tmp.renameTo(datei)
    }

    // ---------- Aufgaben ----------

    fun alle(context: Context): List<Aufgabe> {
        val j = try { JSONObject(listenDatei(context).readText()) } catch (_: Exception) { return emptyList() }
        val a = j.optJSONArray("aufgaben") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Aufgabe(
                o.optString("id"), o.optString("text"), zeitLesen(o.optString("frist")), zeitLesen(o.optString("erledigt")),
                o.optString("quelle").ifBlank { null }, zeitLesen(o.optString("erstellt")) ?: 0L, o.optBoolean("unklar_wer"),
            )
        }.filter { it.id.isNotBlank() && it.text.isNotBlank() }
    }

    /** Offene Aufgaben, die mit der nächsten Frist zuerst, Aufgaben ohne Frist am Ende (neueste zuerst). */
    fun offene(context: Context): List<Aufgabe> =
        alle(context).filter { it.erledigt == null }
            .sortedWith(compareBy<Aufgabe> { it.frist == null }.thenBy { it.frist ?: 0L }.thenByDescending { it.erstellt })

    fun alsJson(liste: List<Aufgabe>): String = JSONObject()
        .put("version", 1)
        .put("aktualisiert", zeitText(System.currentTimeMillis()))
        .put("aufgaben", JSONArray(liste.map {
            JSONObject().put("id", it.id).put("text", it.text).put("frist", zeitText(it.frist))
                .put("erledigt", zeitText(it.erledigt)).put("quelle", it.quelle ?: "")
                .put("erstellt", zeitText(it.erstellt)).put("unklar_wer", it.unklarWer)
        })).toString(1)

    @Synchronized
    private fun speichern(context: Context, liste: List<Aufgabe>) {
        // erledigte nur 60 Tage aufheben, damit die Datei klein bleibt
        val grenze = System.currentTimeMillis() - 60L * 24 * 3_600_000
        schreiben(listenDatei(context), alsJson(liste.filter { (it.erledigt ?: Long.MAX_VALUE) > grenze }))
        Erinnerungen.allePlanen(context)
        AufgabenSicherung.anstossen(context)
        _stand.value++
    }

    @Synchronized
    private fun aendern(context: Context, f: (MutableList<Aufgabe>) -> Unit) {
        val liste = alle(context).toMutableList()
        f(liste)
        speichern(context, liste)
    }

    fun neu(context: Context, text: String, frist: Long?, quelle: String? = null, unklarWer: Boolean = false) =
        aendern(context) {
            it += Aufgabe(UUID.randomUUID().toString().take(8), text.trim(), frist, null, quelle, System.currentTimeMillis(), unklarWer)
        }

    fun abhaken(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(erledigt = System.currentTimeMillis()) else it }
    }

    fun loeschen(context: Context, id: String) = aendern(context) { l -> l.removeAll { it.id == id } }

    fun fristSetzen(context: Context, id: String, frist: Long?) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(frist = frist) else it }
    }

    /** „Morgen“ in der Erinnerung: morgen um 8 Uhr. */
    fun aufMorgen(context: Context, id: String) = fristSetzen(context, id, morgenAcht())

    fun morgenAcht(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Aus der Sicherung im Drive (nach Neuinstallation). Nur wenn es lokal noch keine Liste gibt. */
    fun wiederherstellen(context: Context, inhalt: String) {
        if (listenDatei(context).exists()) return
        try { JSONObject(inhalt) } catch (_: Exception) { return }
        schreiben(listenDatei(context), inhalt)
        Erinnerungen.allePlanen(context)
        _stand.value++
    }

    // ---------- Vorschläge ----------

    fun vorschlaege(context: Context): List<Vorschlag> {
        val a = try { JSONArray(vorschlagDatei(context).readText()) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Vorschlag(o.optString("id"), o.optString("aufnahme"), o.optString("art", "neu"), o.optString("text"),
                zeitLesen(o.optString("frist")), o.optBoolean("unklar_wer"), o.optString("bezug").ifBlank { null })
        }
    }

    private fun vorschlaegeSchreiben(context: Context, liste: List<Vorschlag>) {
        schreiben(vorschlagDatei(context), JSONArray(liste.map {
            JSONObject().put("id", it.id).put("aufnahme", it.aufnahme).put("art", it.art).put("text", it.text)
                .put("frist", zeitText(it.frist)).put("unklar_wer", it.unklarWer).put("bezug", it.bezug ?: "")
        }).toString(1))
        _stand.value++
    }

    /** Neue Vorschläge aus einer fertigen Aufnahme übernehmen (jeder nur einmal, auch nach Neuauswertung). */
    @Synchronized
    fun ausErgebnis(context: Context, aufnahme: String, vorschlaege: List<Vorschlag>): List<Vorschlag> {
        val gesehen = try { JSONArray(gesehenDatei(context).readText()) } catch (_: Exception) { JSONArray() }
        val bekannt = (0 until gesehen.length()).map { gesehen.getString(it) }.toMutableSet()
        val offen = alle(context).filter { it.erledigt == null }.map { it.id }.toSet()
        val neu = vorschlaege.filter { it.id !in bekannt && (it.bezug == null || it.bezug in offen) }
        if (neu.isEmpty()) return emptyList()
        bekannt += neu.map { it.id }
        schreiben(gesehenDatei(context), JSONArray(bekannt.toList().takeLast(2000)).toString())
        vorschlaegeSchreiben(context, vorschlaege(context) + neu)
        return neu
    }

    @Synchronized
    fun uebernehmen(context: Context, ids: Set<String>) {
        val alleV = vorschlaege(context)
        val gewaehlt = alleV.filter { it.id in ids }
        if (gewaehlt.isEmpty()) return
        aendern(context) { l ->
            for (v in gewaehlt) when (v.art) {
                "erledigt" -> l.replaceAll { if (it.id == v.bezug) it.copy(erledigt = System.currentTimeMillis()) else it }
                "ergaenzen" -> if (l.any { it.id == v.bezug }) {
                    l.replaceAll { if (it.id == v.bezug) it.copy(text = v.text, frist = v.frist ?: it.frist) else it }
                } else {
                    l += Aufgabe(UUID.randomUUID().toString().take(8), v.text, v.frist, null, v.aufnahme, System.currentTimeMillis(), v.unklarWer)
                }
                else -> l += Aufgabe(UUID.randomUUID().toString().take(8), v.text, v.frist, null, v.aufnahme, System.currentTimeMillis(), v.unklarWer)
            }
        }
        vorschlaegeSchreiben(context, alleV.filter { it.id !in ids })
    }

    fun verwerfen(context: Context, id: String) = vorschlaegeSchreiben(context, vorschlaege(context).filter { it.id != id })

    fun fristText(ms: Long): String {
        val heute = Calendar.getInstance()
        val f = Calendar.getInstance().apply { timeInMillis = ms }
        val tage = Math.round((tagBeginn(f) - tagBeginn(heute)) / 86_400_000.0).toInt()
        val uhr = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ms))
        return when (tage) {
            0 -> "heute $uhr"
            1 -> "morgen $uhr"
            -1 -> "gestern $uhr"
            in 2..6 -> SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(ms)) + " $uhr"
            else -> SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))
        }
    }

    private fun tagBeginn(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Erinnerungen zur Frist, minutengenau (USE_EXACT_ALARM; sonst ungefähr). */
object Erinnerungen {
    const val KANAL = "erinnerungen"
    private const val AKTION = "de.braininger.ERINNERUNG"
    const val EXTRA_ID = "aufgabe"

    fun kanalAnlegen(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KANAL, "Erinnerungen an Aufgaben", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun absicht(context: Context, id: String) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ErinnerungEmpfaenger::class.java).setAction(AKTION).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Plant alle offenen Aufgaben mit Frist in der Zukunft; erledigte und gelöschte werden abgemeldet. */
    fun allePlanen(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val jetzt = System.currentTimeMillis()
        val geplant = context.getSharedPreferences("erinnerungen", Context.MODE_PRIVATE)
        val vorher = geplant.getStringSet("ids", emptySet()).orEmpty()
        val jetztGeplant = mutableSetOf<String>()
        for (a in AufgabenSpeicher.alle(context)) {
            val f = a.frist
            if (a.erledigt != null || f == null || f <= jetzt) continue
            val pi = absicht(context, a.id)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, f, pi)
            jetztGeplant += a.id
        }
        for (id in vorher - jetztGeplant) am.cancel(absicht(context, id))
        geplant.edit().putStringSet("ids", jetztGeplant).apply()
    }

    fun zeigen(context: Context, a: Aufgabe) {
        kanalAnlegen(context)
        fun aktion(was: String, code: Int) = PendingIntent.getBroadcast(
            context, a.id.hashCode() * 3 + code,
            Intent(context, AufgabenAktion::class.java).setAction(was).putExtra(EXTRA_ID, a.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val oeffnen = PendingIntent.getActivity(
            context, 20, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(a.text)
            .setContentText("Fällig " + (a.frist?.let { AufgabenSpeicher.fristText(it) } ?: "jetzt"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .addAction(0, "ERLEDIGT", aktion(AufgabenAktion.ERLEDIGT, 1))
            .addAction(0, "MORGEN", aktion(AufgabenAktion.MORGEN, 2))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(a.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

class ErinnerungEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Erinnerungen.EXTRA_ID) ?: return
        val a = AufgabenSpeicher.alle(context).firstOrNull { it.id == id && it.erledigt == null } ?: return
        Erinnerungen.zeigen(context, a)
    }
}

/** Knöpfe in den Benachrichtigungen: Aufgabe erledigt, auf morgen, Vorschläge übernehmen. */
class AufgabenAktion : BroadcastReceiver() {
    companion object {
        const val ERLEDIGT = "de.braininger.AUFGABE_ERLEDIGT"
        const val MORGEN = "de.braininger.AUFGABE_MORGEN"
        const val UEBERNEHMEN = "de.braininger.VORSCHLAEGE_UEBERNEHMEN"
        const val EXTRA_VORSCHLAEGE = "vorschlaege"
        const val EXTRA_MELDUNG = "meldung"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            ERLEDIGT, MORGEN -> {
                val id = intent.getStringExtra(Erinnerungen.EXTRA_ID) ?: return
                if (intent.action == ERLEDIGT) AufgabenSpeicher.abhaken(context, id) else AufgabenSpeicher.aufMorgen(context, id)
                nm.cancel(id.hashCode())
            }
            UEBERNEHMEN -> {
                val ids = intent.getStringArrayExtra(EXTRA_VORSCHLAEGE)?.toSet() ?: return
                AufgabenSpeicher.uebernehmen(context, ids)
                nm.cancel(intent.getIntExtra(EXTRA_MELDUNG, 4))
            }
        }
    }
}

/** Nach Neustart oder App-Update sind geplante Erinnerungen weg: neu planen. */
class NeustartEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Erinnerungen.allePlanen(context)
        TastenWache.pruefen(context) // nach einem App-Update schaltet Android die Bedienungshilfe manchmal ab
    }
}

/** Sichert die Liste in Braininger/Aufgaben/aufgaben.json (die App legt die Datei an, der Laptop liest sie). */
class AufgabenSicherung(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val NAME = "braininger-aufgaben-sichern"
        private const val VORLIEBEN = "aufgaben_sicherung"

        fun anstossen(context: Context) {
            val auftrag = OneTimeWorkRequestBuilder<AufgabenSicherung>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, auftrag)
        }

        private suspend fun dateiId(context: Context, api: DriveApi): Pair<String?, String> {
            val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
            val ordner = v.getString("ordner", null)
                ?: api.ordnerSichern("Aufgaben", api.ordnerSichern("Braininger", null)).also { v.edit().putString("ordner", it).apply() }
            val id = v.getString("datei", null) ?: api.dateiFinden("aufgaben.json", ordner)?.id
            id?.let { v.edit().putString("datei", it).apply() }
            return id to ordner
        }

        /** Beim Start: gibt es lokal keine Liste, aber eine Sicherung im Drive, wird sie zurückgeholt. */
        suspend fun wiederherstellen(context: Context) {
            if (AufgabenSpeicher.listenDatei(context).exists()) return
            val api = DriveApi(context)
            val (id, _) = dateiId(context, api)
            if (id != null) AufgabenSpeicher.wiederherstellen(context, api.herunterladen(id).decodeToString())
        }
    }

    override suspend fun doWork(): Result = try {
        val datei = AufgabenSpeicher.listenDatei(applicationContext)
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
                val neu = api.hochladen("aufgaben.json", ordner, "application/json", inhalt)
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
