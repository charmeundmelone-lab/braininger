package de.braininger.app.claude

import android.content.Context
import de.braininger.app.Aufgabe
import de.braininger.app.Schritt
import org.json.JSONObject

/**
 * „Aufgabe kleiner machen“ (Grill 20.10.2026, Frage 29 und 32): Claude zerlegt eine Aufgabe in wenige kleine
 * erste Schritte. Sie stehen als Häkchen unter der Aufgabe. Kostet ca. 0,01 Cent je Aufgabe.
 */
object Zerlegen {
    private val SCHEMA = JSONObject(
        """{"type":"object","properties":{"schritte":{"type":"array","items":{"type":"string"}}},
            "required":["schritte"],"additionalProperties":false}"""
    )

    suspend fun schritte(context: Context, a: Aufgabe): List<Schritt> {
        val antwort = ClaudeAufruf.frage(
            context,
            system = listOf(
                "Du hilfst jemandem mit ADHS, eine Aufgabe anzufangen. Zerlege sie in 3 bis 5 kleine, konkrete Schritte. " +
                    "Der erste Schritt ist so klein, dass er in 2 Minuten geht (z. B. „Laptop aufklappen und Mail öffnen“). " +
                    "Jeder Schritt ein kurzer Satz im Imperativ, ohne Nummern, ohne Erklärungen, auf Deutsch."
            ),
            inhalt = "Aufgabe: ${a.text}",
            schema = SCHEMA,
            maxTokens = 1_500,
            geschaetzt = 0.002,
        )
        val s = antwort.daten.optJSONArray("schritte") ?: return emptyList()
        return (0 until s.length()).map { s.optString(it).trim() }.filter { it.isNotEmpty() }.take(6).map { Schritt(it) }
    }
}
