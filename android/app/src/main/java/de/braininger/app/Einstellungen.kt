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
}
