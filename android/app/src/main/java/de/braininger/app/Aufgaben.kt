package de.braininger.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.braininger.app.drive.AnmeldungNoetig
import de.braininger.app.drive.DriveApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Ein kleiner Schritt einer zerlegten Aufgabe („Aufgabe kleiner machen“). */
data class Schritt(val text: String, val erledigt: Boolean = false)

/**
 * Eine Aufgabe in Jans Liste. frist und erledigt: Millisekunden oder null.
 * Körbe statt Uhrzeiten (Grill 20.10.2026): korb = heute | woche | irgendwann, neue Aufgaben starten in „irgendwann“.
 * Eine Frist schiebt die Aufgabe von selbst nach vorn (siehe [korbJetzt]); zurueckAm = „morgen wieder“.
 */
data class Aufgabe(
    val id: String,
    val text: String,
    val frist: Long?,
    val erledigt: Long?,
    val quelle: String?,
    val erstellt: Long,
    val unklarWer: Boolean = false,
    val korb: String = Koerbe.IRGENDWANN,
    val heuteSeit: Long? = null,
    val zurueckAm: Long? = null,
    val bereich: String = "",
    val schritte: List<Schritt> = emptyList(),
) {
    /**
     * Korb, in dem die Aufgabe jetzt steht. Eine Frist zieht sie am Vortag nach „heute“, in der Woche davor nach „woche“.
     * Jeden Morgen frisch (22.10.2026, gegen den Stau in „heute“): Was schon vor heute in „heute“ stand und weder heute
     * dorthin geholt wurde noch heute oder morgen fällig ist, steht in [Koerbe.LIEGEN]. Gespeichert wird dabei nichts.
     */
    fun korbJetzt(jetzt: Long = System.currentTimeMillis()): String {
        val k = korbOhneTag(jetzt)
        return if (k == Koerbe.HEUTE && liegtSeitVortagen(jetzt)) Koerbe.LIEGEN else k
    }

    private fun korbOhneTag(jetzt: Long): String {
        zurueckAm?.let { if (it > jetzt) return Koerbe.WOCHE }
        frist?.let { f ->
            val tage = Koerbe.tageBis(f, jetzt)
            if (tage <= 1) return Koerbe.HEUTE
            if (tage <= 7 && korb == Koerbe.IRGENDWANN) return Koerbe.WOCHE
        }
        if (zurueckAm != null) return Koerbe.HEUTE // „morgen wieder“ ist erreicht
        return korb
    }

    private fun liegtSeitVortagen(jetzt: Long): Boolean {
        val heuteBeginn = Koerbe.tagBeginn(jetzt)
        if (heuteSeit != null && heuteSeit >= heuteBeginn) return false // heute bewusst geholt
        if (zurueckAm != null && zurueckAm >= heuteBeginn) return false // „morgen“ von gestern ist heute
        if (frist != null && Koerbe.tageBis(frist, jetzt) in 0..1) return false // heute oder morgen fällig
        return seitWann() < heuteBeginn
    }

    private fun seitWann(): Long = listOfNotNull(heuteSeit, zurueckAm, frist?.let { it - 86_400_000L }).minOrNull() ?: erstellt

    /** Seit wann die Aufgabe schon in „heute“ steht, wenn das vor heute war (für das ruhige „seit Montag“), sonst null. */
    fun heuteSeitFrueher(jetzt: Long = System.currentTimeMillis()): Long? {
        if (korbJetzt(jetzt) != Koerbe.HEUTE && korbJetzt(jetzt) != Koerbe.LIEGEN) return null
        return seitWann().takeIf { it < Koerbe.tagBeginn(jetzt) }
    }
}

object Koerbe {
    const val HEUTE = "heute"
    const val WOCHE = "woche"
    const val IRGENDWANN = "irgendwann"

    /** Nur zur Anzeige, nie gespeichert: aus „heute“ von den Vortagen liegen geblieben (siehe [Aufgabe.korbJetzt]). */
    const val LIEGEN = "liegen"

    fun name(korb: String) = when (korb) {
        HEUTE -> "Heute"
        LIEGEN -> "Von den letzten Tagen"
        WOCHE -> "Diese Woche"
        else -> "Irgendwann"
    }

    fun tagBeginn(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Ganze Tage von heute bis zum Tag der Frist (0 = heute, negativ = vorbei). */
    fun tageBis(frist: Long, jetzt: Long = System.currentTimeMillis()): Int =
        Math.round((tagBeginn(frist) - tagBeginn(jetzt)) / 86_400_000.0).toInt()

    fun morgenBeginn(jetzt: Long = System.currentTimeMillis()): Long = tagBeginn(jetzt) + 86_400_000L + 3_600_000L

    /** „seit Montag“ / „seit gestern“ – nur ein Hinweis, keine Warnfarbe. */
    fun seitText(ms: Long, jetzt: Long = System.currentTimeMillis()): String {
        val t = -tageBis(ms, jetzt)
        return when {
            t <= 0 -> "seit heute"
            t == 1 -> "seit gestern"
            t <= 6 -> "seit " + SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(ms))
            else -> "seit " + SimpleDateFormat("d. MMMM", Locale.GERMANY).format(Date(ms))
        }
    }

    /** Frist ohne Uhrzeit: „bis Fr“, „bis morgen“. Verpasste Fristen bleiben leer: „seit …“ reicht (Grill 20.10.2026). */
    fun fristText(ms: Long, jetzt: Long = System.currentTimeMillis()): String {
        val t = tageBis(ms, jetzt)
        return when {
            t < 0 -> ""
            t == 0 -> "bis heute"
            t == 1 -> "bis morgen"
            t <= 6 -> "bis " + SimpleDateFormat("EE", Locale.GERMANY).format(Date(ms))
            else -> "bis " + SimpleDateFormat("d.M.", Locale.GERMANY).format(Date(ms))
        }
    }
}

/** Vorschlag des Laptops aus einer Aufnahme. art: neu | ergaenzen | erledigt; bezug = Kennung einer Aufgabe. */
data class Vorschlag(
    val id: String,
    val aufnahme: String,
    val art: String,
    val text: String,
    val frist: Long?,
    val unklarWer: Boolean,
    val bezug: String?,
    val bereich: String = "",
)

/**
 * Jans Aufgabenliste. Führend ist das Handy (files/aufgaben/). Jede Änderung plant die Erinnerungen neu und sichert
 * die Liste in Braininger/Aufgaben/aufgaben.json (zum Wiederherstellen und für die Lese-Ansicht am Laptop).
 */
object AufgabenSpeicher {
    private val iso get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

    private val _stand = MutableStateFlow(0)
    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu liest. */
    val stand: StateFlow<Int> = _stand

    private fun ordner(context: Context) = File(context.filesDir, "aufgaben").apply { mkdirs() }
    fun listenDatei(context: Context) = File(ordner(context), "aufgaben.json")
    private fun vorschlagDatei(context: Context) = File(ordner(context), "vorschlaege.json")
    private fun gesehenDatei(context: Context) = File(ordner(context), "gesehen.json")

    fun zeitLesen(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { try { iso.parse(it)?.time } catch (_: Exception) { null } }
    private fun zeitText(ms: Long?): String = ms?.let { iso.format(Date(it)) } ?: ""

    private fun schreiben(datei: File, inhalt: String) {
        val tmp = File(datei.path + ".tmp")
        tmp.writeText(inhalt)
        tmp.renameTo(datei)
    }

    // ---------- Aufgaben ----------

    fun alle(context: Context): List<Aufgabe> {
        val j = try { JSONObject(listenDatei(context).readText()) } catch (_: Exception) { return emptyList() }
        val a = j.optJSONArray("aufgaben") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optJSONArray("schritte")
            Aufgabe(
                o.optString("id"), o.optString("text"), zeitLesen(o.optString("frist")), zeitLesen(o.optString("erledigt")),
                o.optString("quelle").ifBlank { null }, zeitLesen(o.optString("erstellt")) ?: 0L, o.optBoolean("unklar_wer"),
                korb = o.optString("korb").takeIf { it == Koerbe.HEUTE || it == Koerbe.WOCHE } ?: Koerbe.IRGENDWANN,
                heuteSeit = zeitLesen(o.optString("heute_seit")),
                zurueckAm = zeitLesen(o.optString("zurueck_am")),
                bereich = o.optString("bereich"),
                schritte = (0 until (s?.length() ?: 0)).mapNotNull { k ->
                    s!!.optJSONObject(k)?.let { Schritt(it.optString("text"), it.optBoolean("erledigt")) }
                }.filter { it.text.isNotBlank() },
            )
        }.filter { it.id.isNotBlank() && it.text.isNotBlank() }
    }

    /** Offene Aufgaben, die mit der nächsten Frist zuerst, Aufgaben ohne Frist am Ende (neueste zuerst). */
    fun offene(context: Context): List<Aufgabe> =
        alle(context).filter { it.erledigt == null }
            .sortedWith(compareBy<Aufgabe> { it.frist == null }.thenBy { it.frist ?: 0L }.thenByDescending { it.erstellt })

    fun alsJson(liste: List<Aufgabe>): String = JSONObject()
        .put("version", 1)
        .put("aktualisiert", zeitText(System.currentTimeMillis()))
        .put("aufgaben", JSONArray(liste.map {
            JSONObject().put("id", it.id).put("text", it.text).put("frist", zeitText(it.frist))
                .put("erledigt", zeitText(it.erledigt)).put("quelle", it.quelle ?: "")
                .put("erstellt", zeitText(it.erstellt)).put("unklar_wer", it.unklarWer)
                .put("korb", it.korb).put("heute_seit", zeitText(it.heuteSeit)).put("zurueck_am", zeitText(it.zurueckAm))
                .put("bereich", it.bereich)
                .put("schritte", JSONArray(it.schritte.map { s -> JSONObject().put("text", s.text).put("erledigt", s.erledigt) }))
        })).toString(1)

    @Synchronized
    private fun speichern(context: Context, liste: List<Aufgabe>) {
        // erledigte nur 60 Tage aufheben, damit die Datei klein bleibt
        val grenze = System.currentTimeMillis() - 60L * 24 * 3_600_000
        schreiben(listenDatei(context), alsJson(liste.filter { (it.erledigt ?: Long.MAX_VALUE) > grenze }))
        Erinnerungen.allePlanen(context)
        AufgabenSicherung.anstossen(context)
        _stand.value++
    }

    @Synchronized
    private fun aendern(context: Context, f: (MutableList<Aufgabe>) -> Unit) {
        val liste = alle(context).toMutableList()
        f(liste)
        speichern(context, liste)
    }

    fun neu(context: Context, text: String, frist: Long?, quelle: String? = null, unklarWer: Boolean = false,
            korb: String = Koerbe.IRGENDWANN) =
        aendern(context) {
            it += Aufgabe(UUID.randomUUID().toString().take(8), text.trim(), frist, null, quelle, System.currentTimeMillis(), unklarWer,
                korb = korb, heuteSeit = if (korb == Koerbe.HEUTE) System.currentTimeMillis() else null)
        }

    /** Tipp „heute / später“ (Grill 20.10.2026, Frage 14) und „holen“ aus dem Wochenblick. */
    fun korbSetzen(context: Context, id: String, korb: String) = aendern(context) { l ->
        val jetzt = System.currentTimeMillis()
        l.replaceAll {
            if (it.id != id) it
            else it.copy(korb = korb, heuteSeit = if (korb == Koerbe.HEUTE) jetzt else null, zurueckAm = null)
        }
    }

    /** „Morgen“: verschwindet bis morgen früh aus „heute“ und kommt dann wieder. */
    fun morgenWieder(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(korb = Koerbe.HEUTE, heuteSeit = null, zurueckAm = Koerbe.morgenBeginn()) else it }
    }

    fun schritteSetzen(context: Context, id: String, schritte: List<Schritt>) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(schritte = schritte) else it }
    }

    /** Schritt abhaken; sind alle Schritte erledigt, ist die Aufgabe erledigt (Frage 32). */
    fun schrittAbhaken(context: Context, id: String, index: Int) = aendern(context) { l ->
        l.replaceAll { a ->
            if (a.id != id || index !in a.schritte.indices) a
            else {
                val neu = a.schritte.mapIndexed { i, s -> if (i == index) s.copy(erledigt = !s.erledigt) else s }
                a.copy(schritte = neu, erledigt = if (neu.all { it.erledigt }) System.currentTimeMillis() else null)
            }
        }
    }

    /**
     * „Behalten“ im Wochenblick: die Aufgabe bleibt in „irgendwann“, zählt aber wieder als neu, damit sie nicht
     * gleich im nächsten Wochenblick steht.
     */
    fun behalten(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(erstellt = System.currentTimeMillis()) else it }
    }

    /** Abgehakt, aber doch nicht fertig: wieder offen (Tipp auf den gefüllten Kreis). */
    fun wiederOeffnen(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(erledigt = null) else it }
    }

    /**
     * Heute Abgehaktes. Es bleibt bis Mitternacht durchgestrichen sichtbar (zum Freuen und Rückgängigmachen),
     * zählt aber nirgends mehr mit.
     */
    fun erledigtHeute(context: Context): List<Aufgabe> {
        val beginn = Koerbe.tagBeginn(System.currentTimeMillis())
        return alle(context).filter { (it.erledigt ?: 0L) >= beginn }.sortedBy { it.erledigt }
    }

    /** Offene Aufgaben in einem Korb (nach [Aufgabe.korbJetzt]). */
    fun imKorb(context: Context, korb: String): List<Aufgabe> = offene(context).filter { it.korbJetzt() == korb }

    fun abhaken(context: Context, id: String) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(erledigt = System.currentTimeMillis()) else it }
    }

    fun loeschen(context: Context, id: String) = aendern(context) { l -> l.removeAll { it.id == id } }

    fun fristSetzen(context: Context, id: String, frist: Long?) = aendern(context) { l ->
        l.replaceAll { if (it.id == id) it.copy(frist = frist) else it }
    }

    /** „Morgen“ in einer Benachrichtigung. */
    fun aufMorgen(context: Context, id: String) = morgenWieder(context, id)

    fun morgenAcht(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Aus der Sicherung im Drive (nach Neuinstallation). Nur wenn es lokal noch keine Liste gibt. */
    fun wiederherstellen(context: Context, inhalt: String) {
        if (listenDatei(context).exists()) return
        try { JSONObject(inhalt) } catch (_: Exception) { return }
        schreiben(listenDatei(context), inhalt)
        Erinnerungen.allePlanen(context)
        _stand.value++
    }

    // ---------- Vorschläge ----------

    fun vorschlaege(context: Context): List<Vorschlag> {
        val a = try { JSONArray(vorschlagDatei(context).readText()) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Vorschlag(o.optString("id"), o.optString("aufnahme"), o.optString("art", "neu"), o.optString("text"),
                zeitLesen(o.optString("frist")), o.optBoolean("unklar_wer"), o.optString("bezug").ifBlank { null },
                o.optString("bereich"))
        }
    }

    private fun vorschlaegeSchreiben(context: Context, liste: List<Vorschlag>) {
        schreiben(vorschlagDatei(context), JSONArray(liste.map {
            JSONObject().put("id", it.id).put("aufnahme", it.aufnahme).put("art", it.art).put("text", it.text)
                .put("frist", zeitText(it.frist)).put("unklar_wer", it.unklarWer).put("bezug", it.bezug ?: "")
                .put("bereich", it.bereich)
        }).toString(1))
        _stand.value++
    }

    /** Neue Vorschläge aus einer fertigen Aufnahme übernehmen (jeder nur einmal, auch nach Neuauswertung). */
    @Synchronized
    fun ausErgebnis(context: Context, aufnahme: String, vorschlaege: List<Vorschlag>): List<Vorschlag> {
        val gesehen = try { JSONArray(gesehenDatei(context).readText()) } catch (_: Exception) { JSONArray() }
        val bekannt = (0 until gesehen.length()).map { gesehen.getString(it) }.toMutableSet()
        val offen = alle(context).filter { it.erledigt == null }.map { it.id }.toSet()
        val neu = vorschlaege.filter { it.id !in bekannt && (it.bezug == null || it.bezug in offen) }
        if (neu.isEmpty()) return emptyList()
        bekannt += neu.map { it.id }
        schreiben(gesehenDatei(context), JSONArray(bekannt.toList().takeLast(2000)).toString())
        vorschlaegeSchreiben(context, vorschlaege(context) + neu)
        return neu
    }

    @Synchronized
    fun uebernehmen(context: Context, ids: Set<String>) {
        val alleV = vorschlaege(context)
        val gewaehlt = alleV.filter { it.id in ids }
        if (gewaehlt.isEmpty()) return
        aendern(context) { l ->
            for (v in gewaehlt) when (v.art) {
                "erledigt" -> l.replaceAll { if (it.id == v.bezug) it.copy(erledigt = System.currentTimeMillis()) else it }
                "ergaenzen" -> if (l.any { it.id == v.bezug }) {
                    l.replaceAll { if (it.id == v.bezug) it.copy(text = v.text, frist = v.frist ?: it.frist) else it }
                } else {
                    l += Aufgabe(UUID.randomUUID().toString().take(8), v.text, v.frist, null, v.aufnahme, System.currentTimeMillis(),
                        v.unklarWer, bereich = v.bereich)
                }
                // neue Aufgaben starten in „irgendwann“ (Frage 58); eine Frist zieht sie von selbst nach vorn
                else -> l += Aufgabe(UUID.randomUUID().toString().take(8), v.text, v.frist, null, v.aufnahme, System.currentTimeMillis(),
                    v.unklarWer, bereich = v.bereich)
            }
        }
        vorschlaegeSchreiben(context, alleV.filter { it.id !in ids })
    }

    fun verwerfen(context: Context, id: String) = vorschlaegeSchreiben(context, vorschlaege(context).filter { it.id != id })

    fun fristText(ms: Long): String {
        val heute = Calendar.getInstance()
        val f = Calendar.getInstance().apply { timeInMillis = ms }
        val tage = Math.round((tagBeginn(f) - tagBeginn(heute)) / 86_400_000.0).toInt()
        val uhr = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ms))
        return when (tage) {
            0 -> "heute $uhr"
            1 -> "morgen $uhr"
            -1 -> "gestern $uhr"
            in 2..6 -> SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(ms)) + " $uhr"
            else -> SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))
        }
    }

    private fun tagBeginn(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Erinnerungen zur Frist, minutengenau (USE_EXACT_ALARM; sonst ungefähr). */
object Erinnerungen {
    const val KANAL = "erinnerungen"
    private const val AKTION = "de.braininger.ERINNERUNG"
    const val EXTRA_ID = "aufgabe"

    fun kanalAnlegen(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(KANAL, "Erinnerungen an Aufgaben", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun absicht(context: Context, id: String) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ErinnerungEmpfaenger::class.java).setAction(AKTION).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Minutengenaue Erinnerungen an Aufgaben gibt es nicht mehr (Grill 20.10.2026: keine Uhrzeiten, stattdessen
     * nachmittags ein sanfter Stups, siehe Tagesrhythmus). Früher geplante werden hier abgemeldet; dazu plant
     * die Funktion den Tagesrhythmus und die Termin-Erinnerungen neu (läuft nach Neustart, Update und Änderungen).
     */
    fun allePlanen(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val geplant = context.getSharedPreferences("erinnerungen", Context.MODE_PRIVATE)
        for (id in geplant.getStringSet("ids", emptySet()).orEmpty()) am.cancel(absicht(context, id))
        geplant.edit().putStringSet("ids", emptySet()).apply()
        Tagesrhythmus.planen(context)
    }

    fun zeigen(context: Context, a: Aufgabe) {
        kanalAnlegen(context)
        fun aktion(was: String, code: Int) = PendingIntent.getBroadcast(
            context, a.id.hashCode() * 3 + code,
            Intent(context, AufgabenAktion::class.java).setAction(was).putExtra(EXTRA_ID, a.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val oeffnen = PendingIntent.getActivity(
            context, 20, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(a.text)
            .setContentText("Fällig " + (a.frist?.let { AufgabenSpeicher.fristText(it) } ?: "jetzt"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .addAction(0, "ERLEDIGT", aktion(AufgabenAktion.ERLEDIGT, 1))
            .addAction(0, "MORGEN", aktion(AufgabenAktion.MORGEN, 2))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(a.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

class ErinnerungEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Erinnerungen.EXTRA_ID) ?: return
        val a = AufgabenSpeicher.alle(context).firstOrNull { it.id == id && it.erledigt == null } ?: return
        Erinnerungen.zeigen(context, a)
    }
}

/** Knöpfe in den Benachrichtigungen: Aufgabe erledigt, auf morgen, Vorschläge übernehmen. */
class AufgabenAktion : BroadcastReceiver() {
    companion object {
        const val ERLEDIGT = "de.braininger.AUFGABE_ERLEDIGT"
        const val MORGEN = "de.braininger.AUFGABE_MORGEN"
        const val UEBERNEHMEN = "de.braininger.VORSCHLAEGE_UEBERNEHMEN"
        const val EXTRA_VORSCHLAEGE = "vorschlaege"
        const val EXTRA_MELDUNG = "meldung"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            ERLEDIGT, MORGEN -> {
                val id = intent.getStringExtra(Erinnerungen.EXTRA_ID) ?: return
                if (intent.action == ERLEDIGT) AufgabenSpeicher.abhaken(context, id) else AufgabenSpeicher.aufMorgen(context, id)
                nm.cancel(id.hashCode())
            }
            UEBERNEHMEN -> {
                val ids = intent.getStringArrayExtra(EXTRA_VORSCHLAEGE)?.toSet() ?: return
                AufgabenSpeicher.uebernehmen(context, ids)
                nm.cancel(intent.getIntExtra(EXTRA_MELDUNG, 4))
            }
        }
    }
}

/** Nach Neustart oder App-Update sind geplante Erinnerungen weg: neu planen. */
class NeustartEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Erinnerungen.allePlanen(context)
        TastenWache.pruefen(context) // nach einem App-Update schaltet Android die Bedienungshilfe manchmal ab
    }
}

/** Sichert die Liste in Braininger/Aufgaben/aufgaben.json (die App legt die Datei an, der Laptop liest sie). */
class AufgabenSicherung(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val NAME = "braininger-aufgaben-sichern"
        private const val VORLIEBEN = "aufgaben_sicherung"

        fun anstossen(context: Context) {
            val auftrag = OneTimeWorkRequestBuilder<AufgabenSicherung>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, auftrag)
        }

        private suspend fun dateiId(context: Context, api: DriveApi): Pair<String?, String> {
            val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
            val ordner = v.getString("ordner", null)
                ?: api.ordnerSichern("Aufgaben", api.ordnerSichern("Braininger", null)).also { v.edit().putString("ordner", it).apply() }
            val id = v.getString("datei", null) ?: api.dateiFinden("aufgaben.json", ordner)?.id
            id?.let { v.edit().putString("datei", it).apply() }
            return id to ordner
        }

        /** Beim Start: gibt es lokal keine Liste, aber eine Sicherung im Drive, wird sie zurückgeholt. */
        suspend fun wiederherstellen(context: Context) {
            if (AufgabenSpeicher.listenDatei(context).exists()) return
            val api = DriveApi(context)
            val (id, _) = dateiId(context, api)
            if (id != null) AufgabenSpeicher.wiederherstellen(context, api.herunterladen(id).decodeToString())
        }
    }

    override suspend fun doWork(): Result = try {
        val datei = AufgabenSpeicher.listenDatei(applicationContext)
        if (datei.exists()) {
            val api = DriveApi(applicationContext)
            val inhalt = datei.readBytes()
            val (id, ordner) = dateiId(applicationContext, api)
            val ersetzt = id != null && try {
                api.inhaltErsetzen(id, "application/json", inhalt); true
            } catch (e: de.braininger.app.drive.DriveFehler) {
                if (e.code != 404) throw e
                false // im Drive gelöscht: neu anlegen
            }
            if (!ersetzt) {
                val neu = api.hochladen("aufgaben.json", ordner, "application/json", inhalt)
                applicationContext.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString("datei", neu).apply()
            }
        }
        Result.success()
    } catch (_: AnmeldungNoetig) {
        Result.success()
    } catch (_: IOException) {
        Result.retry()
    }
}
