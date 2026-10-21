package de.braininger.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Farben der fünf Lebensbereiche (Grill 20.10.2026, Frage 26–28): gedämpfte, gefärbte Flächen auf Schwarz,
 * Schrift bleibt weiß. Bewusst kein Rot (Rot steht in Braininger nur für Aufnahme und echte Fehler).
 * 21.10.2026: Familie (grün) und Finanzen (blaugrün) waren am Handy kaum zu unterscheiden. Jetzt fünf klar
 * getrennte Farbtöne: Grün, Gelb, Blau, Violett, Orange (Kupfer). Jan prüft am Handy.
 */
object Bereiche {
    data class Bereich(val kennung: String, val name: String, val flaeche: Color, val punkt: Color)

    val alle = listOf(
        Bereich("familie", "Familie & Haushalt", Color(0xFF173A2B), Color(0xFF5FBF8F)),
        Bereich("musik", "Musik & Gigs", Color(0xFF38330F), Color(0xFFE3CC4A)),
        Bereich("ich", "Ich selbst", Color(0xFF16304A), Color(0xFF6FA8DC)),
        Bereich("projekte", "Projekte & Ideen", Color(0xFF33213F), Color(0xFFB48AD6)),
        Bereich("finanzen", "Finanzen Geschäft", Color(0xFF42260F), Color(0xFFE38A3E)),
    )

    private val nachKennung = alle.associateBy { it.kennung }

    fun von(kennung: String?): Bereich? = kennung?.let { nachKennung[it] }

    /** Fläche für einen Bereich; ohne Bereich ein neutrales Dunkelgrau. */
    fun flaeche(kennung: String?): Color = von(kennung)?.flaeche ?: Color(0xFF151515)
}
