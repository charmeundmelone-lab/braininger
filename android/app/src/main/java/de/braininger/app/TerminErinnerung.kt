package de.braininger.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Erinnerung an Termine, die Jan betreffen (Grill 20.10.2026):
 * - 1,5 Stunden vorher (Frage 22: „1–2 Stunden vorher hätte gerettet“)
 * - bleibt, bis Jan reagiert: Wegwischen zählt nicht, nach 5 Minuten ist sie wieder da (Frage 46; seit Android 14
 *   lassen sich auch „feste“ Benachrichtigungen wegwischen, developer.android.com/about/versions/14/behavior-changes-all)
 * - „Nicht stören“ am Handy wird beachtet: dann kommt sie, sobald der Modus aus ist (Frage 51)
 * Ganztägige Termine stehen nur im Heute-Überblick.
 */
object TerminErinnerung {
    const val KANAL = "termine"
    private const val VORHER_MS = 90 * 60_000L
    private const val WIEDER_MS = 5 * 60_000L
    private const val SPAETER_MS = 15 * 60_000L
    private const val NICHT_STOEREN_MS = 10 * 60_000L
    private const val VORLAUF_MS = 48 * 3_600_000L

    const val ZEIGEN = "de.braininger.TERMIN_ZEIGEN"
    const val OK = "de.braininger.TERMIN_OK"
    const val SPAETER = "de.braininger.TERMIN_SPAETER"
    const val WEG = "de.braininger.TERMIN_WEGGEWISCHT"
    const val NICHT_MICH = "de.braininger.TERMIN_NICHT_MICH"
    const val EXTRA_EREIGNIS = "ereignis"
    const val EXTRA_BEGINN = "beginn"

    fun kanalAnlegen(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KANAL, "Erinnerungen an Termine", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun schluessel(ereignis: Long, beginn: Long) = "$ereignis@$beginn"
    private fun code(ereignis: Long, beginn: Long) = schluessel(ereignis, beginn).hashCode()

    private fun absicht(context: Context, aktion: String, ereignis: Long, beginn: Long, extra: Int = 0) = PendingIntent.getBroadcast(
        context, code(ereignis, beginn) * 7 + extra,
        Intent(context, TerminEmpfaenger::class.java).setAction(aktion)
            .putExtra(EXTRA_EREIGNIS, ereignis).putExtra(EXTRA_BEGINN, beginn),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun vorlieben(context: Context) = context.getSharedPreferences("termin_erinnerung", Context.MODE_PRIVATE)

    fun erledigt(context: Context, ereignis: Long, beginn: Long) =
        schluessel(ereignis, beginn) in vorlieben(context).getStringSet("erledigt", emptySet()).orEmpty()

    private fun erledigtSetzen(context: Context, ereignis: Long, beginn: Long) {
        val v = vorlieben(context)
        val jetzt = System.currentTimeMillis()
        // alte Einträge (Beginn vor über 2 Tagen) aufräumen
        val alt = v.getStringSet("erledigt", emptySet()).orEmpty()
            .filter { (it.substringAfter("@").toLongOrNull() ?: 0L) > jetzt - 2 * 86_400_000L }
        v.edit().putStringSet("erledigt", (alt + schluessel(ereignis, beginn)).toSet()).apply()
    }

    private fun wecker(context: Context, zeit: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeit, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeit, pi)
    }

    /** Plant die Erinnerungen der nächsten 48 Stunden (bei Start, Abruf alle 15 min, nach Neustart). */
    fun planen(context: Context) {
        if (!Kalender.erlaubt(context)) return
        val jetzt = System.currentTimeMillis()
        val v = vorlieben(context)
        val vorher = v.getStringSet("geplant", emptySet()).orEmpty()
        val geplant = mutableSetOf<String>()
        for (t in Kalender.termine(context, jetzt, jetzt + VORLAUF_MS)) {
            if (t.ganztags || t.beginn <= jetzt || !Kalender.relevant(context, t) || erledigt(context, t.ereignis, t.beginn)) continue
            val zeit = maxOf(t.beginn - VORHER_MS, jetzt + 60_000L)
            wecker(context, zeit, absicht(context, ZEIGEN, t.ereignis, t.beginn))
            geplant += schluessel(t.ereignis, t.beginn)
        }
        // nicht mehr gültige (verschoben, gelöscht, „betrifft mich nicht“) abmelden
        val am = context.getSystemService(AlarmManager::class.java)
        for (s in vorher - geplant) {
            val e = s.substringBefore("@").toLongOrNull() ?: continue
            val b = s.substringAfter("@").toLongOrNull() ?: continue
            am.cancel(absicht(context, ZEIGEN, e, b))
        }
        v.edit().putStringSet("geplant", geplant).apply()
    }

    private fun termin(context: Context, ereignis: Long, beginn: Long): Termin? =
        Kalender.termine(context, beginn - 60_000L, beginn + 60_000L).firstOrNull { it.ereignis == ereignis && it.beginn == beginn }

    fun zeigen(context: Context, ereignis: Long, beginn: Long) {
        val jetzt = System.currentTimeMillis()
        if (erledigt(context, ereignis, beginn) || jetzt > beginn + 15 * 60_000L) return
        val t = termin(context, ereignis, beginn) ?: return // verschoben oder gelöscht
        if (!Kalender.relevant(context, t)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            wecker(context, jetzt + NICHT_STOEREN_MS, absicht(context, ZEIGEN, ereignis, beginn))
            return
        }
        kanalAnlegen(context)
        val minuten = ((beginn - jetzt) / 60_000L).coerceAtLeast(0)
        val wann = when {
            minuten < 1 -> "Jetzt"
            minuten < 60 -> "In $minuten Min"
            else -> "In ${minuten / 60}:${String.format("%02d", minuten % 60)} Std"
        }
        val oeffnen = PendingIntent.getActivity(
            context, 60, Intent(context, MainActivity::class.java).putExtra(Tagesrhythmus.EXTRA_REITER, "heute"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("$wann: ${t.titel}")
            .setContentText(Tagesrhythmus.uhrzeit(t.beginn) + if (t.ort.isNotBlank()) " · ${t.ort}" else "")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            // 15 Minuten nach Beginn ist sie überholt und geht von selbst weg (sonst stand am 20.10. um 22:20 noch „In 4 Min“ für 20:15)
            .setTimeoutAfter(maxOf(beginn + 15 * 60_000L - jetzt, 60_000L))
            .setContentIntent(oeffnen)
            .setDeleteIntent(absicht(context, WEG, ereignis, beginn, 3))
            .addAction(0, "OK, BIN DRAN", absicht(context, OK, ereignis, beginn, 1))
            .addAction(0, "SPÄTER NOCHMAL", absicht(context, SPAETER, ereignis, beginn, 2))
            .addAction(0, "BETRIFFT MICH NICHT", absicht(context, NICHT_MICH, ereignis, beginn, 4))
            .build()
        try {
            nm.notify(code(ereignis, beginn), n)
        } catch (_: SecurityException) {
        }
    }

    fun aktion(context: Context, aktion: String, ereignis: Long, beginn: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val jetzt = System.currentTimeMillis()
        when (aktion) {
            ZEIGEN -> zeigen(context, ereignis, beginn)
            OK -> {
                erledigtSetzen(context, ereignis, beginn)
                nm.cancel(code(ereignis, beginn))
            }
            SPAETER -> {
                nm.cancel(code(ereignis, beginn))
                wecker(context, jetzt + SPAETER_MS, absicht(context, ZEIGEN, ereignis, beginn))
            }
            WEG -> if (!erledigt(context, ereignis, beginn)) {
                wecker(context, jetzt + WIEDER_MS, absicht(context, ZEIGEN, ereignis, beginn))
            }
            NICHT_MICH -> {
                erledigtSetzen(context, ereignis, beginn)
                nm.cancel(code(ereignis, beginn))
                termin(context, ereignis, beginn)?.let { Kalender.festlegen(context, it, false) }
            }
        }
    }
}

class TerminEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val e = intent.getLongExtra(TerminErinnerung.EXTRA_EREIGNIS, -1)
        val b = intent.getLongExtra(TerminErinnerung.EXTRA_BEGINN, -1)
        if (e < 0 || b < 0) return
        TerminErinnerung.aktion(context, intent.action ?: return, e, b)
    }
}
