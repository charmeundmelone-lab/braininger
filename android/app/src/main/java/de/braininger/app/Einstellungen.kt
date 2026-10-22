package de.braininger.app

import android.content.Context

/**
 * Persönliche Angaben für die Auswertung am Handy (am Laptop stehen sie in daten/persoenlich.json).
 * Bleiben nur auf dem Handy, nie im öffentlichen Projekt.
 */
object Einstellungen {
    private const val VORLIEBEN = "einstellungen"

    private fun v(context: Context) = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)

    fun name(context: Context): String = v(context).getString("name", "") ?: ""
    fun beschreibung(context: Context): String = v(context).getString("beschreibung", "") ?: ""

    fun speichern(context: Context, name: String, beschreibung: String) =
        v(context).edit().putString("name", name.trim()).putString("beschreibung", beschreibung.trim()).apply()

    /**
     * Wörterbuch (22.10.2026): Namen und Begriffe, so geschrieben, wie sie in den Notizen stehen sollen. Ein Begriff
     * je Zeile, Zeilen mit # sind Überschriften. Gleiche Regeln wie am Laptop (laptop/braininger/woerterbuch.py).
     */
    fun woerterbuch(context: Context): String = v(context).getString("woerterbuch", "") ?: ""

    fun woerterbuchSpeichern(context: Context, text: String) =
        v(context).edit().putString("woerterbuch", text.trim()).apply()

    fun begriffe(context: Context): List<String> {
        val gesehen = mutableSetOf<String>()
        return woerterbuch(context).lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && gesehen.add(it.lowercase()) }
    }

    /** Vorwissen für die Spracherkennung: die ersten Begriffe, höchstens 400 Zeichen (wie HOTWORDS_MAX_ZEICHEN). */
    fun erkennungsHinweis(context: Context): String {
        val teile = mutableListOf<String>()
        var laenge = 0
        for (b in begriffe(context)) {
            if (laenge + b.length + 2 > 400) break
            teile += b
            laenge += b.length + 2
        }
        return if (teile.isEmpty()) "" else teile.joinToString(", ") + "."
    }
}
