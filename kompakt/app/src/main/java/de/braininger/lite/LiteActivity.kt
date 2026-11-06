package de.braininger.lite

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startseite im Stil von Muditas eigenen Apps (MMD, wie Books und CalmCast): Lato, nur reines Schwarz auf Weiß
 * (Grau wird auf E-Ink blass), fette Titel, dicke Linie unter Kopf und Abschnitt, gepunktete Trennlinien,
 * keine Animationen, kein sekündliches Neuzeichnen.
 */
class LiteActivity : Activity() {

    private lateinit var inhalt: LinearLayout
    private var abgleichMeldung: String? = null
    private val offen = mutableSetOf<String>()
    private val neuZeichnen: () -> Unit = { runOnUiThread { zeichnen() } }

    private val normal: Typeface by lazy { schrift("fonts/Lato-Regular.ttf", Typeface.NORMAL) }
    private val fett: Typeface by lazy { schrift("fonts/Lato-Bold.ttf", Typeface.BOLD) }

    /** Lato liegt in der App (SIL Open Font License). Fehlt sie, Muditas Systemschrift (auch Lato). */
    private fun schrift(pfad: String, stil: Int): Typeface = try {
        Typeface.createFromAsset(assets, pfad)
    } catch (_: Exception) {
        Typeface.create(Typeface.DEFAULT, stil)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
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

    private fun text(t: CharSequence, groesse: Float, dick: Boolean = false, farbe: Int = Color.BLACK) =
        TextView(this).apply {
            text = t
            textSize = groesse
            typeface = if (dick) fett else normal
            setTextColor(farbe)
            includeFontPadding = false
            setLineSpacing(dpf(3f), 1f)
        }

    private fun rand(v: View, links: Int = 16, oben: Int = 0, rechts: Int = 16, unten: Int = 0): View {
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(links), dp(oben), dp(rechts), dp(unten)) }
        return v
    }

    /** Dicke Linie wie unter „Books“ oder „Episodes“ in Muditas Apps. */
    private fun dickeLinie(oben: Int = 0) = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(oben) }
    }

    /** Gepunktete Trennlinie zwischen zwei Aufnahmen (wie in CalmCast). */
    private inner class Punktlinie(c: Context) : View(c) {
        private val stift = Paint().apply {
            color = Color.BLACK
            strokeWidth = dpf(1.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpf(2f), dpf(3f)), 0f)
        }
        override fun onDraw(c: Canvas) {
            val y = height / 2f
            c.drawLine(0f, y, width.toFloat(), y, stift)
        }
    }

    private fun punktlinie() = Punktlinie(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
            .apply { setMargins(dp(16), dp(14), dp(16), dp(14)) }
    }

    private fun mitSymbol(t: CharSequence, symbol: Int, groesse: Float, dick: Boolean = false, farbe: Int = Color.BLACK) =
        text(t, groesse, dick, farbe).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(symbol, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            gravity = Gravity.CENTER_VERTICAL
        }

    // ---------- Seite ----------

    private fun zeichnen() {
        inhalt.removeAllViews()

        // Kopf: Titel links, Abgleichen rechts, dicke Linie darunter
        val kopf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        kopf.addView(text("Braininger", 30f, dick = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        kopf.addView(ImageView(this).apply {
            setImageResource(R.drawable.aktualisieren)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Abgleichen"
            setOnClickListener {
                abgleichMeldung = "Gleiche ab …"
                zeichnen()
                abgleichen()
            }
        })
        inhalt.addView(kopf)
        inhalt.addView(dickeLinie())

        // Großer Knopf
        val laeuft = AufnahmeDienst.laeuft
        val knopf = if (laeuft) {
            val seit = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(AufnahmeDienst.seit))
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dpf(14f); setColor(Color.WHITE); setStroke(dp(3), Color.BLACK)
                }
                addView(mitSymbol("Stoppen", R.drawable.quadrat, 22f, dick = true))
                addView(text("läuft seit $seit", 15f).apply { setPadding(0, dp(6), 0, 0) })
            }
        } else {
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(Color.BLACK) }
                addView(mitSymbol("Aufnehmen", R.drawable.punkt, 22f, dick = true, farbe = Color.WHITE))
            }
        }
        knopf.minimumHeight = dp(92)
        knopf.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else if (AufnahmeDienst.laeuft) AufnahmeDienst.stoppen(this)
            else AufnahmeDienst.starten(this)
        }
        inhalt.addView(rand(knopf, oben = 16))

        // Warnungen: schwarz hinterlegt, damit man sie nicht übersieht
        val warnung = when {
            laeuft && AufnahmeDienst.stumm -> "Mikrofon ist stumm. Offline+ an der Seite ausschalten."
            !laeuft && Offline.an(this) == true -> "Offline+ ist an: Das Mikrofon ist getrennt."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Mikrofon nicht erlaubt. Knopf antippen, dann „Zulassen“."
            else -> null
        }
        if (warnung != null) {
            inhalt.addView(rand(text(warnung, 16f, dick = true, farbe = Color.WHITE).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { cornerRadius = dpf(8f); setColor(Color.BLACK) }
            }, oben = 10))
        }

        // Laptop-Stand in einer ruhigen Zeile
        val kontakt = Lager.letzterKontakt(this)
        val stand = when {
            abgleichMeldung == "Gleiche ab …" -> "Gleiche mit dem Laptop ab …"
            kontakt == 0L -> "Zum Laptop: Kabel anstecken."
            else -> "Laptop zuletzt " + SimpleDateFormat("EE d.M., HH:mm", Locale.GERMANY).format(Date(kontakt))
        }
        inhalt.addView(rand(text(stand, 15f), oben = 14))

        // Abschnitt Aufnahmen
        inhalt.addView(rand(text("Aufnahmen", 20f, dick = true), oben = 22))
        inhalt.addView(dickeLinie(oben = 8))

        val liste = Lager.aufnahmen(this).take(40)
        if (liste.isEmpty()) inhalt.addView(rand(text("Noch keine Aufnahmen.", 16f), oben = 14))
        liste.forEachIndexed { i, o ->
            if (i > 0) inhalt.addView(punktlinie()) else inhalt.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
            eintrag(o)
        }
    }

    private fun eintrag(o: File) {
        val info = Lager.json(File(o, "info.json")) ?: return
        val erg = Lager.json(File(o, "ergebnis.json"))?.takeIf { it.optString("status") == "fertig" }
        val roh = Lager.json(File(o, "ergebnis.json"))
        val start = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(info.optString("start"))
        } catch (_: Exception) { null }
        val wann = start?.let { SimpleDateFormat("EE d.M. · HH:mm", Locale.GERMANY).format(it) } ?: o.name
        val dauer = info.optLong("dauer_s", 0)
        val dauerText = when {
            dauer >= 60 -> " · ${dauer / 60} Min"
            dauer > 0 -> " · $dauer s"
            else -> ""
        }
        val notizen = erg?.optJSONArray("notizen")
        val ersteNotiz = notizen?.optJSONObject(0)?.optString("titel")?.takeIf { it.isNotBlank() }
        val kurz = erg?.optString("kurz")?.takeIf { it.isNotBlank() && it != "null" }
        val titel = when {
            info.optString("status") == "laeuft" -> "Aufnahme läuft"
            erg == null -> "Gedanke"
            ersteNotiz != null && !ersteNotiz.startsWith("Kein verwertbarer") -> ersteNotiz
            kurz != null && (erg.optJSONArray("aufgaben")?.length() ?: 0) + (erg.optJSONArray("einkauf")?.length() ?: 0) > 0 -> "Merker"
            else -> "Ohne verwertbaren Inhalt"
        }
        inhalt.addView(rand(text(titel, 19f, dick = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }))

        val status = when {
            info.optString("status") == "laeuft" -> null
            !Lager.gesendet(o) -> "wartet auf den Laptop"
            roh == null -> "beim Laptop"
            roh.optString("status") == "fertig" -> null
            roh.optString("status") == "fehler" -> "Fehler beim Laptop"
            roh.optString("status") == "wartet" -> "Laptop wartet"
            else -> "Laptop arbeitet"
        }
        inhalt.addView(rand(text(wann + dauerText + (status?.let { " · $it" } ?: ""), 15f), oben = 4))

        if (erg == null || titel == "Ohne verwertbaren Inhalt") return

        if (kurz != null) inhalt.addView(rand(text(kurz, 16f), oben = 8))

        val aufgaben = erg.optJSONArray("aufgaben")
        if (aufgaben != null) for (i in 0 until aufgaben.length()) {
            val a = aufgaben.optJSONObject(i) ?: continue
            if (a.optString("art") == "erledigt") continue
            val frist = a.optString("frist").takeIf { it.length >= 10 }?.let {
                try {
                    " · " + SimpleDateFormat("EE d.M.", Locale.GERMANY)
                        .format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it.substring(0, 10))!!)
                } catch (_: Exception) { "" }
            } ?: ""
            inhalt.addView(rand(mitSymbol(a.optString("text") + frist, R.drawable.kasten, 16f), oben = 8))
        }

        val einkauf = erg.optJSONArray("einkauf")
        if (einkauf != null && einkauf.length() > 0) {
            val sachen = (0 until einkauf.length()).mapNotNull { einkauf.optJSONObject(it)?.optString("sache") }
            inhalt.addView(rand(text("Einkauf: " + TextUtils.join(", ", sachen), 16f), oben = 8))
        }

        // Notizen: zugeklappt, Tippen zeigt den Text
        if (notizen != null && notizen.length() > 0 && ersteNotiz != null) {
            val auf = o.name in offen
            inhalt.addView(rand(text(if (auf) "Notiz zuklappen" else "Notiz lesen  ›", 16f, dick = true).apply {
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (!offen.remove(o.name)) offen += o.name
                    zeichnen()
                }
            }, oben = 4))
            if (auf) for (i in 0 until notizen.length()) {
                val n = notizen.optJSONObject(i) ?: continue
                if (notizen.length() > 1) inhalt.addView(rand(text(n.optString("titel"), 17f, dick = true), oben = 6))
                inhalt.addView(rand(text(einfach(n.optString("inhalt")), 16f), oben = 4))
            }
        }
    }

    /** Markdown grob in lesbaren Text: Rauten und Sternchen weg, Listen-Striche als Punkte. */
    private fun einfach(md: String): String = md.lines().joinToString("\n") { z ->
        z.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace(Regex("^\\s*[-*]\\s+"), "• ")
    }.trim()
}
