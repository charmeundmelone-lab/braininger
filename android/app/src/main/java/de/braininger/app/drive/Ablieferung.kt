package de.braininger.app.drive

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.braininger.app.Ablage
import de.braininger.app.Aufnahme
import de.braininger.app.MainActivity
import de.braininger.app.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Einfache Hinweis-Benachrichtigung, die die App öffnet. */
object Hinweise {
    fun zeigen(context: Context, id: Int, titel: String, text: String) {
        val oeffnen = PendingIntent.getActivity(
            context, 10 + id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Meldet, wenn Aufnahmen seit über 2 Stunden im Drive liegen und der Laptop sie nicht anfasst (z. B. weil er aus ist). */
object WarteHinweis {
    private const val GRENZE_MS = 2 * 3_600_000L

    fun pruefen(context: Context) {
        val jetzt = System.currentTimeMillis()
        val wartend = Ablage.alle(context).filter { a ->
            val ende = a.ende ?: return@filter false
            UploadStand.lesen(a.ordner).abgeschlossen && jetzt - ende > GRENZE_MS &&
                (Ergebnis.lesen(a.ordner)?.status ?: "hochgeladen") == "hochgeladen"
        }.map { it.ordner.name }.sorted()
        val vorlieben = context.getSharedPreferences("hinweise", Context.MODE_PRIVATE)
        val schluessel = wartend.joinToString(",")
        if (wartend.isNotEmpty() && schluessel != vorlieben.getString("wartend", "")) {
            Hinweise.zeigen(
                context, 11, "Laptop aus?",
                "${wartend.size} Aufnahme(n) warten seit über 2 Stunden auf den Laptop. Ist er an und im Internet?",
            )
        }
        vorlieben.edit().putString("wartend", schluessel).apply()
    }
}

/**
 * Jede fertige Aufnahme zusätzlich als Google-Dokument in Braininger/Notizen (zum Lesen am PC oder Tablet).
 * Die App lädt HTML hoch, Drive wandelt es in ein Google Doc um. Ändern sich die Notizen, wird das Dokument
 * ersetzt (die App darf nur löschen, was sie selbst angelegt hat). Je Aufnahme merkt sie sich doc.json.
 */
object GoogleDocs {
    private const val DOC = "application/vnd.google-apps.document"

    suspend fun aktualisieren(context: Context) {
        var api: DriveApi? = null
        var ordnerId: String? = null
        for (a in Ablage.alle(context)) {
            val e = Ergebnis.lesen(a.ordner) ?: continue
            if (e.status != "fertig" || e.notizen.isEmpty()) continue
            if (e.notizen.all { it.titel == "Kein verwertbarer Inhalt" }) continue
            val html = html(a, e)
            val fingerabdruck = html.hashCode().toString()
            val docDatei = File(a.ordner, "doc.json")
            val bisher = try { JSONObject(docDatei.readText()) } catch (_: Exception) { JSONObject() }
            if (bisher.optString("fingerabdruck") == fingerabdruck) continue

            val drive = api ?: DriveApi(context).also { api = it }
            val ziel = ordnerId ?: drive.ordnerSichern("Notizen", drive.ordnerSichern("Braininger", null)).also { ordnerId = it }
            bisher.optString("id").takeIf { it.isNotBlank() }?.let { drive.loeschen(it) }
            val name = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.GERMANY).format(Date(a.start)) + " " + e.notizen.first().titel
            val id = drive.hochladen(name.take(120), ziel, "text/html", html.toByteArray(), zielTyp = DOC)
            docDatei.writeText(JSONObject().put("id", id).put("fingerabdruck", fingerabdruck).toString())
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String {
        val teile = esc(s).split("**")
        return teile.mapIndexed { i, t -> if (i % 2 == 1 && i < teile.size - 1) "<b>$t</b>" else t }.joinToString("")
    }

    /** Einfaches Markdown → HTML: Überschriften, Listen, Checkboxen, fett. */
    private fun markdown(md: String): String {
        val aus = StringBuilder()
        var inListe = false
        fun listeZu() {
            if (inListe) {
                aus.append("</ul>"); inListe = false
            }
        }
        for (roh in md.lines()) {
            val z = roh.trim()
            when {
                z.isBlank() -> listeZu()
                z.startsWith("#") -> {
                    listeZu(); aus.append("<h3>").append(inline(z.trimStart('#', ' '))).append("</h3>")
                }
                z.startsWith("- ") || z.startsWith("* ") -> {
                    if (!inListe) {
                        aus.append("<ul>"); inListe = true
                    }
                    val inhalt = z.drop(2)
                    val text = when {
                        inhalt.startsWith("[ ] ") -> "☐ " + inhalt.drop(4)
                        inhalt.startsWith("[x] ", ignoreCase = true) -> "☑ " + inhalt.drop(4)
                        else -> inhalt
                    }
                    aus.append("<li>").append(inline(text)).append("</li>")
                }
                else -> {
                    listeZu(); aus.append("<p>").append(inline(z)).append("</p>")
                }
            }
        }
        listeZu()
        return aus.toString()
    }

    private fun html(a: Aufnahme, e: Ergebnis): String {
        val datum = SimpleDateFormat("EEEE, dd.MM.yyyy, HH:mm", Locale.GERMANY).format(Date(a.start))
        val b = StringBuilder("<html><head><meta charset=\"utf-8\"></head><body>")
        b.append("<p><i>").append(esc(a.modus.anzeige)).append(" · ").append(esc(datum)).append("</i></p>")
        e.kurz?.let { b.append("<p>").append(inline(it)).append("</p>") }
        for (n in e.notizen) {
            b.append("<h2>").append(esc(n.titel)).append("</h2><p><i>").append(esc(Vorlagen.name(n.vorlage))).append("</i></p>")
            b.append(markdown(n.inhalt))
        }
        return b.append("</body></html>").toString()
    }
}
