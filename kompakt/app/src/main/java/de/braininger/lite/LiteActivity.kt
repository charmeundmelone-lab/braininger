package de.braininger.lite

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ImageSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Braininger Lite auf dem E-Ink-Bildschirm des Kompakt, im Stil von Muditas eigenen Apps (Books, CalmCast):
 * Lato, nur reines Schwarz auf Weiß, fette Titel, dicke Linien, gepunktete Trenner.
 *
 * Kein Scrollen: Alles ist in Seiten aufgeteilt wie in einem Buch. Blättern mit den Pfeilen unten oder mit den
 * Lautstärketasten (wie in Books). Die Startseite passt immer ganz auf den Bildschirm.
 */
class LiteActivity : Activity() {

    private companion object {
        const val UEBERSCHRIFT = "ueberschrift"
        const val TAGESKOPF = "tageskopf" // beginnt immer eine neue Seite
    }

    private enum class Ansicht { START, AUFGABEN, EINKAUF, LADEN, AUFNAHMEN, AUFNAHME, UEBERSICHT }

    private var ansicht = Ansicht.START
    private var seite = 0
    private var seitenZahl = 1
    // damit „zurück“ aus einer Aufnahme dort landet, wo man herkam (Liste oder Aufgaben, gleiche Seite)
    private var zurueckZu = Ansicht.AUFNAHMEN
    private var zurueckSeite = 0
    private var gewaehlt: String? = null
    private var offeneAufgabe: String? = null // aufgeklappte Aufgabe
    private val leereOffen = mutableSetOf<String>() // Tage, deren leere Aufnahmen aufgeklappt sind
    private var ladenLetzte: Sache? = null // im Laden zuletzt in den Korb gelegt (für „Rückgängig“)
    private var abgleichLaeuft = false
    private var lauterGehalten = false
    private val lauterHalten = Runnable {
        lauterGehalten = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AufnahmeDienst.umschalten(this)
        } else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private lateinit var wurzel: FrameLayout
    private val neuZeichnen: () -> Unit = {
        runOnUiThread { if (ansicht == Ansicht.START || ansicht == Ansicht.AUFNAHME) zeichnen() } // Knopf und Nachtrag aktuell halten
    }

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
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        wurzel = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(wurzel)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AbgleichJob.planen(this)
    }

    override fun onResume() {
        super.onResume()
        AufnahmeDienst.beobachten(neuZeichnen)
        // Aus der sichtbaren App gestartet bekommt der Dienst sicher das Mikrofon, auch später bei dunklem Bildschirm.
        AufnahmeDienst.bereitHalten(this)
        wurzel.post { zeichnen() }
        abgleichen()
    }

    override fun onPause() {
        AufnahmeDienst.nichtMehr(neuZeichnen)
        wurzel.removeCallbacks(lauterHalten)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        AufnahmeDienst.bereitHalten(this)
        zeichnen()
    }

    @Deprecated("Activity-Zurück reicht für Android 12")
    override fun onBackPressed() {
        if (ansicht == Ansicht.START) super.onBackPressed() else zurueck()
    }

    private fun blaetterbar() = ansicht != Ansicht.START && seitenZahl > 1

    /**
     * Lauter-Taste 1 Sekunde halten = Aufnahme an/aus (wie überall auf dem Kompakt).
     * Kurz drücken: blättern wie in Books (Leiser = weiter, Lauter = zurück), auf der Startseite normale Lautstärke.
     * Läuft eine Aufnahme, setzt kurz Lauter eine Markierung („das war wichtig“).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) {
                    lauterGehalten = false
                    wurzel.postDelayed(lauterHalten, AufnahmeDienst.HALTEN_MS)
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    if (blaetterbar()) blaettern(+1) else lautstaerke(AudioManager.ADJUST_LOWER)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                wurzel.removeCallbacks(lauterHalten)
                if (!lauterGehalten) {
                    when {
                        AufnahmeDienst.laeuft -> AufnahmeDienst.markieren(this) // wie überall: kurz = markieren
                        blaetterbar() -> blaettern(-1)
                        else -> lautstaerke(AudioManager.ADJUST_RAISE)
                    }
                }
                lauterGehalten = false
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun lautstaerke(richtung: Int) {
        val audio = getSystemService(AudioManager::class.java)
        val strom = if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING
        try { audio.adjustStreamVolume(strom, richtung, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
    }

    private fun abgleichen() {
        if (abgleichLaeuft) return
        abgleichLaeuft = true
        Thread {
            Abgleich.laufen(this)
            runOnUiThread {
                abgleichLaeuft = false
                if (ansicht == Ansicht.START || ansicht == Ansicht.AUFNAHMEN) zeichnen()
            }
        }.start()
    }

    private fun oeffnen(a: Ansicht, id: String? = null) {
        if (a == Ansicht.AUFNAHME || a == Ansicht.UEBERSICHT) {
            zurueckZu = ansicht
            zurueckSeite = seite
        }
        ansicht = a
        gewaehlt = id
        seite = 0
        zeichnen()
    }

    private fun zurueck() {
        if (ansicht == Ansicht.AUFNAHME || ansicht == Ansicht.UEBERSICHT) {
            ansicht = zurueckZu
            seite = zurueckSeite
        } else if (ansicht == Ansicht.LADEN) {
            ansicht = Ansicht.EINKAUF
            seite = 0
        } else {
            ansicht = Ansicht.START
            seite = 0
            offeneAufgabe = null
        }
        zeichnen()
    }

    private fun blaettern(richtung: Int) {
        val neu = (seite + richtung).coerceIn(0, seitenZahl - 1)
        if (neu != seite) {
            seite = neu
            zeichnen()
        }
    }

    // ---------------------------------------------------------------- Bausteine

    private fun text(t: CharSequence, groesse: Float, dick: Boolean = false, farbe: Int = Color.BLACK) =
        TextView(this).apply {
            text = t
            textSize = groesse
            typeface = if (dick) fett else normal
            setTextColor(farbe)
            includeFontPadding = false
            setLineSpacing(dpf(3f), 1f)
        }

    private fun breit(h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    private fun dickeLinie() = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = breit(dp(3))
    }

    private inner class Punktlinie(c: Context) : View(c) {
        private val stift = Paint().apply {
            color = Color.BLACK
            strokeWidth = dpf(1.5f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(dpf(2f), dpf(3f)), 0f)
        }
        override fun onDraw(c: Canvas) = c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, stift)
    }

    private fun punktlinie() = Punktlinie(this).apply { layoutParams = breit(dp(4)) }

    private fun symbol(res: Int, groesse: Int = 28) = ImageView(this).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(groesse), dp(groesse))
    }

    /** Kopfzeile: links Zurück-Pfeil (außer Start), Titel, rechts ein kleiner Zusatz. Darunter die dicke Linie. */
    private fun kopf(titel: String, rechts: String? = null, zurueckPfeil: Boolean = false): View {
        val zeile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (zurueckPfeil) dp(4) else dp(16), 0, dp(16), 0)
        }
        if (zurueckPfeil) {
            zeile.addView(symbol(R.drawable.pfeil_links, 30).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(60))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Zurück"
            })
            zeile.setOnClickListener { zurueck() }
        }
        zeile.addView(text(titel, if (zurueckPfeil) 25f else 29f, dick = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (rechts != null) zeile.addView(text(rechts, 16f))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(zeile, breit(dp(60)))
            addView(dickeLinie())
        }
    }

    /** Fußzeile zum Blättern: große Pfeilflächen links und rechts, in der Mitte „Seite 1 von 3“. */
    private fun fuss(): View {
        val zeile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun pfeil(res: Int, sichtbar: Boolean, richtung: Int) = FrameLayout(this).apply {
            if (sichtbar) {
                addView(symbol(res, 30), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                setOnClickListener { blaettern(richtung) }
            }
        }
        zeile.addView(pfeil(R.drawable.pfeil_links, seite > 0, -1), LinearLayout.LayoutParams(dp(96), dp(58)))
        // bis 7 Seiten ruhige Punkte (aktuelle Seite gefüllt), darüber „3 / 12“
        val mitte: View = if (seitenZahl <= 7) object : View(this) {
            private val voll = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dpf(1.6f)
            }
            override fun onDraw(c: Canvas) {
                val abstand = dpf(18f)
                val x0 = width / 2f - abstand * (seitenZahl - 1) / 2f
                for (i in 0 until seitenZahl) {
                    if (i == seite) c.drawCircle(x0 + i * abstand, height / 2f, dpf(4.5f), voll)
                    else c.drawCircle(x0 + i * abstand, height / 2f, dpf(3.5f), ring)
                }
            }
        } else text("${seite + 1} / $seitenZahl", 15f).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            letterSpacing = 0.06f
        }
        zeile.addView(mitte, LinearLayout.LayoutParams(0, dp(58), 1f).also { if (mitte is TextView) it.height = ViewGroup.LayoutParams.WRAP_CONTENT })
        zeile.addView(pfeil(R.drawable.pfeil_rechts, seite < seitenZahl - 1, +1), LinearLayout.LayoutParams(dp(96), dp(58)))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (seitenZahl > 1) addView(View(this@LiteActivity).apply { setBackgroundColor(Color.BLACK) }, breit(dp(2))) else addView(View(this@LiteActivity), breit(dp(2)))
            addView(zeile)
        }
    }

    /**
     * Seite mit Kopf, Inhalt (genau eine Seite) und Blätter-Fuß. Kein ScrollView.
     * Passt alles auf eine Seite, gibt es keinen Fuß: dann gehört der Platz dem Inhalt.
     */
    private fun rahmen(kopfView: View, unten: View? = null, inhalt: (Int, Int) -> View) {
        val spalte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        spalte.addView(kopfView)
        val koerper = FrameLayout(this)
        spalte.addView(koerper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (unten != null) spalte.addView(unten, breit()) // fest unten, auf jeder Seite gleich (z. B. „Weitersprechen“)
        val fussPlatz = FrameLayout(this)
        spalte.addView(fussPlatz, breit())
        wurzel.addView(spalte)
        // Höhe des Inhalts: alles minus Kopf (63 dp), Abstand oben (12 dp) und unten (8 dp), mit Fuß noch 60 dp weniger
        val breite = wurzel.width - dp(32)
        val untenHoehe = unten?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(wurzel.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.measuredHeight
        } ?: 0
        val ganz = wurzel.height - dp(63) - dp(12) - dp(8) - untenHoehe
        val gewuenscht = seite
        var v = inhalt(breite, ganz)
        val mitFuss = seitenZahl > 1
        if (mitFuss) {
            (v as? ViewGroup)?.removeAllViews() // die Zeilen werden gleich neu verteilt
            seite = gewuenscht
            v = inhalt(breite, ganz - dp(52))
        }
        koerper.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(16), dp(12), dp(16), 0) })
        if (mitFuss) fussPlatz.addView(fuss())
    }

    /**
     * Teilt fertige Zeilen-Views auf Seiten auf (gemessen, nicht geschätzt) und zeigt die aktuelle.
     * Eine Überschrift bleibt nie allein unten auf einer Seite. Mit [fokus] springt die Seite dorthin, wo diese Zeile
     * steht (z. B. eine gerade aufgeklappte Aufgabe, die nicht mehr auf die alte Seite passt).
     */
    private fun liste(zeilen: List<View>, leer: String, fokus: View? = null): (Int, Int) -> View = { breite, hoehe ->
        val seiten = mutableListOf<MutableList<View>>(mutableListOf())
        var belegt = 0
        for (z in zeilen) {
            z.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = z.measuredHeight
            val neueSeite = z.tag == TAGESKOPF && seiten.last().isNotEmpty()
            if (neueSeite || (belegt + h > hoehe && seiten.last().isNotEmpty())) {
                val alt = seiten.last()
                val neu = mutableListOf<View>()
                // Überschriften (auch Laden + Gang zusammen) nie allein unten stehen lassen
                while (!neueSeite && alt.size > 1 && alt.last().tag == UEBERSCHRIFT) neu.add(0, alt.removeAt(alt.size - 1))
                // läuft eine Gruppe auf der neuen Seite weiter, steht oben klein, wo man ist („REWE · KÜHLREGAL“)
                if (neu.isEmpty() && z.tag != UEBERSCHRIFT && z.tag != TAGESKOPF) zeilenKontext[z]?.let { k ->
                    neu += ueberschrift(k).apply {
                        setPadding(0, dp(2), 0, dp(1))
                        measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    }
                }
                seiten += neu
                belegt = neu.sumOf { it.measuredHeight }
            }
            seiten.last() += z
            belegt += h
        }
        seitenZahl = seiten.size
        if (fokus != null) seiten.indexOfFirst { fokus in it }.takeIf { it >= 0 }?.let { seite = it }
        seite = seite.coerceIn(0, seitenZahl - 1)
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (zeilen.isEmpty()) {
                gravity = Gravity.CENTER // leere Seite: ein ruhiger Satz in der Mitte
                addView(text(leer, 19f).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(40)) })
            }
            seiten[seite].forEach { addView(it, breit()) }
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    private fun zeichnen() {
        if (wurzel.height == 0) { wurzel.post { zeichnen() }; return }
        wurzel.removeAllViews()
        // im Laden bleibt der Bildschirm an (E-Ink braucht dafür kaum Strom), sonst wie immer
        if (ansicht == Ansicht.LADEN) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val alle = Daten.eintraege(this)
        when (ansicht) {
            Ansicht.START -> start(alle)
            Ansicht.AUFGABEN -> aufgabenSeite(alle)
            Ansicht.EINKAUF -> einkaufSeite(alle)
            Ansicht.LADEN -> ladenSeite(alle)
            Ansicht.AUFNAHMEN -> tagebuch(alle)
            Ansicht.UEBERSICHT -> uebersichtSeite(gewaehlt)
            Ansicht.AUFNAHME -> {
                // ein Nachtrag (z. B. über „Aus der Aufnahme“ bei einer Aufgabe) öffnet seinen Gedanken
                val ziel = alle.firstOrNull { it.id == gewaehlt }?.let { Daten.nachtragsZiel(this, it, alle) }
                if (ziel != null) gewaehlt = ziel
                aufnahmeSeite(alle.firstOrNull { it.id == gewaehlt }, alle)
            }
        }
    }

    /** Kalendertage von heute bis d (0 = heute, −1 = gestern). */
    private fun tageBis(d: Date): Int {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.now(zone), d.toInstant().atZone(zone).toLocalDate()
        ).toInt()
    }

    /** „heute 10:00“, „morgen“, „Sa 8.11.“ – Uhrzeit nur, wenn eine genannt ist. */
    private fun tag(d: Date, mitZeit: Boolean): String {
        val wort = when (tageBis(d)) {
            0 -> "heute"
            1 -> "morgen"
            -1 -> "gestern"
            else -> SimpleDateFormat("EE d.M.", Locale.GERMANY).format(d)
        }
        return if (mitZeit) wort + ", " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(d) else wort
    }

    // ----- Startseite: passt immer ganz auf den Bildschirm

    private fun start(alle: List<Eintrag>) {
        val spalte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Umschlag: Wochentag klein in Versalien, Datum groß, darunter die dicke Linie wie auf allen Seiten
        spalte.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(text(SimpleDateFormat("EEEE", Locale.GERMANY).format(Date()).uppercase(Locale.GERMANY), 13f, dick = true).apply {
                letterSpacing = 0.14f
            })
            addView(text(SimpleDateFormat("d. MMMM", Locale.GERMANY).format(Date()), 30f, dick = true).apply {
                setPadding(0, dp(2), 0, 0)
            })
        })
        spalte.addView(dickeLinie())

        // Großer Knopf
        val laeuft = AufnahmeDienst.laeuft
        val knopf = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (laeuft) {
                background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(Color.WHITE); setStroke(dp(3), Color.BLACK) }
                addView(LinearLayout(this@LiteActivity).apply {
                    gravity = Gravity.CENTER
                    addView(symbol(R.drawable.quadrat, 18))
                    addView(text("Stoppen", 26f, dick = true).apply { setPadding(dp(12), 0, 0, 0) })
                })
                val seit = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(AufnahmeDienst.seit))
                val m = AufnahmeDienst.markiert
                val zusatz = when (m) { 0 -> ""; 1 -> " · 1 markiert"; else -> " · $m markiert" }
                addView(text("Aufnahme läuft seit $seit$zusatz", 16f).apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER })
            } else {
                background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(Color.BLACK) }
                addView(LinearLayout(this@LiteActivity).apply {
                    gravity = Gravity.CENTER
                    addView(symbol(R.drawable.punkt, 18))
                    addView(text("Aufnehmen", 26f, dick = true, farbe = Color.WHITE).apply { setPadding(dp(12), 0, 0, 0) })
                })
            }
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                } else if (AufnahmeDienst.laeuft) AufnahmeDienst.stoppen(this@LiteActivity)
                else AufnahmeDienst.starten(this@LiteActivity)
            }
        }
        spalte.addView(knopf, breit(dp(108)).apply { setMargins(dp(16), dp(16), dp(16), 0) })

        // Warnung (schwarz hinterlegt) oder, wenn alles gut ist, der Hinweis auf die Taste
        val eingeschraenkt = getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val warnung = when {
            laeuft && AufnahmeDienst.gesperrt -> "Android hat das Mikrofon gesperrt. Bitte stoppen und neu aufnehmen."
            laeuft && AufnahmeDienst.stumm -> "Mikrofon ist stumm. Offline+ an der Seite ausschalten."
            !laeuft && Offline.an(this) == true -> "Offline+ ist an. Das Mikrofon ist getrennt."
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Mikrofon nicht erlaubt. Knopf antippen, dann „Zulassen“."
            eingeschraenkt -> "Akku-Sparen bremst Braininger: Aufnahmen brechen bei dunklem Bildschirm ab. " +
                "Kabel an den Laptop, dann ist es behoben."
            else -> null
        }
        if (warnung != null) {
            spalte.addView(text(warnung, 16f, dick = true, farbe = Color.WHITE).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { cornerRadius = dpf(8f); setColor(Color.BLACK) }
            }, breit().apply { setMargins(dp(16), dp(10), dp(16), 0) })
        } else {
            spalte.addView(text(if (laeuft) "Lauter-Taste: kurz = markieren, halten = stoppen"
                else "Oder: Lauter-Taste 1 Sekunde halten", 15f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), 0)
            }, breit())
        }

        // Drei große Einträge
        val aufgaben = Daten.aufgaben(this, alle)
        // ruhig, ohne Zahlen: was heute dran ist, sonst was diese Woche, sonst was irgendwann (Grill 20.10.)
        val offen = aufgaben.filter { !it.erledigt }
        val korbTitel = listOf("Heute", "Diese Woche", "Irgendwann")
        val ersterKorb = offen.minOfOrNull { korb(it) }
        val aufgabenText = if (ersterKorb == null) "Nichts offen" else
            korbTitel[ersterKorb] + ": " + offen.filter { korb(it) == ersterKorb }.joinToString(" · ") { it.text }
        val sachen = Daten.einkauf(this, alle).filter { !it.abgehakt }
        val einkaufText = when (sachen.size) {
            0 -> "Liste ist leer"
            1, 2 -> sachen.joinToString(", ") { it.sache }
            else -> sachen.take(2).joinToString(", ") { it.sache } + " und ${sachen.size - 2} mehr"
        }
        val wartend = alle.count { it.stand != null }
        val letzte = alle.firstOrNull()?.start
        val aufnahmenText = when {
            letzte == null -> "Noch keine"
            wartend > 0 -> "zuletzt ${tag(letzte, true)} · $wartend beim Laptop"
            else -> "zuletzt ${tag(letzte, true)}"
        }
        val menue = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menue.addView(menuePunkt("Aufgaben", aufgabenText) { oeffnen(Ansicht.AUFGABEN) })
        menue.addView(punktlinie())
        menue.addView(menuePunkt("Einkauf", einkaufText) { oeffnen(Ansicht.EINKAUF) })
        menue.addView(punktlinie())
        menue.addView(menuePunkt("Tagebuch", aufnahmenText) { oeffnen(Ansicht.AUFNAHMEN) })
        spalte.addView(menue, breit().apply { setMargins(dp(16), dp(14), dp(16), 0) })

        // Rest füllen, Laptop-Zeile ganz unten
        spalte.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        spalte.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, breit(dp(2)))
        val kontakt = Lager.letzterKontakt(this)
        val stand = when {
            abgleichLaeuft -> "Gleiche mit dem Laptop ab …"
            kontakt == 0L -> "Zum Abgleichen Kabel an den Laptop"
            else -> "Laptop zuletzt ${tag(Date(kontakt), true)}"
        }
        spalte.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            addView(text(stand, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(symbol(R.drawable.aktualisieren, 28))
            setOnClickListener { abgleichen(); zeichnen() }
        }, breit(dp(56)))
        wurzel.addView(spalte)
    }

    private fun mitZeit(d: Date): Boolean {
        val k = Calendar.getInstance().apply { time = d }
        return k.get(Calendar.HOUR_OF_DAY) != 0 || k.get(Calendar.MINUTE) != 0
    }

    private fun menuePunkt(titel: String, unter: String, aktion: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(LinearLayout(this@LiteActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(titel, 23f, dick = true))
            addView(text(unter, 16f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(symbol(R.drawable.pfeil_rechts, 28))
        setOnClickListener { aktion() }
    }

    // ----- Aufgaben

    /**
     * Drei ruhige Körbe nach Jans Regeln (Grill Kalender/ADHS, 20.10.2026): Heute, Diese Woche, Irgendwann.
     * Keine Uhrzeiten, keine Zahlen, kein „überfällig“: Liegengebliebenes steht still unter Heute mit „seit Fr.“,
     * eine Frist von morgen wandert schon heute nach Heute.
     * Erst einzeilig; der übrige Platz geht der Reihe nach an offene Aufgaben, die zweizeilig ganz lesbar werden,
     * solange alles auf die Seite passt. Antippen klappt eine Aufgabe auf, das Kästchen hakt ab.
     */
    private fun aufgabenSeite(alle: List<Eintrag>) {
        val liste = Daten.aufgaben(this, alle)
        if (liste.none { it.schluessel == offeneAufgabe }) offeneAufgabe = null
        rahmen(kopf("Aufgaben", zurueckPfeil = true)) { breite, hoehe ->
            fun hoeheVon(v: View): Int {
                v.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                return v.measuredHeight
            }
            val zweizeilig = mutableSetOf<String>()
            var gesamt = aufgabenZeilen(liste, zweizeilig).first.sumOf { hoeheVon(it) }
            if (gesamt <= hoehe) for (a in liste) {
                if (a.erledigt || a.schluessel == offeneAufgabe) continue
                val mehr = hoeheVon(aufgabeZeile(a, rechtsText(a), false, 2)) - hoeheVon(aufgabeZeile(a, rechtsText(a), false, 1))
                if (mehr > 0 && gesamt + mehr <= hoehe) {
                    zweizeilig += a.schluessel
                    gesamt += mehr
                }
            }
            val (zeilen, fokus) = aufgabenZeilen(liste, zweizeilig)
            liste(zeilen, "Nichts offen.", fokus)(breite, hoehe)
        }
    }

    /** 0 = Heute (auch Liegengebliebenes und Fristen von morgen), 1 = Diese Woche, 2 = Irgendwann. */
    private fun korb(a: Aufgabe): Int {
        val tage = a.frist?.let { tageBis(it) } ?: return 2
        return when {
            tage <= 1 -> 0
            tage <= 7 -> 1
            else -> 2
        }
    }

    /** Leise Angabe rechts: „seit Fr.“, „morgen“, „Mi.“ oder ein Datum. Nie eine Uhrzeit. */
    private fun rechtsText(a: Aufgabe): String? {
        val f = a.frist ?: return null
        if (a.erledigt) return null
        val tage = tageBis(f)
        return when {
            tage < -6 -> "seit " + SimpleDateFormat("d.M.", Locale.GERMANY).format(f)
            tage < 0 -> "seit " + SimpleDateFormat("EE", Locale.GERMANY).format(f)
            tage == 0 -> null
            tage == 1 -> "morgen"
            tage <= 7 -> SimpleDateFormat("EE", Locale.GERMANY).format(f)
            else -> SimpleDateFormat("d.M.", Locale.GERMANY).format(f)
        }
    }

    /** Ausgeschrieben für die aufgeklappte Aufgabe: „seit Freitag, 6.11.“, „bis morgen“, „bis Mittwoch, 11.11.“. */
    private fun fristLang(f: Date): String {
        val tage = tageBis(f)
        val datum = SimpleDateFormat("EEEE, d.M.", Locale.GERMANY).format(f)
        return when {
            tage < 0 -> "seit $datum"
            tage == 0 -> "bis heute"
            tage == 1 -> "bis morgen"
            else -> "bis $datum"
        }
    }

    /** Zeilen samt Zwischenüberschriften; zweites Ergebnis ist die aufgeklappte Zeile. */
    private fun aufgabenZeilen(liste: List<Aufgabe>, zweizeilig: Set<String>): Pair<List<View>, View?> {
        val zeilen = mutableListOf<View>()
        var fokus: View? = null
        var letzteGruppe: String? = null
        for (a in liste) {
            val gruppe = if (a.erledigt) "Erledigt" else listOf("Heute", "Diese Woche", "Irgendwann")[korb(a)]
            if (gruppe != letzteGruppe) {
                zeilen += ueberschrift(gruppe)
                letzteGruppe = gruppe
            }
            val offen = a.schluessel == offeneAufgabe
            val z = aufgabeZeile(a, rechtsText(a), offen, if (a.schluessel in zweizeilig) 2 else 1)
            zeilenKontext[z] = gruppe
            if (offen) fokus = z
            zeilen += z
        }
        return zeilen to fokus
    }

    /** Kleine Zwischenüberschrift in Großbuchstaben, z. B. HEUTE. */
    private fun ueberschrift(t: String) = text(t.uppercase(Locale.GERMANY), 14f, dick = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(10), 0, dp(1))
        tag = UEBERSCHRIFT
    }

    /** Kästchen links mit großer Tippfläche. */
    private fun kaestchen(erledigt: Boolean, umschalten: () -> Unit) = FrameLayout(this).apply {
        addView(symbol(if (erledigt) R.drawable.kasten_voll else R.drawable.kasten, 26),
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.START or Gravity.TOP).apply { topMargin = dp(10) })
        contentDescription = if (erledigt) "Erledigt, antippen zum Zurücknehmen" else "Abhaken"
        setOnClickListener { umschalten() }
    }

    private fun aufgabeZeile(a: Aufgabe, rechts: String?, offen: Boolean, maxZeilen: Int): View {
        val oben = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        oben.addView(kaestchen(a.erledigt) {
            Daten.aufgabeUmschalten(this, a)
            zeichnen()
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(11), 0, if (offen) dp(4) else dp(11))
            addView(text(a.text, 19f, dick = offen).apply {
                if (!offen) {
                    maxLines = maxZeilen
                    ellipsize = TextUtils.TruncateAt.END
                }
                if (a.erledigt) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (rechts != null && !offen) addView(text(rechts, 15f).apply { setPadding(dp(10), dp(3), 0, 0) })
            addView(symbol(if (offen) R.drawable.pfeil_oben else R.drawable.pfeil_unten, 22).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(8), dp(2), 0, 0)
            })
            setOnClickListener {
                offeneAufgabe = if (offen) null else a.schluessel
                zeichnen()
            }
        }
        oben.addView(inhalt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val zeile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!offen) {
            zeile.addView(oben, breit())
            zeile.addView(punktlinie())
            return zeile
        }
        // Aufgeklappt: umrandet, mit allem, was dazugehört
        val mehr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), 0, dp(4), dp(12))
            val f = a.frist
            if (f != null) addView(text(fristLang(f), 16f).apply { setPadding(0, dp(4), 0, 0) })
            if (a.bezug.isNotBlank()) addView(text("Gehört zu: " + a.bezug, 16f).apply { setPadding(0, dp(6), 0, 0) })
            addView(LinearLayout(this@LiteActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(2))
                val wann = a.quelle.start?.let { tag(it, true) } ?: ""
                addView(text("Aus der Aufnahme $wann", 16f, dick = true), LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(symbol(R.drawable.pfeil_rechts, 22))
                setOnClickListener { oeffnen(Ansicht.AUFNAHME, a.quelle.id) }
            })
        }
        zeile.background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(Color.WHITE); setStroke(dp(2), Color.BLACK) }
        zeile.setPadding(dp(10), 0, dp(10), 0)
        zeile.addView(oben, breit())
        zeile.addView(mehr, breit())
        return FrameLayout(this).apply {
            setPadding(0, dp(6), 0, dp(6))
            addView(zeile)
        }
    }

    // ----- Einkauf: nach Läden und Gängen, Antippen hakt ab

    /** Wo eine Zeile hingehört („Rewe · Kühlregal“): steht oben auf einer neuen Seite, wenn die Gruppe weiterläuft. */
    private val zeilenKontext = java.util.WeakHashMap<View, String>()

    private fun ladenName(s: Sache) = s.laden.ifBlank { "Supermarkt" }
    private fun abteilungName(s: Sache) = Daten.ABTEILUNGEN.getOrElse(s.abteilung) { "Sonstiges" }
    private fun hatGaenge(laden: String) = laden == "Supermarkt" || Daten.istSupermarkt(laden)

    /**
     * Gliedert die Liste wie einen Weg durch die Läden: der Laden als Überschrift (nur wenn einer genannt ist oder
     * es mehrere gibt), darin bei Supermärkten (auch Aldi, Rewe …) die Gänge in der festen Reihenfolge vom Eingang
     * zur Kasse (Grill Einkauf 2.11.), bei anderen Läden (dm, Bäcker) einfach die Sachen. Abgehaktes am Ende.
     */
    private fun einkaufGliedern(sachen: List<Sache>, zeile: (Sache) -> View): List<View> {
        val zeilen = mutableListOf<View>()
        val offen = sachen.filter { !it.abgehakt }
        val laeden = offen.map { ladenName(it) }.distinct()
        val mitLaeden = laeden.size > 1 || laeden.singleOrNull()?.let { it != "Supermarkt" } == true
        val mitGaengen = offen.map { ladenName(it) to abteilungName(it) }.distinct().size > 1
        var letzterLaden: String? = null
        var letzterGang: String? = null
        for (s in offen) {
            val laden = ladenName(s)
            if (mitLaeden && laden != letzterLaden) {
                zeilen += ladenKopf(laden, erster = zeilen.isEmpty())
                letzterLaden = laden
                letzterGang = null
            }
            val gang = if (mitGaengen && hatGaenge(laden)) abteilungName(s) else null
            if (gang != null && gang != letzterGang) {
                zeilen += ueberschrift(gang)
                letzterGang = gang
            }
            val z = zeile(s)
            listOfNotNull(if (mitLaeden) laden else null, gang).joinToString(" · ").takeIf { it.isNotBlank() }
                ?.let { zeilenKontext[z] = it }
            zeilen += z
        }
        val korb = sachen.filter { it.abgehakt }
        if (korb.isNotEmpty() && offen.isNotEmpty()) zeilen += ueberschrift("Im Korb")
        korb.forEach { s -> zeilen += zeile(s).also { if (offen.isNotEmpty()) zeilenKontext[it] = "Im Korb" } }
        return zeilen
    }

    /** Laden als Überschrift: Name fett, darunter der kurze Strich wie im Tagebuch. */
    private fun ladenKopf(laden: String, erster: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, if (erster) dp(2) else dp(20), 0, dp(2))
        tag = UEBERSCHRIFT
        addView(text(laden, 21f, dick = true))
        addView(View(this@LiteActivity).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(dp(28), dp(3)).apply { topMargin = dp(6) })
    }

    private fun einkaufSeite(alle: List<Eintrag>) {
        val sachen = Daten.einkauf(this, alle)
        val zeilen = einkaufGliedern(sachen) { s ->
            hakenZeile(s.sache, s.details.ifBlank { null }, s.abgehakt) {
                Daten.sacheUmschalten(this, s)
                zeichnen()
            }
        }.toMutableList()
        if (sachen.any { it.abgehakt }) {
            zeilen += leiseZeile("Abgehakte entfernen", R.drawable.pfeil_rechts, einzug = 0) {
                Daten.abgehakteWeg(this@LiteActivity, sachen)
                seite = 0
                zeichnen()
            }
        }
        val offen = sachen.filter { !it.abgehakt }
        rahmen(kopf("Einkauf", zurueckPfeil = true), unten = einkaufKnoepfe(offen), inhalt = liste(zeilen, "Die Liste ist leer."))
    }

    /** Unten fest: „Im Laden“ (schwarz) und „Teilen“ (umrandet), nur wenn etwas offen ist. */
    private fun einkaufKnoepfe(offen: List<Sache>): View? {
        if (offen.isEmpty()) return null
        fun knopf(t: String, voll: Boolean, aktion: () -> Unit) = text(t, 18f, dick = true, farbe = if (voll) Color.WHITE else Color.BLACK).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(if (voll) Color.BLACK else Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            setOnClickListener { aktion() }
        }
        return LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(10))
            addView(knopf("Im Laden", true) { ladenLetzte = null; oeffnen(Ansicht.LADEN) },
                LinearLayout.LayoutParams(0, dp(50), 1.4f))
            addView(View(this@LiteActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(knopf("Teilen", false) { teilen(offen) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
    }

    /**
     * Im Laden: nur Offenes, groß, der Bildschirm bleibt an. Antippen legt die Sache still in den Korb (sie
     * verschwindet aus dem Blick), unten steht „Rückgängig“ für die zuletzt getippte.
     */
    private fun ladenSeite(alle: List<Eintrag>) {
        val offen = Daten.einkauf(this, alle).filter { !it.abgehakt }
        val zeilen = einkaufGliedern(offen) { s ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@LiteActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(14), 0, dp(14))
                    addView(symbol(R.drawable.kasten, 30).apply {
                        (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(16)
                    })
                    addView(LinearLayout(this@LiteActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(s.sache, 23f, dick = true))
                        if (s.details.isNotBlank()) addView(text(s.details, 16f).apply { setPadding(0, dp(3), 0, 0) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener {
                        Daten.sacheUmschalten(this@LiteActivity, s)
                        ladenLetzte = s
                        zeichnen()
                    }
                }, breit())
                addView(punktlinie())
            }
        }
        val rueck = ladenLetzte?.let { s ->
            leiseZeile("Rückgängig: ${s.sache}", null, einzug = dp(16)) {
                Daten.sacheOeffnen(this@LiteActivity, s)
                ladenLetzte = null
                zeichnen()
            }
        }
        rahmen(kopf("Im Laden", zurueckPfeil = true), unten = rueck, inhalt = liste(zeilen, "Alles im Korb."))
    }

    /**
     * Alles Offene als ruhiger Text über das Teilen-Fenster (z. B. WhatsApp an Anna): je Laden ein Block, bei
     * Supermärkten eine Zeile pro Gang („Kühlregal: Milch, Butter“).
     */
    private fun teilen(offen: List<Sache>) {
        val text = buildString {
            append("Einkauf (").append(SimpleDateFormat("EE d.M.", Locale.GERMANY).format(Date())).append(")\n")
            val nachLaden = offen.groupBy { ladenName(it) }
            for ((laden, sachen) in nachLaden) {
                append("\n")
                if (nachLaden.size > 1 || laden != "Supermarkt") append(laden.uppercase(Locale.GERMANY)).append("\n")
                fun name(s: Sache) = s.sache + if (s.details.isNotBlank()) " (${s.details})" else ""
                if (hatGaenge(laden)) {
                    for ((gang, imGang) in sachen.groupBy { abteilungName(it) }) {
                        append(gang).append(": ").append(imGang.joinToString(", ") { name(it) }).append("\n")
                    }
                } else append(sachen.joinToString(", ") { name(it) }).append("\n")
            }
        }.trimEnd()
        try {
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text), "Einkaufsliste teilen"))
        } catch (_: Exception) {}
    }

    /** Einkaufszeile: Kästchen, Sache, dahinter klein Menge/Details. Die ganze Zeile hakt ab (eine Hand im Laden). */
    private fun hakenZeile(haupt: String, unter: String?, erledigt: Boolean, umschalten: () -> Unit): View {
        val t = SpannableStringBuilder(haupt)
        if (unter != null) {
            val s = t.length
            t.append("   ").append(unter)
            t.setSpan(AbsoluteSizeSpan(sp(15f).toInt()), s, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val zeile = LinearLayout(this).apply {
            addView(kaestchen(erledigt, umschalten), LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(text(t, 19f).apply {
                setPadding(0, dp(11), 0, dp(11))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                if (erledigt) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { umschalten() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(zeile, breit())
            addView(punktlinie())
        }
    }

    // ----- Tagebuch: die Aufnahmen als Buch nach Tagen

    /**
     * Jeder Tag mit Inhalt beginnt auf einer neuen Seite wie ein Kapitel: Wochentag groß, Datum, ein kurzer Strich,
     * darunter der Tagesüberblick vom Laptop (sobald der Tag vorbei ist) und die Gedanken in der Reihenfolge des Tages.
     * Leere Aufnahmen stören nicht: sie stehen als eine leise Zeile da und lassen sich aufklappen. Tage ohne Inhalt
     * bekommen keine eigene Seite.
     */
    private fun tagebuch(alle: List<Eintrag>) {
        val zeilen = mutableListOf<View>()
        // Nachträge stehen bei ihrem Gedanken, nicht als eigene Zeile
        val eigene = alle.filter { Daten.nachtragsZiel(this, it, alle) == null }
        for ((tag, eintraege) in eigene.groupBy { it.id.take(10) }) { // alle ist neu → alt sortiert
            val hatNachtrag = { e: Eintrag -> Daten.nachtraegeVon(this, e, alle).isNotEmpty() }
            // reine Einkaufs-Ansagen: leise Zeile, machen aus einem Tag noch kein Kapitel
            val einkaeufe = eintraege.filter { it.nurEinkauf && !hatNachtrag(it) }
            val mitInhalt = eintraege.filter {
                it !in einkaeufe && (it.hatInhalt || it.stand != null || it.laeuft || hatNachtrag(it))
            }
            val leer = eintraege.filter { it !in mitInhalt && it !in einkaeufe }.sortedBy { it.id }
            zeilen += tagesKopf(tag, eigeneSeite = mitInhalt.isNotEmpty())
            val ue = uebersichtVon(tag)
            if (ue != null && mitInhalt.any { it.hatInhalt }) zeilen += ueberblick(tag, ue)
            for (e in (mitInhalt + einkaeufe).sortedBy { it.id }) {
                zeilen += if (e in einkaeufe) einkaufsZeile(e) else tagebuchZeile(e, alle)
            }
            if (leer.isNotEmpty()) {
                if (tag in leereOffen) leer.forEach { zeilen += tagebuchZeile(it, alle) }
                else zeilen += leiseZeile(if (leer.size == 1) "1 leere Aufnahme" else "${leer.size} leere Aufnahmen") {
                    leereOffen += tag
                    zeichnen()
                }
            }
        }
        rahmen(kopf("Tagebuch", zurueckPfeil = true), inhalt = liste(zeilen, "Noch keine Aufnahmen."))
    }

    private fun tagesKopf(tag: String, eigeneSeite: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, if (eigeneSeite) dp(2) else dp(18), 0, dp(6))
        this.tag = if (eigeneSeite) TAGESKOPF else UEBERSCHRIFT
        val d = tagDatum(tag)
        val abstand = d?.let { tageBis(it) }
        // kleine Zeile über jedem Tag, damit alle Kapitel gleich beginnen
        val oben = when {
            abstand == null -> null
            abstand == 0 -> "Heute"
            abstand == -1 -> "Gestern"
            abstand == -2 -> "Vorgestern"
            abstand > 0 -> null
            abstand > -14 -> "Vor ${-abstand} Tagen"
            abstand > -60 -> "Vor ${-abstand / 7} Wochen"
            else -> SimpleDateFormat("MMMM yyyy", Locale.GERMANY).format(d!!)
        }
        if (oben != null) addView(text(oben.uppercase(Locale.GERMANY), 13f, dick = true).apply {
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(4))
        })
        if (d != null) {
            addView(text(SimpleDateFormat("EEEE", Locale.GERMANY).format(d), if (eigeneSeite) 30f else 22f, dick = true))
            addView(text(SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY).format(d), 16f).apply { setPadding(0, dp(3), 0, 0) })
        } else addView(text(tag, 22f, dick = true))
        // kurzer, kräftiger Strich als ruhiger Akzent
        addView(View(this@LiteActivity).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(dp(36), dp(3)).apply { topMargin = dp(10) })
    }

    /** Der Tag in Kürze, antippen öffnet den ganzen Überblick. */
    private fun ueberblick(tag: String, ue: org.json.JSONObject): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(10))
        addView(text(ue.optString("titel"), 19f, dick = true))
        val kurz = kurzfassung(ue.optString("inhalt"))
        if (kurz.isNotBlank()) addView(text(kurz, 17f).apply {
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dpf(4f), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        addView(LinearLayout(this@LiteActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(text("Ganzer Tagesüberblick", 15f, dick = true))
            addView(symbol(R.drawable.pfeil_rechts, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4)
            })
        })
        setOnClickListener { oeffnen(Ansicht.UEBERSICHT, tag) }
    }

    /** Abschnitt „Der Tag in Kürze“ aus dem Markdown, sonst der erste Absatz. */
    private fun kurzfassung(inhalt: String): String {
        val zeilen = inhalt.lines()
        val ab = zeilen.indexOfFirst { it.trimStart().startsWith("#") && it.contains("in Kürze", ignoreCase = true) }
        val teil = if (ab >= 0) zeilen.drop(ab + 1).takeWhile { !it.trimStart().startsWith("#") }
        else zeilen.dropWhile { it.isBlank() || it.trimStart().startsWith("#") }.takeWhile { it.isNotBlank() }
        return teil.joinToString(" ") { it.trim() }.replace("**", "").trim()
    }

    /** Uhrzeit in eigener Spalte (gleich breite Ziffern), daneben der Gedanke. */
    private fun tagebuchZeile(e: Eintrag, alle: List<Eintrag>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@LiteActivity).apply {
            setPadding(0, dp(11), 0, dp(11))
            addView(text(e.start?.let { SimpleDateFormat("HH:mm", Locale.GERMANY).format(it) } ?: "", 16f).apply {
                fontFeatureSettings = "tnum"
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@LiteActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(e.titel, 18f, dick = e.hatInhalt || e.laeuft).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
                // der Gedanke in einem Satz, wie ein Tagebucheintrag
                e.kurz?.takeIf { e.hatInhalt && it != e.titel }?.let { k ->
                    addView(text(k, 16f).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(5), 0, 0)
                    })
                }
                val unter = listOfNotNull(e.stand, if (!e.hatInhalt && e.stand == null) e.dauerText().ifBlank { null } else null)
                if (unter.isNotEmpty()) addView(text(unter.joinToString(" · "), 15f).apply { setPadding(0, dp(4), 0, 0) })
                val nachtraege = Daten.nachtraegeVon(this@LiteActivity, e, alle)
                if (nachtraege.isNotEmpty()) {
                    val zuletzt = nachtraege.last().start?.let { tag(it, false) }.orEmpty()
                    val wort = if (nachtraege.size == 1) "+ Nachtrag $zuletzt" else "+ ${nachtraege.size} Nachträge, zuletzt $zuletzt"
                    addView(text(wort, 15f, dick = true).apply { setPadding(0, dp(6), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(symbol(R.drawable.pfeil_rechts, 20).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(8), dp(2), 0, 0)
            })
            setOnClickListener { oeffnen(Ansicht.AUFNAHME, e.id) }
        }, breit())
        addView(punktlinie())
    }

    /** „18:20  Einkauf: Milch, Eier“: eine Einkaufs-Ansage, ruhig in der Zeitspalte, antippen öffnet sie trotzdem. */
    private fun einkaufsZeile(e: Eintrag): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@LiteActivity).apply {
            setPadding(0, dp(10), 0, dp(10))
            addView(text(e.start?.let { SimpleDateFormat("HH:mm", Locale.GERMANY).format(it) } ?: "", 16f).apply {
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            val t = SpannableStringBuilder("Einkauf: ").apply {
                setSpan(TypefaceSpan(fett), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(e.einkaufText())
            }
            addView(text(t, 16f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { oeffnen(Ansicht.AUFNAHME, e.id) }
        }, breit())
        addView(punktlinie())
    }

    /** Leise, antippbare Zeile (z. B. „3 leere Aufnahmen ⌄“, „Rückgängig: Milch“). */
    private fun leiseZeile(t: String, symbolRes: Int? = R.drawable.pfeil_unten, einzug: Int = dp(56), aktion: () -> Unit): View =
        LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(einzug, dp(12), 0, dp(12))
        addView(text(t, 16f, dick = symbolRes == null))
        if (symbolRes != null) addView(symbol(symbolRes, 18).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
        })
        setOnClickListener { aktion() }
    }

    // ----- Eine Aufnahme: Text wie im Buch auf Seiten verteilt

    private fun aufnahmeSeite(e: Eintrag?, alle: List<Eintrag>) {
        if (e == null) { zurueck(); return }
        val titel = e.start?.let { SimpleDateFormat("EE d.M. · HH:mm", Locale.GERMANY).format(it) } ?: "Aufnahme"
        rahmen(kopf(titel, zurueckPfeil = true), unten = weitersprechenKnopf(e)) { breite, hoehe ->
            buchSeite(inhaltText(e, alle), breite, hoehe)
        }
    }

    /**
     * „Weitersprechen“: nimmt einen Nachtrag zu diesem Gedanken auf. Bewusst nur als Knopf (Jan, 8.11.): die
     * Lauter-Taste bleibt überall eindeutig. Läuft der Nachtrag, wird der Knopf schwarz und beendet ihn.
     * Läuft gerade eine andere Aufnahme, gibt es keinen Knopf.
     */
    private fun weitersprechenKnopf(e: Eintrag): View? {
        if (e.laeuft) return null
        val meinNachtrag = AufnahmeDienst.laeuft && AufnahmeDienst.nachtragZu == e.id
        if (AufnahmeDienst.laeuft && !meinNachtrag) return null
        val knopf = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(if (meinNachtrag) Color.BLACK else Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            addView(symbol(if (meinNachtrag) R.drawable.quadrat_weiss else R.drawable.punkt_schwarz, 14))
            val farbe = if (meinNachtrag) Color.WHITE else Color.BLACK
            addView(text(if (meinNachtrag) "Nachtrag beenden" else "Weitersprechen", 18f, dick = true, farbe = farbe).apply {
                setPadding(dp(10), 0, 0, 0)
            })
            contentDescription = if (meinNachtrag) "Nachtrag beenden" else "Nachtrag zu diesem Gedanken aufnehmen"
            setOnClickListener {
                when {
                    meinNachtrag -> AufnahmeDienst.stoppen(this@LiteActivity)
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    else -> AufnahmeDienst.nachtragStarten(this@LiteActivity, e.id, e.titel)
                }
            }
        }
        return FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(10))
            addView(knopf, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }
    }


    /**
     * Buchsatz für Aufnahme und Tagesübersicht: Absätze mit kleinem Abstand statt Leerzeilen, hängende Einzüge für
     * Aufzählungen und Kästchen, Markdown der Notizen (Überschriften, Punkte, Checklisten „- [ ]“).
     */
    private inner class Satz {
        val b = SpannableStringBuilder()
        private val messen = TextPaint().apply { typeface = normal; textSize = sp(18f) }

        /** Kleiner Abstand zwischen Absätzen (eine niedrige Leerzeile statt einer ganzen). */
        fun luecke() {
            if (b.isEmpty()) return
            val s = b.length
            b.append("\n")
            b.setSpan(AbsoluteSizeSpan(sp(9f).toInt()), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** Ein Absatz; einzug = hängender Einzug für Folgezeilen (Aufzählungen, Kästchen). */
        fun absatz(t: CharSequence, groesse: Float, dick: Boolean = false, abstandVorher: Boolean = true, einzug: Int = 0) {
            if (abstandVorher) luecke()
            val start = b.length
            b.append(t)
            b.setSpan(AbsoluteSizeSpan(sp(groesse).toInt()), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(TypefaceSpan(if (dick) fett else normal), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("\n")
            if (einzug > 0) b.setSpan(android.text.style.LeadingMarginSpan.Standard(0, einzug), start, b.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** Zeile mit gezeichnetem Kästchen vorn; Folgezeilen stehen unter dem Text, nicht unter dem Kästchen. */
        fun kaestchenZeile(text: String, erledigt: Boolean = false) {
            val start = b.length
            b.append("  ")
            getDrawable(if (erledigt) R.drawable.kasten_voll else R.drawable.kasten)?.let { d ->
                val g = sp(17f).toInt()
                d.setBounds(0, 0, g, g)
                b.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b.append(text)
            b.setSpan(AbsoluteSizeSpan(sp(18f).toInt()), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(TypefaceSpan(normal), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("\n")
            b.setSpan(android.text.style.LeadingMarginSpan.Standard(0, sp(17f).toInt() + messen.measureText(" ").toInt()),
                start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** Antippbare Zeile im Buchtext, fett mit „›“, ohne Unterstreichung (buchSeite wertet das Tippen aus). */
        fun verweis(t: String, aktion: () -> Unit) {
            luecke()
            val start = b.length
            b.append("$t  ›")
            b.setSpan(AbsoluteSizeSpan(sp(16f).toInt()), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(TypefaceSpan(fett), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) = aktion()
                override fun updateDrawState(ds: TextPaint) {} // bleibt schwarz, keine Linie
            }, start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append("\n")
        }

        fun markdown(inhalt: String) {
            var vorherPunkt = false
            val punktEinzug = messen.measureText("•  ").toInt()
            val punkt = Regex("^\\s*[-*]\\s+")
            val haken = Regex("^\\s*[-*]\\s+\\[([ xX])]\\s*")
            for (z in inhalt.lines()) {
                val zeile = z.trimEnd().replace("**", "")
                val istPunkt = punkt.containsMatchIn(zeile)
                when {
                    zeile.isBlank() -> {}
                    zeile.trimStart().startsWith("#") -> absatz(zeile.trimStart('#', ' '), 18f, dick = true)
                    haken.containsMatchIn(zeile) -> {
                        if (!vorherPunkt) luecke()
                        val m = haken.find(zeile)!!
                        kaestchenZeile(zeile.substring(m.range.last + 1), erledigt = m.groupValues[1].isNotBlank())
                    }
                    istPunkt -> absatz(zeile.replace(punkt, "•  "), 18f, abstandVorher = !vorherPunkt, einzug = punktEinzug)
                    else -> absatz(zeile, 18f)
                }
                if (zeile.isNotBlank()) vorherPunkt = istPunkt
            }
        }
    }

    /**
     * Ein Gedanke wie im Buch. Nachträge stehen darunter, jeder mit eigener Überschrift („Nachtrag · Montag, 18:42“);
     * der ursprüngliche Gedanke wird nie umgeschrieben. Unter jedem Nachtrag „Als eigenen Gedanken führen“, auf einem
     * gelösten Nachtrag „Wieder als Nachtrag anhängen“: beides nur am Kompakt vermerkt, nichts wird gelöscht.
     */
    private fun inhaltText(e: Eintrag, alle: List<Eintrag>): CharSequence {
        val s = Satz()
        s.absatz(e.titel, 23f, dick = true, abstandVorher = false)
        inhaltTeil(s, e)
        for (n in Daten.nachtraegeVon(this, e, alle)) {
            s.luecke()
            s.luecke()
            val wann = n.start?.let { SimpleDateFormat("EEEE, HH:mm", Locale.GERMANY).format(it) } ?: ""
            s.absatz("Nachtrag · $wann", 19f, dick = true)
            val nTitel = n.titel.replace(Regex("^Nachtrag\\s*[:–-]\\s*", RegexOption.IGNORE_CASE), "") // steht schon drüber
            if (n.hatInhalt && nTitel != e.titel && nTitel.isNotBlank()) s.absatz(nTitel, 18f, abstandVorher = false)
            inhaltTeil(s, n, ohneMeta = n.stand == null)
            s.verweis("Als eigenen Gedanken führen") {
                Daten.nachtragLoesen(this, n, true)
                zeichnen()
            }
        }
        val ziel = e.nachtragZu?.takeIf { z -> Daten.nachtragsZiel(this, e, alle) == null && alle.any { it.id == z } }
        if (ziel != null) {
            s.luecke()
            s.verweis("Wieder als Nachtrag anhängen") {
                Daten.nachtragLoesen(this, e, false)
                gewaehlt = ziel
                zeichnen()
            }
        }
        return s.b
    }

    /** Meta-Zeile, Kurzfassung, Aufgaben, Einkauf, Notizen eines Eintrags. */
    private fun inhaltTeil(s: Satz, e: Eintrag, ohneMeta: Boolean = false) {
        if (!ohneMeta) {
            val markiert = e.info.optJSONArray("markierungen")?.length() ?: 0
            val meta = listOfNotNull(
                e.dauerText().ifBlank { null },
                when (markiert) { 0 -> null; 1 -> "1 Stelle markiert"; else -> "$markiert Stellen markiert" },
                e.stand,
            )
            s.absatz(meta.joinToString(" · ").ifBlank { " " }, 15f, abstandVorher = false)
        }
        val erg = e.erg ?: return
        if (!e.hatInhalt) {
            s.absatz("Der Laptop hat hier nichts zum Merken gefunden.", 18f)
            return
        }
        e.kurz?.let { if (it != e.titel) s.absatz(it, 18f) }

        val aufgaben = erg.optJSONArray("aufgaben")
        if (aufgaben != null && aufgaben.length() > 0) {
            s.absatz("Aufgaben", 18f, dick = true)
            for (i in 0 until aufgaben.length()) {
                val a = aufgaben.optJSONObject(i) ?: continue
                if (a.optString("art") == "erledigt") continue
                val frist = a.optString("frist").takeIf { it.length >= 10 }?.let {
                    try {
                        " · " + tag(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it.take(10))!!, false) // nie Uhrzeit
                    } catch (_: Exception) { "" }
                } ?: ""
                s.kaestchenZeile(a.optString("text") + frist)
            }
        }
        val einkauf = erg.optJSONArray("einkauf")
        if (einkauf != null && einkauf.length() > 0) {
            s.absatz("Einkauf", 18f, dick = true)
            s.absatz((0 until einkauf.length()).mapNotNull { einkauf.optJSONObject(it)?.optString("sache") }.joinToString(", "),
                18f, abstandVorher = false)
        }
        val notizen = erg.optJSONArray("notizen")
        if (notizen != null) for (i in 0 until notizen.length()) {
            val n = notizen.optJSONObject(i) ?: continue
            val inhalt = n.optString("inhalt")
            if (inhalt.isBlank()) continue
            if (notizen.length() > 1 || n.optString("titel") != e.titel) s.absatz(n.optString("titel"), 19f, dick = true)
            s.markdown(inhalt)
        }
    }

    // ----- Tagesübersicht (vom Laptop, am Morgen nach dem Tag): ganz, wie im Buch

    private fun uebersichtVon(tag: String): org.json.JSONObject? =
        Lager.json(Lager.uebersicht(this, tag))?.takeIf {
            it.optString("status") == "fertig" && it.optString("inhalt").isNotBlank() && it.optString("inhalt") != "null"
        }

    private fun tagDatum(tag: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(tag)
    } catch (_: Exception) { null }

    private fun uebersichtSeite(tag: String?) {
        val ue = tag?.let { uebersichtVon(it) }
        if (ue == null) { zurueck(); return }
        val titel = tagDatum(tag)?.let { SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(it) } ?: tag
        rahmen(kopf(titel, zurueckPfeil = true)) { breite, hoehe ->
            val s = Satz()
            s.absatz(ue.optString("titel"), 23f, dick = true, abstandVorher = false)
            s.markdown(ue.optString("inhalt"))
            buchSeite(s.b, breite, hoehe)
        }
    }

    /**
     * Zeigt den Text seitenweise: einmal gesetzt (mit deutscher Silbentrennung), dann an Zeilengrenzen in Seiten
     * geschnitten. Gezeichnet wird genau dieses Layout, deshalb passt jede Seite exakt.
     */
    private fun buchSeite(text: CharSequence, breite: Int, hoehe: Int): View {
        val stift = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = normal
            textSize = sp(18f)
            textLocale = Locale.GERMANY
        }
        val satz = StaticLayout.Builder.obtain(text, 0, text.length, stift, breite)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(dpf(4f), 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
        val anfaenge = mutableListOf(0)
        for (z in 0 until satz.lineCount) {
            if (satz.getLineBottom(z) - satz.getLineTop(anfaenge.last()) > hoehe && z > anfaenge.last()) anfaenge += z
        }
        seitenZahl = anfaenge.size
        seite = seite.coerceIn(0, seitenZahl - 1)
        val von = anfaenge[seite]
        val bis = if (seite + 1 < anfaenge.size) anfaenge[seite + 1] else satz.lineCount
        val oben = satz.getLineTop(von)
        val unten = satz.getLineBottom(bis - 1)
        return object : View(this) {
            override fun onDraw(c: Canvas) {
                c.save()
                c.clipRect(0, 0, width, unten - oben)
                c.translate(0f, -oben.toFloat())
                satz.draw(c)
                c.restore()
            }

            /** Antippbare Verweise (Satz.verweis) im gezeichneten Text finden. */
            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                val s = text as? Spanned ?: return false
                val y = ev.y.toInt() + oben
                if (y > unten) return false
                val zeile = satz.getLineForVertical(y)
                if (zeile < von || zeile >= bis || ev.x > satz.getLineRight(zeile) + dp(24)) return false
                val stelle = satz.getOffsetForHorizontal(zeile, ev.x)
                val treffer = s.getSpans(stelle, stelle, android.text.style.ClickableSpan::class.java).firstOrNull()
                    ?: return false
                if (ev.action == android.view.MotionEvent.ACTION_UP) treffer.onClick(this)
                return true
            }
        }
    }
}
