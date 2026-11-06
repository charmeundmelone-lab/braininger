package de.braininger.lite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startseite für den E-Ink-Bildschirm (480 × 800): schwarz auf weiß, keine Animationen, kein Ticken.
 * Oben der große Knopf, darunter der Laptop-Stand, dann die Aufnahmen mit dem, was der Laptop zurückgeschickt hat.
 */
class LiteActivity : Activity() {

    private lateinit var inhalt: LinearLayout
    private var abgleichMeldung: String? = null
    private val offen = mutableSetOf<String>() // aufgeklappte Notizen
    private val neuZeichnen: () -> Unit = { runOnUiThread { zeichnen() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inhalt)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AbgleichJob.planen(this)
    }

    override fun onResume() {
        super.onResume()
        AufnahmeDienst.beobachten(neuZeichnen)
        zeichnen()
        abgleichen()
    }

    override fun onPause() {
        AufnahmeDienst.nichtMehr(neuZeichnen)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        zeichnen()
    }

    private fun abgleichen() {
        Thread {
            abgleichMeldung = Abgleich.laufen(this)
            runOnUiThread { zeichnen() }
        }.start()
    }

    // ---------- Bausteine ----------

    private fun text(t: CharSequence, groesse: Float = 15f, fett: Boolean = false, grau: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = groesse
            setTextColor(if (grau) Color.rgb(90, 90, 90) else Color.BLACK)
            if (fett) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.1f)
        }

    private fun abstand(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun linie() = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }

    private fun rahmen(gefuellt: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(19).toFloat()
        setStroke(dp(2), Color.BLACK)
        setColor(if (gefuellt) Color.BLACK else Color.WHITE)
    }

    private fun knopfKlein(t: String, aktion: () -> Unit) = text(t, 13f, fett = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rahmen(false)
        setOnClickListener { aktion() }
    }

    // ---------- Seite ----------

    private fun zeichnen() {
        inhalt.removeAllViews()
        val kopf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        kopf.addView(text("BRAININGER", 13f, fett = true).apply { letterSpacing = 0.2f })
        kopf.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        kopf.addView(text("lite", 13f, grau = true))
        inhalt.addView(kopf)
        inhalt.addView(abstand(14))

        // Großer Knopf
        val laeuft = AufnahmeDienst.laeuft
        val seit = if (laeuft) SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(AufnahmeDienst.seit)) else ""
        inhalt.addView(text(if (laeuft) "■  STOPPEN\nAufnahme seit $seit" else "●  AUFNEHMEN", 20f, fett = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setTextColor(if (laeuft) Color.WHITE else Color.BLACK)
            background = rahmen(laeuft)
            minHeight = dp(118)
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                } else if (AufnahmeDienst.laeuft) AufnahmeDienst.stoppen(this@LiteActivity)
                else AufnahmeDienst.starten(this@LiteActivity)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (laeuft && AufnahmeDienst.stumm) {
            inhalt.addView(abstand(10))
            inhalt.addView(text("Mikrofon ist stumm. Offline+-Schalter an der Seite ausschalten, dann neu aufnehmen.", 15f, fett = true))
        }
        if (!TastenDienst.verbunden) {
            inhalt.addView(abstand(10))
            inhalt.addView(text("Taste am Mikrofon einrichten  ›", 14f).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            inhalt.addView(text("Mikrofon ist nicht erlaubt. Knopf antippen, dann „Zulassen“.", 14f))
        }

        // Laptop
        inhalt.addView(linie())
        val zeile = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kontakt = Lager.letzterKontakt(this)
        val stand = if (kontakt == 0L) "Laptop: noch nicht erreicht"
                    else "Laptop: zuletzt " + SimpleDateFormat("EE d.M. HH:mm", Locale.GERMANY).format(Date(kontakt))
        zeile.addView(text(stand, 13f, grau = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        zeile.addView(knopfKlein("ABGLEICHEN") {
            abgleichMeldung = "…"
            zeichnen()
            abgleichen()
        })
        inhalt.addView(zeile)
        abgleichMeldung?.let { if (it.startsWith("Laptop nicht") || it.startsWith("Noch") || it.startsWith("Fehler")) inhalt.addView(text(it, 13f, grau = true)) }
        inhalt.addView(linie())

        // Aufnahmen
        val liste = Lager.aufnahmen(this).take(40)
        if (liste.isEmpty()) inhalt.addView(text("Noch keine Aufnahmen.", 14f, grau = true))
        for (o in liste) eintrag(o)
    }

    private fun eintrag(o: File) {
        val info = Lager.json(File(o, "info.json")) ?: return
        val erg = Lager.json(File(o, "ergebnis.json"))
        val start = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(info.optString("start"))
        } catch (_: Exception) { null }
        val wann = start?.let { SimpleDateFormat("EE d.M. · HH:mm", Locale.GERMANY).format(it) } ?: o.name
        val dauer = info.optLong("dauer_s", 0)
        val dauerText = if (dauer >= 60) " · ${dauer / 60} Min" else if (dauer > 0) " · $dauer s" else ""
        inhalt.addView(text(wann + dauerText, 13f, fett = true))

        val status = when {
            info.optString("status") == "laeuft" -> "nimmt auf"
            !Lager.gesendet(o) -> "wartet auf den Laptop"
            erg == null -> "beim Laptop, noch kein Ergebnis"
            erg.optString("status") == "fertig" -> null
            erg.optString("status") == "fehler" -> "Fehler: " + erg.optString("meldung")
            erg.optString("status") == "wartet" -> "wartet: " + erg.optString("meldung")
            else -> "Laptop arbeitet" + (erg.optString("schritt").takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" } ?: "")
        }
        if (status != null) inhalt.addView(text(status, 13f, grau = true))
        if (erg != null && erg.optString("status") == "fertig") ergebnis(o.name, erg)
        inhalt.addView(abstand(14))
    }

    private fun ergebnis(id: String, erg: JSONObject) {
        val kurz = erg.optString("kurz")
        if (kurz.isNotBlank() && kurz != "null") inhalt.addView(text(kurz, 15f))
        val notizen = erg.optJSONArray("notizen")
        if (notizen != null) for (i in 0 until notizen.length()) {
            val n = notizen.optJSONObject(i) ?: continue
            val schluessel = "$id/$i"
            val auf = schluessel in offen
            inhalt.addView(text((if (auf) "▾ " else "▸ ") + n.optString("titel"), 15f, fett = true).apply {
                setPadding(0, dp(6), 0, dp(2))
                setOnClickListener {
                    if (!offen.remove(schluessel)) offen += schluessel
                    zeichnen()
                }
            })
            if (auf) inhalt.addView(text(einfach(n.optString("inhalt")), 14f).apply { setPadding(dp(14), 0, 0, dp(4)) })
        }
        val aufgaben = erg.optJSONArray("aufgaben")
        if (aufgaben != null) for (i in 0 until aufgaben.length()) {
            val a = aufgaben.optJSONObject(i) ?: continue
            if (a.optString("art") == "erledigt") continue
            val frist = a.optString("frist").takeIf { it.length >= 10 }?.let {
                try {
                    " · " + SimpleDateFormat("EE d.M.", Locale.GERMANY).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it.substring(0, 10))!!)
                } catch (_: Exception) { "" }
            } ?: ""
            inhalt.addView(text("☐  " + a.optString("text") + frist, 14f).apply { setPadding(0, dp(4), 0, 0) })
        }
        val einkauf = erg.optJSONArray("einkauf")
        if (einkauf != null && einkauf.length() > 0) {
            val sachen = (0 until einkauf.length()).mapNotNull { einkauf.optJSONObject(it)?.optString("sache") }
            inhalt.addView(text("Einkauf: " + TextUtils.join(", ", sachen), 14f).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    /** Markdown grob in lesbaren Text: Überschriften-Rauten und Sternchen weg, Listen-Striche als Punkte. */
    private fun einfach(md: String): String = md.lines().joinToString("\n") { z ->
        z.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace(Regex("^\\s*[-*]\\s+"), "• ")
    }.trim()
}
