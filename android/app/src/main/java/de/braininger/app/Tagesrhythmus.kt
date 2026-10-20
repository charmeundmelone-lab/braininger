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
import de.braininger.app.claude.ClaudeAufruf
import de.braininger.app.claude.Schluessel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Was Braininger über den Tag von sich aus meldet (Grill 20.10.2026), immer ruhig und ohne Druck:
 * - Morgen-Nachricht beim ersten Entsperren ab 5 Uhr: höchstens 5 Punkte, Termine und Aufgaben nach Tageszeit
 * - nachmittags ein sanfter Stups „Heute noch offen: n“
 * - sonntags der Wochenblick auf drei alte Aufgaben aus „Irgendwann“
 * Termin-Erinnerungen stehen in [TerminErinnerung].
 */
object Tagesrhythmus {
    const val KANAL = "tagesrhythmus"
    const val EXTRA_REITER = "reiter"
    private const val STUPS_STUNDE = 15
    private const val WOCHENBLICK_STUNDE = 18
    private const val MORGEN_AB = 5
    private const val MAX_PUNKTE = 5
    private const val ID_MORGEN = 31
    private const val ID_STUPS = 32
    private const val ID_WOCHE = 33
    const val STUPS = "de.braininger.STUPS"
    const val WOCHENBLICK = "de.braininger.WOCHENBLICK"
    const val MORGEN_VORBEREITEN = "de.braininger.MORGEN_VORBEREITEN"

    fun kanalAnlegen(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KANAL, "Tagesüberblick", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun absicht(context: Context, aktion: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, TagesrhythmusEmpfaenger::class.java).setAction(aktion),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun naechstes(stunde: Int, minute: Int = 0, wochentag: Int? = null): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, stunde); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val jetzt = System.currentTimeMillis()
        while (c.timeInMillis <= jetzt || (wochentag != null && c.get(Calendar.DAY_OF_WEEK) != wochentag)) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun wecker(context: Context, zeit: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeit, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeit, pi)
    }

    /** Plant die festen Zeitpunkte neu (nach Start, Neustart, Update und jeder Änderung an der Liste). */
    fun planen(context: Context) {
        wecker(context, naechstes(STUPS_STUNDE), absicht(context, STUPS, 41))
        wecker(context, naechstes(WOCHENBLICK_STUNDE, wochentag = Calendar.SUNDAY), absicht(context, WOCHENBLICK, 42))
        wecker(context, naechstes(4, 30), absicht(context, MORGEN_VORBEREITEN, 43))
        TerminErinnerung.planen(context)
    }

    private fun oeffnen(context: Context, reiter: String, code: Int) = PendingIntent.getActivity(
        context, code, Intent(context, MainActivity::class.java).putExtra(EXTRA_REITER, reiter)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun zeigen(context: Context, id: Int, titel: String, text: String, reiter: String) {
        kanalAnlegen(context)
        val n = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen(context, reiter, 50 + id))
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    // ---------- Heute-Überblick (auch für die Startseite) ----------

    /** Ein Punkt im Heute-Überblick: Termin (mit Uhrzeit) oder Aufgabe (ohne). */
    data class Punkt(val zeit: Long?, val text: String, val bereich: String, val termin: Termin? = null, val aufgabe: Aufgabe? = null)

    /**
     * Termine, die Jan betreffen, und Aufgaben aus „heute“, nach Tageszeit gemischt (Frage 15). Aufgaben haben keine
     * Uhrzeit (Frage 16/17); sie stehen hinter den Terminen. Ganztägige Termine stehen ganz oben.
     */
    fun heute(context: Context): List<Punkt> {
        val jetzt = System.currentTimeMillis()
        val termine = Kalender.heute(context).filter { Kalender.relevant(context, it) && (it.ganztags || it.ende > jetzt) }
        val ganztags = termine.filter { it.ganztags }.map { Punkt(null, it.titel, Kalender.bereich(it), termin = it) }
        val mitZeit = termine.filter { !it.ganztags }.map { Punkt(it.beginn, it.titel, Kalender.bereich(it), termin = it) }
        val aufgaben = AufgabenSpeicher.imKorb(context, Koerbe.HEUTE).map { Punkt(null, it.text, it.bereich, aufgabe = it) }
        return ganztags + mitZeit + aufgaben
    }

    fun uhrzeit(ms: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ms))

    // ---------- Morgen-Nachricht ----------

    private fun vorlieben(context: Context) = context.getSharedPreferences("tagesrhythmus", Context.MODE_PRIVATE)
    private fun tag(ms: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))

    /** Vom TastenDienst beim Entsperren aufgerufen. Einmal am Tag, frühestens ab 5 Uhr (Frage 10 und 49). */
    fun entsperrt(context: Context) {
        val c = Calendar.getInstance()
        if (c.get(Calendar.HOUR_OF_DAY) < MORGEN_AB) return
        val v = vorlieben(context)
        if (v.getString("morgen_gezeigt", null) == tag()) return
        v.edit().putString("morgen_gezeigt", tag()).apply()
        morgenZeigen(context)
    }

    fun morgenZeigen(context: Context) {
        val punkte = heute(context)
        val einleitung = vorlieben(context).getString("einleitung_${tag()}", null)
        val zeilen = punkte.take(MAX_PUNKTE).map { p -> (p.zeit?.let { uhrzeit(it) + "  " } ?: "• ") + p.text }
        val rest = punkte.size - MAX_PUNKTE
        val text = buildString {
            if (!einleitung.isNullOrBlank()) append(einleitung).append("\n")
            if (zeilen.isEmpty()) append("Heute steht nichts Festes an.")
            else append(zeilen.joinToString("\n"))
            if (rest > 0) append("\n+ $rest weitere in der App")
        }
        zeigen(context, ID_MORGEN, "Guten Morgen", text, "heute")
    }

    /** Um 4:30: Claude schreibt eine freundliche Einleitungszeile für den Tag (kostet ca. 0,01 Cent). */
    suspend fun einleitungSchreiben(context: Context) {
        if (!Schluessel.vorhanden(context)) return
        val punkte = heute(context)
        if (punkte.isEmpty()) return
        val liste = punkte.joinToString("\n") { p -> (p.zeit?.let { uhrzeit(it) + " " } ?: "") + p.text }
        val schema = JSONObject("""{"type":"object","properties":{"satz":{"type":"string"}},"required":["satz"],"additionalProperties":false}""")
        val antwort = ClaudeAufruf.frage(
            context,
            system = listOf(
                "Du schreibst einen einzigen kurzen, ruhigen Satz zum Start in den Tag für jemanden mit ADHS. " +
                    "Kein Druck, keine Ausrufezeichen, keine Aufzählung, höchstens 15 Wörter, auf Deutsch, du-Form."
            ),
            inhalt = "Heute stehen an:\n$liste",
            schema = schema,
            maxTokens = 600,
            geschaetzt = 0.001,
        )
        antwort.daten.optString("satz").takeIf { it.isNotBlank() }?.let {
            vorlieben(context).edit().putString("einleitung_${tag()}", it.take(140)).apply()
        }
    }

    // ---------- Stups und Wochenblick ----------

    fun stups(context: Context) {
        val offen = AufgabenSpeicher.imKorb(context, Koerbe.HEUTE).size
        if (offen > 0) zeigen(context, ID_STUPS, "Heute noch offen: $offen", "Wenn du magst, schau kurz rein.", "aufgaben")
    }

    private fun dreiAelteste(context: Context): List<Aufgabe> =
        AufgabenSpeicher.imKorb(context, Koerbe.IRGENDWANN).sortedBy { it.erstellt }.take(3)

    /**
     * Die drei ältesten Aufgaben aus „Irgendwann“ vom letzten Sonntag (Frage 59). Was Jan entschieden hat (geholt,
     * behalten, gelöscht), fällt heraus; sind alle drei entschieden, ist der Wochenblick fertig.
     */
    fun wochenblickAufgaben(context: Context): List<Aufgabe> {
        val v = vorlieben(context)
        val ids = v.getStringSet("wochenblick_ids", emptySet()).orEmpty()
        val seit = v.getLong("wochenblick_zeit", 0L)
        return AufgabenSpeicher.imKorb(context, Koerbe.IRGENDWANN).filter { it.id in ids && it.erstellt < seit }
    }

    fun wochenblick(context: Context) {
        val alte = dreiAelteste(context)
        if (alte.isEmpty()) return
        vorlieben(context).edit().putString("wochenblick", tag())
            .putStringSet("wochenblick_ids", alte.map { it.id }.toSet())
            .putLong("wochenblick_zeit", System.currentTimeMillis()).apply()
        zeigen(context, ID_WOCHE, "Wochenblick", "Drei alte Aufgaben: holen, behalten oder weg?\n" +
            alte.joinToString("\n") { "• " + it.text }, "aufgaben")
    }

    /** Wochenblick in der App zeigen, bis Jan alle drei entschieden hat (ab Sonntagabend, eine Woche lang). */
    fun wochenblickOffen(context: Context): Boolean {
        val seit = vorlieben(context).getString("wochenblick", null) ?: return false
        val d = try { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(seit)!!.time } catch (_: Exception) { return false }
        return System.currentTimeMillis() - d < 7 * 86_400_000L
    }

    fun wochenblickFertig(context: Context) = vorlieben(context).edit().remove("wochenblick").apply()
}

class TagesrhythmusEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Tagesrhythmus.STUPS -> Tagesrhythmus.stups(context)
            Tagesrhythmus.WOCHENBLICK -> Tagesrhythmus.wochenblick(context)
            Tagesrhythmus.MORGEN_VORBEREITEN -> WorkManager.getInstance(context).enqueueUniqueWork(
                "braininger-morgen", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MorgenVorbereitung>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
        Tagesrhythmus.planen(context) // nächsten Termin planen
    }
}

/** Nachts: Termine einschätzen lassen und die Einleitung der Morgen-Nachricht schreiben. */
class MorgenVorbereitung(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            Kalender.einschaetzen(applicationContext)
        } catch (_: Exception) {
        }
        try {
            Tagesrhythmus.einleitungSchreiben(applicationContext)
        } catch (_: Exception) {
        }
        TerminErinnerung.planen(applicationContext)
        return Result.success()
    }
}
