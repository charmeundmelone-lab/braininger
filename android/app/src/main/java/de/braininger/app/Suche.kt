package de.braininger.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import de.braininger.app.drive.Ergebnis
import java.util.Locale

/**
 * Stichwortsuche über alle Aufnahmen, direkt auf dem Handy (auch ohne Netz).
 * Durchsucht Kurztext, Notizen, den ganzen Text und die Sprechernamen. Mehrere Wörter: alle müssen vorkommen.
 */
object Suche {
    private val markdown = Regex("""(^|\s)(#+ |- \[[ xX]\] |[-*] )|\*\*""")

    private fun klein(s: String) = s.lowercase(Locale.GERMANY)

    fun woerter(anfrage: String) = klein(anfrage).split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Ausschnitt rund um den ersten Treffer, oder null, wenn nicht alle Wörter vorkommen. */
    fun treffer(aufnahme: Aufnahme, ergebnis: Ergebnis?, anfrage: String): String? {
        val woerter = woerter(anfrage)
        if (woerter.isEmpty()) return ""
        val teile = buildList {
            add(aufnahme.modus.anzeige)
            addAll(ergebnis?.sprecher?.values.orEmpty())
            ergebnis?.kurz?.let(::add)
            ergebnis?.notizen?.forEach { add(it.titel); add(it.inhalt) }
            ergebnis?.abschnitte?.forEach { add(it.text) }
        }
        val gesamt = teile.joinToString(" · ") { markdown.replace(it, " ").replace(Regex("\\s+"), " ").trim() }
        val gesamtKlein = klein(gesamt)
        if (!woerter.all { it in gesamtKlein }) return null
        // Ausschnitt um das seltenste (längste) Wort, das meist am meisten aussagt
        val wort = woerter.maxBy { it.length }
        val i = gesamtKlein.indexOf(wort)
        val von = (i - 50).coerceAtLeast(0)
        val bis = (i + wort.length + 70).coerceAtMost(gesamt.length)
        return (if (von > 0) "…" else "") + gesamt.substring(von, bis).trim() + (if (bis < gesamt.length) "…" else "")
    }

    /** Suchwörter im Ausschnitt fett. */
    fun hervorheben(text: String, anfrage: String): AnnotatedString = buildAnnotatedString {
        val woerter = woerter(anfrage)
        val textKlein = klein(text)
        var pos = 0
        while (pos < text.length) {
            val naechster = woerter.mapNotNull { w -> textKlein.indexOf(w, pos).takeIf { it >= 0 }?.let { it to w } }
                .minByOrNull { it.first }
            if (naechster == null) {
                append(text.substring(pos)); break
            }
            val (start, w) = naechster
            append(text.substring(pos, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)) {
                append(text.substring(start, start + w.length))
            }
            pos = start + w.length
        }
    }
}
