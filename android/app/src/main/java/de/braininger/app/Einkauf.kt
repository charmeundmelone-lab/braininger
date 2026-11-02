package de.braininger.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Ein Eintrag der Einkaufsliste. erledigt: abgehakt (Millisekunden) oder null; gefragt: letzte Frage „Noch nötig?“.
 * aufnahme = Aufnahme, aus der der Eintrag kam (leer bei „oft gekauft“).
 */
data class Einkauf(
    val id: String,
    val sache: String,
    val details: String,
    val menge: String,
    val laden: String,
    val abteilung: String,
    val werHolt: String,
    val aufnahme: String,
    val erstellt: Long,
    val erledigt: Long? = null,
    val gefragt: Long? = null,
)

/**
 * Einkaufsliste (Grill Einkauf 2.11.2026). Nur am Handy (files/einkauf/), keine Sicherung im Drive (Frage 28).
 * Claude fügt nur hinzu (Frage 21): Einträge hakt und löscht Jan selbst. Ausnahmen: eine fehlende Menge wird ergänzt
 * (Frage 22), ein abgehakter Eintrag wird wieder offen, wenn er wieder gebraucht wird (Frage 23).
 */
object EinkaufSpeicher {
    const val SUPERMARKT = "Supermarkt"

    /** Feste Reihenfolge im Supermarkt (Frage 29), gleiche Kennungen wie ABTEILUNGEN in laptop/braininger/auswertung.py. */
    val ABTEILUNGEN = listOf("obst_gemuese", "brot", "kuehlregal", "fleisch_fisch", "vorrat", "suesses", "getraenke",
        "tiefkuehl", "drogerie_haushalt", "sonstiges")

    /** Läden zum Verschieben (Frage 8), dazu alle, die schon auf der Liste stehen. */
    val UEBLICHE_LAEDEN = listOf(SUPERMARKT, "Drogerie", "Bäcker", "Metzger", "Apotheke", "Baumarkt", "Getränkemarkt", "Wochenmarkt")

    private const val TAG_MS = 24 * 3_600_000L
    const val NOCH_NOETIG_TAGE = 14 // Frage 31
    private const val NOCH_NOETIG_ABSTAND_MS = 7 * TAG_MS // höchstens einmal pro Woche (Frage 32)
    private const val NOCH_NOETIG_MAX = 5
    private const val ID_ANSAGE = 70
    private const val ID_NOCH_NOETIG = 71 // 71 bis 75

    private val _stand = MutableStateFlow(0)
    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu liest. */
    val stand: StateFlow<Int> = _stand

    private fun ordner(context: Context) = File(context.filesDir, "einkauf").apply { mkdirs() }
    private fun listenDatei(context: Context) = File(ordner(context), "liste.json")
    private fun gesehenDatei(context: Context) = File(ordner(context), "gesehen.json")
    private fun laedenDatei(context: Context) = File(ordner(context), "laeden.json")
    private fun haeufigDatei(context: Context) = File(ordner(context), "haeufig.json")
    private fun vorlieben(context: Context) = context.getSharedPreferences("einkauf", Context.MODE_PRIVATE)

    /** „Milch“, „milch “ und „Milch.“ sind dieselbe Sache. */
    fun schluessel(sache: String) = sache.trim().trimEnd('.', '!', ',').lowercase(Locale.GERMAN)

    private fun schreiben(datei: File, inhalt: String) {
        val tmp = File(datei.path + ".tmp")
        tmp.writeText(inhalt)
        tmp.renameTo(datei)
    }

    private fun objektLesen(datei: File): JSONObject = try { JSONObject(datei.readText()) } catch (_: Exception) { JSONObject() }

    // ---------- Liste ----------

    fun alle(context: Context): List<Einkauf> {
        val a = try { JSONArray(listenDatei(context).readText()) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Einkauf(
                o.optString("id"), o.optString("sache"), o.optString("details"), o.optString("menge"),
                o.optString("laden").ifBlank { SUPERMARKT }, o.optString("abteilung").takeIf { it in ABTEILUNGEN } ?: "sonstiges",
                o.optString("wer_holt"), o.optString("aufnahme"), o.optLong("erstellt"),
                o.optLong("erledigt").takeIf { it > 0 }, o.optLong("gefragt").takeIf { it > 0 },
            )
        }.filter { it.id.isNotBlank() && it.sache.isNotBlank() }
    }

    fun offene(context: Context) = alle(context).filter { it.erledigt == null }

    @Synchronized
    private fun speichern(context: Context, liste: List<Einkauf>) {
        schreiben(listenDatei(context), JSONArray(liste.map {
            JSONObject().put("id", it.id).put("sache", it.sache).put("details", it.details).put("menge", it.menge)
                .put("laden", it.laden).put("abteilung", it.abteilung).put("wer_holt", it.werHolt)
                .put("aufnahme", it.aufnahme).put("erstellt", it.erstellt)
                .put("erledigt", it.erledigt ?: 0L).put("gefragt", it.gefragt ?: 0L)
        }).toString(1))
        _stand.value++
    }

    @Synchronized
    private fun aendern(context: Context, f: (MutableList<Einkauf>) -> Unit) {
        val liste = alle(context).toMutableList()
        f(liste)
        speichern(context, liste)
    }

    /** Offene Einträge nach Laden: Supermarkt zuerst, dann die übrigen nach Name; innen nach Abteilung. */
    fun nachLaden(liste: List<Einkauf>): List<Pair<String, List<Einkauf>>> =
        liste.groupBy { it.laden }.toList()
            .sortedWith(compareBy<Pair<String, List<Einkauf>>> { it.first != SUPERMARKT }.thenBy { it.first.lowercase(Locale.GERMAN) })
            .map { (laden, l) -> laden to l.sortedWith(compareBy<Einkauf> { ABTEILUNGEN.indexOf(it.abteilung) }.thenBy { it.erstellt }) }

    /** Abhaken oder wieder offen. Abgehaktes zählt für „oft gekauft“. */
    fun umschalten(context: Context, id: String) {
        var gekauft: Einkauf? = null
        aendern(context) { l ->
            l.replaceAll {
                if (it.id != id) it
                else if (it.erledigt == null) it.copy(erledigt = System.currentTimeMillis()).also { e -> gekauft = e }
                else it.copy(erledigt = null)
            }
        }
        gekauft?.let { merkenGekauft(context, it) }
    }

    /** Wischen = weg (Frage 25). Rückgabe für RÜCKGÄNGIG. */
    fun loeschen(context: Context, id: String): Einkauf? {
        var weg: Einkauf? = null
        aendern(context) { l -> weg = l.firstOrNull { it.id == id }; l.removeAll { it.id == id } }
        return weg
    }

    fun zurueck(context: Context, e: Einkauf) = aendern(context) { l -> if (l.none { it.id == e.id }) l += e }

    /** LISTE LEEREN (Frage 11): nimmt nur Abgehaktes weg. Rückgabe für RÜCKGÄNGIG. */
    fun leeren(context: Context): List<Einkauf> {
        var weg = emptyList<Einkauf>()
        aendern(context) { l -> weg = l.filter { it.erledigt != null }; l.removeAll { it.erledigt != null } }
        return weg
    }

    fun alleZurueck(context: Context, liste: List<Einkauf>) = aendern(context) { l ->
        val da = l.map { it.id }.toSet()
        l += liste.filter { it.id !in da }
    }

    /** In einen anderen Laden schieben; Braininger merkt sich das für diese Sache (Frage 8). */
    fun verschieben(context: Context, id: String, laden: String) {
        val e = alle(context).firstOrNull { it.id == id } ?: return
        aendern(context) { l -> l.replaceAll { if (it.id == id) it.copy(laden = laden) else it } }
        val j = objektLesen(laedenDatei(context))
        j.put(schluessel(e.sache), laden)
        schreiben(laedenDatei(context), j.toString(1))
    }

    private fun gelernterLaden(context: Context, sache: String): String? =
        objektLesen(laedenDatei(context)).optString(schluessel(sache)).ifBlank { null }

    /** Läden, die es auf der Liste gibt oder je gab, dazu die üblichen. */
    fun laeden(context: Context): List<String> {
        val gelernt = objektLesen(laedenDatei(context)).let { j -> j.keys().asSequence().map { j.optString(it) }.toList() }
        return (UEBLICHE_LAEDEN + alle(context).map { it.laden } + gelernt).filter { it.isNotBlank() }.distinct()
    }

    // ---------- aus Aufnahmen ----------

    /**
     * Einkäufe aus einer ausgewerteten Aufnahme. Jede Sache einer Aufnahme zählt nur einmal: Nach „Neu auswerten“ kommt
     * nur dazu, was vorher fehlte; Gelöschtes kommt nicht wieder (Frage 33). Rückgabe: was neu auf der Liste steht.
     */
    @Synchronized
    fun ausErgebnis(context: Context, aufnahme: String, sachen: List<JSONObject>): List<Einkauf> {
        if (sachen.isEmpty()) return emptyList()
        val gesehen = objektLesen(gesehenDatei(context))
        val bekannt = gesehen.optJSONArray(aufnahme)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toMutableSet()
        val liste = alle(context).toMutableList()
        val neu = mutableListOf<Einkauf>()
        for (s in sachen) {
            val sache = s.optString("sache").trim()
            val key = schluessel(sache)
            if (key.isEmpty() || key in bekannt) continue
            bekannt += key
            val menge = s.optString("menge").trim()
            val i = liste.indexOfFirst { schluessel(it.sache) == key }
            if (i >= 0) {
                val alt = liste[i]
                liste[i] = alt.copy(
                    menge = alt.menge.ifBlank { menge },
                    erledigt = null, // abgehakt, aber wieder gebraucht: wieder offen (Frage 23)
                    werHolt = alt.werHolt.ifBlank { s.optString("wer_holt").trim() },
                )
                if (alt.erledigt != null) neu += liste[i]
                continue
            }
            val e = Einkauf(
                UUID.randomUUID().toString().take(8), sache, s.optString("details").trim(), menge,
                gelernterLaden(context, sache) ?: s.optString("laden").trim().ifBlank { SUPERMARKT },
                s.optString("abteilung").takeIf { it in ABTEILUNGEN } ?: "sonstiges",
                s.optString("wer_holt").trim(), aufnahme, System.currentTimeMillis(),
            )
            liste += e
            neu += e
        }
        gesehen.put(aufnahme, JSONArray(bekannt.toList()))
        // nur die letzten 500 Aufnahmen merken, damit die Datei klein bleibt
        val ids = gesehen.keys().asSequence().toList().sorted()
        ids.dropLast(500).forEach { gesehen.remove(it) }
        schreiben(gesehenDatei(context), gesehen.toString())
        speichern(context, liste)
        return neu
    }

    /** Reine Einkaufs-Ansage („Einkauf: …“, Frage 13): leise Bestätigung, was auf der Liste steht. */
    fun ansageBestaetigen(context: Context, neu: List<Einkauf>) {
        if (neu.isEmpty()) return
        val text = neu.joinToString(", ") { it.sache }
        val oeffnen = PendingIntent.getActivity(
            context, ID_ANSAGE, Intent(context, MainActivity::class.java).putExtra(Tagesrhythmus.EXTRA_REITER, "einkauf")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Auf der Einkaufsliste")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_ANSAGE, n)
        } catch (_: SecurityException) {
        }
    }

    // ---------- oft gekauft ----------

    private fun merkenGekauft(context: Context, e: Einkauf) {
        val j = objektLesen(haeufigDatei(context))
        val key = schluessel(e.sache)
        val alt = j.optJSONObject(key)
        j.put(key, JSONObject().put("sache", e.sache).put("laden", e.laden).put("abteilung", e.abteilung)
            .put("anzahl", (alt?.optInt("anzahl") ?: 0) + 1).put("zuletzt", System.currentTimeMillis()))
        schreiben(haeufigDatei(context), j.toString(1))
    }

    /** Die am häufigsten gekauften Sachen, die gerade nicht offen auf der Liste stehen (Frage 14). */
    fun oftGekauft(context: Context, anzahl: Int = 10): List<String> {
        val j = objektLesen(haeufigDatei(context))
        val offen = offene(context).map { schluessel(it.sache) }.toSet()
        return j.keys().asSequence().mapNotNull { j.optJSONObject(it) }
            .filter { it.optInt("anzahl") >= 1 && schluessel(it.optString("sache")) !in offen }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("anzahl") }.thenByDescending { it.optLong("zuletzt") })
            .take(anzahl).map { it.optString("sache") }.toList()
    }

    /** Tipp auf „oft gekauft“: wie beim letzten Mal (Laden, Abteilung). Steht sie abgehakt da, wird sie wieder offen. */
    fun ausHaeufig(context: Context, sache: String) {
        val key = schluessel(sache)
        val h = objektLesen(haeufigDatei(context)).optJSONObject(key)
        aendern(context) { l ->
            val i = l.indexOfFirst { schluessel(it.sache) == key }
            if (i >= 0) {
                l[i] = l[i].copy(erledigt = null)
                return@aendern
            }
            l += Einkauf(
                UUID.randomUUID().toString().take(8), h?.optString("sache")?.ifBlank { null } ?: sache, "", "",
                gelernterLaden(context, sache) ?: h?.optString("laden")?.ifBlank { null } ?: SUPERMARKT,
                h?.optString("abteilung")?.takeIf { it in ABTEILUNGEN } ?: "sonstiges", "", "", System.currentTimeMillis(),
            )
        }
    }

    // ---------- Text für WhatsApp ----------

    /** Alles Offene nach Laden und Abteilung (Frage 20). */
    fun alsText(context: Context): String {
        val gruppen = nachLaden(offene(context))
        if (gruppen.isEmpty()) return "Die Einkaufsliste ist leer."
        return "Einkaufsliste\n" + gruppen.joinToString("") { (laden, l) ->
            "\n" + laden.uppercase(Locale.GERMAN) + "\n" + l.joinToString("\n") { e ->
                "• " + e.sache + (if (e.menge.isNotBlank()) " " + e.menge else "") +
                    (if (e.details.isNotBlank()) " (" + e.details + ")" else "") +
                    (if (e.werHolt.isNotBlank()) " – " + holtText(e.werHolt) else "")
            } + "\n"
        }.trimEnd()
    }

    fun holtText(werHolt: String) = if (werHolt.equals("jemand anderes", true)) "holt jemand anderes" else "$werHolt holt"

    // ---------- „Noch nötig?“ ----------

    /**
     * Was seit 2 Wochen offen ist, fragt Braininger per Benachrichtigung „Noch nötig?“ JA / WEG (Frage 31/32), gesammelt
     * höchstens einmal pro Woche und nur tagsüber. Läuft im 15-min-Abruf (ErgebnisAuftrag).
     */
    fun nochNoetigPruefen(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val stunde = Calendar.getInstance().apply { timeInMillis = jetzt }.get(Calendar.HOUR_OF_DAY)
        if (stunde !in 10..19) return
        val v = vorlieben(context)
        if (jetzt - v.getLong("noch_noetig_zuletzt", 0L) < NOCH_NOETIG_ABSTAND_MS) return
        val grenze = jetzt - NOCH_NOETIG_TAGE * TAG_MS
        val faellig = offene(context).filter { (it.gefragt ?: it.erstellt) < grenze }.sortedBy { it.gefragt ?: it.erstellt }
            .take(NOCH_NOETIG_MAX)
        if (faellig.isEmpty()) return
        v.edit().putLong("noch_noetig_zuletzt", jetzt).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        faellig.forEachIndexed { i, e ->
            fun aktion(was: String, code: Int) = PendingIntent.getBroadcast(
                context, 700 + i * 2 + code,
                Intent(context, EinkaufAktion::class.java).setAction(was).putExtra(EinkaufAktion.EXTRA_ID, e.id)
                    .putExtra(EinkaufAktion.EXTRA_MELDUNG, ID_NOCH_NOETIG + i),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val oeffnen = PendingIntent.getActivity(
                context, 710 + i, Intent(context, MainActivity::class.java).putExtra(Tagesrhythmus.EXTRA_REITER, "einkauf")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, "hinweise")
                .setSmallIcon(R.drawable.ic_benachrichtigung)
                .setContentTitle("Noch nötig?")
                .setContentText(e.sache + if (e.laden != SUPERMARKT) " (${e.laden})" else "")
                .setContentIntent(oeffnen)
                .setGroup("einkauf_noch_noetig")
                .setAutoCancel(true)
                .addAction(0, "JA", aktion(EinkaufAktion.JA, 0))
                .addAction(0, "WEG", aktion(EinkaufAktion.WEG, 1))
                .build()
            try {
                nm.notify(ID_NOCH_NOETIG + i, n)
            } catch (_: SecurityException) {
            }
        }
    }

    /** JA: bleibt, gefragt wird erst in 2 Wochen wieder. */
    fun nochNoetig(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(gefragt = System.currentTimeMillis()) else it }
    }
}

/** Knöpfe JA / WEG der Benachrichtigung „Noch nötig?“. */
class EinkaufAktion : BroadcastReceiver() {
    companion object {
        const val JA = "de.braininger.EINKAUF_JA"
        const val WEG = "de.braininger.EINKAUF_WEG"
        const val EXTRA_ID = "einkauf"
        const val EXTRA_MELDUNG = "meldung"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            JA -> EinkaufSpeicher.nochNoetig(context, id)
            WEG -> EinkaufSpeicher.loeschen(context, id)
        }
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_MELDUNG, 0))
    }
}
