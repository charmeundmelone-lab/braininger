package de.braininger.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import de.braininger.app.claude.ClaudeAufruf
import de.braininger.app.claude.Schluessel
import org.json.JSONArray
import org.json.JSONObject

/** Ein Termin (eine Wiederholung eines Ereignisses) aus den Kalendern am Handy. Zeiten in Millisekunden. */
data class Termin(
    val ereignis: Long,
    val beginn: Long,
    val ende: Long,
    val titel: String,
    val ort: String,
    val kalender: String,
    val ganztags: Boolean,
) {
    /** Schlüssel für Jans Entscheidung „betrifft mich / nicht“: gilt für gleiche Termine im selben Kalender. */
    val muster: String get() = "$kalender|${titel.trim().lowercase()}"
    val schluessel: String get() = "$ereignis@$beginn"
}

/**
 * Liest die Kalender, die am Handy angemeldet sind (Grill 20.10.2026: alle Kalender des Show-Kontos, nur lesen).
 * Grundlage: Android Calendar Provider (developer.android.com/identity/providers/calendar-provider), Erlaubnis
 * READ_CALENDAR. Kein Internet und keine Google-Anmeldung nötig; Google Kalender gleicht im Hintergrund ab.
 */
object Kalender {
    fun erlaubt(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun termine(context: Context, von: Long, bis: Long): List<Termin> {
        if (!erlaubt(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, von)
            ContentUris.appendId(it, bis)
        }.build()
        val felder = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.VISIBLE, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val liste = mutableListOf<Termin>()
        try {
            context.contentResolver.query(uri, felder, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(7) == 0) continue // im Kalender ausgeblendet
                    if (c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    liste += Termin(
                        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
                        c.getString(5) ?: "", c.getInt(6) == 1,
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return liste.filter { it.titel.isNotBlank() }
    }

    fun heute(context: Context): List<Termin> {
        val beginn = Koerbe.tagBeginn(System.currentTimeMillis())
        return termine(context, beginn, beginn + 86_400_000L)
    }

    private fun istFeiertag(t: Termin) =
        t.kalender.contains("feiertag", true) || t.kalender.contains("holiday", true) || t.kalender.contains("week number", true)

    private fun istGig(t: Termin) = t.kalender.contains("gig", true)

    /** Lebensbereich für die Farbe (wie in der Auswertung): Gig-Kalender = Musik, sonst Familie. */
    fun bereich(t: Termin): String = when {
        istFeiertag(t) -> ""
        istGig(t) -> "musik"
        else -> "familie"
    }

    // ---------- Betrifft mich? ----------

    private const val VORLIEBEN = "kalender"

    private fun lesenMap(context: Context, feld: String): JSONObject = try {
        JSONObject(context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getString(feld, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun schreibenMap(context: Context, feld: String, j: JSONObject) =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString(feld, j.toString()).apply()

    /** Jans Tipp am Termin („Betrifft mich nicht“ / „Erinnern“, Frage 50). Gilt auch für gleiche Termine später. */
    fun festlegen(context: Context, t: Termin, betrifft: Boolean) {
        val j = lesenMap(context, "jan")
        j.put(t.muster, betrifft)
        schreibenMap(context, "jan", j)
        TerminErinnerung.planen(context)
    }

    /** null = noch nicht entschieden (Claude fragt beim nächsten Abgleich). */
    fun betrifftMich(context: Context, t: Termin): Boolean? {
        if (istFeiertag(t)) return false
        val jan = lesenMap(context, "jan")
        if (jan.has(t.muster)) return jan.optBoolean(t.muster)
        if (istGig(t)) return true
        val claude = lesenMap(context, "claude")
        if (claude.has(t.muster)) return claude.optBoolean(t.muster)
        return null
    }

    /** Ohne Claude (kein Schlüssel, kein Netz): im Zweifel erinnern (lieber einmal zu viel als einen Termin vergessen). */
    fun regel(context: Context, t: Termin): Boolean = betrifftMich(context, t) ?: true

    fun relevant(context: Context, t: Termin): Boolean = betrifftMich(context, t) ?: regel(context, t)

    /**
     * Lässt Claude für noch unbekannte Termine der nächsten 14 Tage einschätzen, ob sie Jan betreffen (Frage 23).
     * Jans frühere Tipps gehen als Beispiele mit. Kostet wenige Zehntel Cent und läuft nur für neue Termine.
     */
    suspend fun einschaetzen(context: Context) {
        if (!Schluessel.vorhanden(context) || !erlaubt(context)) return
        val jetzt = System.currentTimeMillis()
        val offen = termine(context, jetzt, jetzt + 14 * 86_400_000L)
            .filter { betrifftMich(context, it) == null }
            .distinctBy { it.muster }
            .take(40)
        if (offen.isEmpty()) return
        val jan = lesenMap(context, "jan")
        val beispiele = jan.keys().asSequence().take(30).joinToString("\n") { k ->
            val (kal, titel) = k.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            "- „$titel“ (Kalender $kal): " + if (jan.optBoolean(k)) "betrifft mich" else "betrifft mich nicht"
        }.ifBlank { "(noch keine)" }
        val name = Einstellungen.name(context).ifBlank { "der Nutzer" }
        val liste = offen.mapIndexed { i, t -> "$i: „${t.titel}“, Kalender „${t.kalender}“" }.joinToString("\n")
        val schema = JSONObject("""
            {"type":"object","properties":{"termine":{"type":"array","items":{"type":"object",
             "properties":{"nummer":{"type":"integer"},"betrifft":{"type":"boolean"}},
             "required":["nummer","betrifft"],"additionalProperties":false}}},
             "required":["termine"],"additionalProperties":false}
        """.trimIndent())
        val antwort = ClaudeAufruf.frage(
            context,
            system = listOf(
                "Du hilfst $name beim Überblick über seine Termine. Im Familienkalender und in den Kalendern anderer " +
                    "Familienmitglieder stehen auch Termine, die ihn nicht betreffen. " +
                    "Entscheide für jeden Termin, ob $name selbst hin muss, etwas tun muss (z. B. ein Kind fahren) oder " +
                    "ihn wissen muss. Gig-Termine betreffen ihn immer. Im Zweifel: betrifft ihn."
            ),
            inhalt = "Frühere Entscheidungen von $name:\n$beispiele\n\nTermine:\n$liste",
            schema = schema,
            maxTokens = 2_000,
            geschaetzt = 0.005,
        )
        val claude = lesenMap(context, "claude")
        val ergebnis = antwort.daten.optJSONArray("termine") ?: JSONArray()
        for (i in 0 until ergebnis.length()) {
            val e = ergebnis.optJSONObject(i) ?: continue
            offen.getOrNull(e.optInt("nummer", -1))?.let { claude.put(it.muster, e.optBoolean("betrifft", true)) }
        }
        // was Claude ausgelassen hat, gilt als „betrifft mich“ (im Zweifel erinnern), damit es nicht jedes Mal neu gefragt wird
        for (t in offen) if (!claude.has(t.muster)) claude.put(t.muster, true)
        schreibenMap(context, "claude", claude)
    }
}
