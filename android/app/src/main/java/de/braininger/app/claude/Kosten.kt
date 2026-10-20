package de.braininger.app.claude

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zählt, was die Claude-Aufrufe vom Handy kosten, und hält den Monatsdeckel ein (Grill 20.10.2026: 5 € im Monat).
 * Gerechnet wird vorsichtig in Dollar: 5,00 $ sind etwas weniger als 5 €.
 * Preise Claude Haiku 5.5 laut platform.claude.com/docs/en/about-claude/pricing (abgerufen 20.10.2026),
 * je Million Tokens; Prompts über 100.000 Tokens kosten das Fünffache.
 */
object Kosten {
    const val DECKEL_DOLLAR = 5.00
    private const val VORLIEBEN = "kosten"

    private data class Preise(val eingabe: Double, val cacheSchreiben: Double, val cacheLesen: Double, val ausgabe: Double)

    private val NORMAL = Preise(0.10, 0.125, 0.01, 0.50)
    private val LANG = Preise(0.50, 0.625, 0.05, 2.50)

    private fun monat() = SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date())

    fun berechnen(eingabe: Long, cacheSchreiben: Long, cacheLesen: Long, ausgabe: Long): Double {
        val p = if (eingabe + cacheSchreiben + cacheLesen > 100_000) LANG else NORMAL
        return (eingabe * p.eingabe + cacheSchreiben * p.cacheSchreiben + cacheLesen * p.cacheLesen + ausgabe * p.ausgabe) / 1e6
    }

    /** Ausgaben im laufenden Monat in Dollar. */
    fun diesenMonat(context: Context): Double {
        val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        return if (v.getString("monat", null) == monat()) v.getLong("mikrodollar", 0) / 1e6 else 0.0
    }

    fun buchen(context: Context, dollar: Double) {
        val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        val bisher = if (v.getString("monat", null) == monat()) v.getLong("mikrodollar", 0) else 0L
        v.edit().putString("monat", monat()).putLong("mikrodollar", bisher + (dollar * 1e6).toLong()).apply()
    }

    /** Ist noch Platz für einen Aufruf, der bis zu [geschaetzt] Dollar kostet? */
    fun frei(context: Context, geschaetzt: Double = 0.02): Boolean = diesenMonat(context) + geschaetzt <= DECKEL_DOLLAR

    /** Wurde der Hinweis „Deckel erreicht“ in diesem Monat schon gezeigt? (nur einmal, ruhig) */
    fun deckelHinweisNoetig(context: Context): Boolean {
        val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        if (v.getString("hinweis_monat", null) == monat()) return false
        v.edit().putString("hinweis_monat", monat()).apply()
        return true
    }
}
