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
    }

    private enum class Ansicht { START, AUFGABEN, EINKAUF, AUFNAHMEN, AUFNAHME }

    private var ansicht = Ansicht.START
    private var seite = 0
    private var seitenZahl = 1
    // damit „zurück“ aus einer Aufnahme dort landet, wo man herkam (Liste oder Aufgaben, gleiche Seite)
    private var zurueckZu = Ansicht.AUFNAHMEN
    private var zurueckSeite = 0
    private var gewaehlt: String? = null
    private var offeneAufgabe: String? = null // aufgeklappte Aufgabe
    private var abgleichLaeuft = false
    private var lauterGehalten = false
    private val lauterHalten = Runnable {
        lauterGehalten = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AufnahmeDienst.umschalten(this)
        } else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private lateinit var wurzel: FrameLayout
    private val neuZeichnen: () -> Unit = { runOnUiThread { if (ansicht == Ansicht.START) zeichnen() } }

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
                    if (blaetterbar()) blaettern(-1) else lautstaerke(AudioManager.ADJUST_RAISE)
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
        if (a == Ansicht.AUFNAHME) {
            zurueckZu = ansicht
            zurueckSeite = seite
        }
        ansicht = a
        gewaehlt = id
        seite = 0
        zeichnen()
    }

    private fun zurueck() {
        if (ansicht == Ansicht.AUFNAHME) {
            ansicht = zurueckZu
            seite = zurueckSeite
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
        zeile.addView(text(if (seitenZahl > 1) "Seite ${seite + 1} von $seitenZahl" else "", 15f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    private fun rahmen(kopfView: View, inhalt: (Int, Int) -> View) {
        val spalte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        spalte.addView(kopfView)
        val koerper = FrameLayout(this)
        spalte.addView(koerper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fussPlatz = FrameLayout(this)
        spalte.addView(fussPlatz, breit())
        wurzel.addView(spalte)
        // Höhe des Inhalts: alles minus Kopf (63 dp), Abstand oben (12 dp) und unten (8 dp), mit Fuß noch 60 dp weniger
        val breite = wurzel.width - dp(32)
        val ganz = wurzel.height - dp(63) - dp(12) - dp(8)
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
            if (belegt + h > hoehe && seiten.last().isNotEmpty()) {
                val alt = seiten.last()
                val neu = mutableListOf<View>()
                if (alt.size > 1 && alt.last().tag == UEBERSCHRIFT) neu += alt.removeAt(alt.size - 1)
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
            if (zeilen.isEmpty()) addView(text(leer, 18f).apply { setPadding(0, dp(12), 0, 0) })
            seiten[seite].forEach { addView(it, breit()) }
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    private fun zeichnen() {
        if (wurzel.height == 0) { wurzel.post { zeichnen() }; return }
        wurzel.removeAllViews()
        val alle = Daten.eintraege(this)
        when (ansicht) {
            Ansicht.START -> start(alle)
            Ansicht.AUFGABEN -> aufgabenSeite(alle)
            Ansicht.EINKAUF -> einkaufSeite(alle)
            Ansicht.AUFNAHMEN -> aufnahmenListe(alle)
            Ansicht.AUFNAHME -> aufnahmeSeite(alle.firstOrNull { it.id == gewaehlt })
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
        spalte.addView(kopf("Braininger", SimpleDateFormat("EE d. MMM", Locale.GERMANY).format(Date())))

        // Großer Knopf
        val laeuft = AufnahmeDienst.laeuft
        val knopf = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (laeuft) {
                background = GradientDrawable().apply { cornerRadius = dpf(16f); setColor(Color.WHITE); setStroke(dp(3), Color.BLACK) }
                addView(LinearLayout(this@LiteActivity).apply {
                    gravity = Gravity.CENTER
                    addView(symbol(R.drawable.quadrat, 18))
                    addView(text("Stoppen", 26f, dick = true).apply { setPadding(dp(12), 0, 0, 0) })
                })
                val seit = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(AufnahmeDienst.seit))
                addView(text("Aufnahme läuft seit $seit", 16f).apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER })
            } else {
                background = GradientDrawable().apply { cornerRadius = dpf(16f); setColor(Color.BLACK) }
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
        spalte.addView(knopf, breit(dp(118)).apply { setMargins(dp(16), dp(16), dp(16), 0) })

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
            spalte.addView(text("Oder: Lauter-Taste 1 Sekunde halten", 15f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), 0)
            }, breit())
        }

        // Drei große Einträge
        val aufgaben = Daten.aufgaben(this, alle)
        val offen = aufgaben.filter { !it.erledigt }
        val ueberfaellig = offen.count { a -> a.frist?.let { tageBis(it) < 0 } == true }
        val naechste = offen.firstOrNull { a -> a.frist?.let { tageBis(it) >= 0 } == true }?.frist
        val aufgabenText = when {
            offen.isEmpty() -> "Nichts offen"
            ueberfaellig > 0 -> "${offen.size} offen · $ueberfaellig überfällig"
            naechste != null -> "${offen.size} offen · als Nächstes ${tag(naechste, mitZeit(naechste))}"
            else -> "${offen.size} offen"
        }
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
        menue.addView(menuePunkt("Aufnahmen", aufnahmenText) { oeffnen(Ansicht.AUFNAHMEN) })
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
     * Alle Aufgaben kompakt, nach Tagen gruppiert, damit möglichst alles auf eine Seite passt: zweizeilig, wenn das
     * auf eine Seite passt (dann ist fast jede Aufgabe ganz lesbar), sonst einzeilig.
     * Antippen klappt eine Aufgabe auf: ganzer Text, Frist, aus welcher Aufnahme. Das Kästchen hakt ab.
     */
    private fun aufgabenSeite(alle: List<Eintrag>) {
        val liste = Daten.aufgaben(this, alle)
        if (liste.none { it.schluessel == offeneAufgabe }) offeneAufgabe = null
        val offenZahl = liste.count { !it.erledigt }
        rahmen(kopf("Aufgaben", if (offenZahl > 0) "$offenZahl offen" else null, zurueckPfeil = true)) { breite, hoehe ->
            var (zeilen, fokus) = aufgabenZeilen(liste, 2)
            val gesamt = zeilen.sumOf {
                it.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                it.measuredHeight
            }
            if (gesamt > hoehe) aufgabenZeilen(liste, 1).let { zeilen = it.first; fokus = it.second }
            liste(zeilen, "Nichts offen. Schön.", fokus)(breite, hoehe)
        }
    }

    /** Zeilen samt Zwischenüberschriften; zweites Ergebnis ist die aufgeklappte Zeile. */
    private fun aufgabenZeilen(liste: List<Aufgabe>, maxZeilen: Int): Pair<List<View>, View?> {
        val zeilen = mutableListOf<View>()
        var fokus: View? = null
        var letzteGruppe: String? = null
        for (a in liste) {
            val tage = a.frist?.let { tageBis(it) }
            val gruppe = when {
                a.erledigt -> "Erledigt"
                tage == null -> "Ohne Termin"
                tage < 0 -> "Überfällig"
                tage == 0 -> "Heute"
                tage == 1 -> "Morgen"
                else -> "Später"
            }
            if (gruppe != letzteGruppe) {
                zeilen += ueberschrift(gruppe)
                letzteGruppe = gruppe
            }
            val f = a.frist
            val rechts = when {
                f == null || a.erledigt -> null
                tage!! < 0 || tage > 1 -> SimpleDateFormat("EE d.M.", Locale.GERMANY).format(f)
                mitZeit(f) -> SimpleDateFormat("HH:mm", Locale.GERMANY).format(f)
                else -> null
            }
            val offen = a.schluessel == offeneAufgabe
            val z = aufgabeZeile(a, rechts, offen, maxZeilen)
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
            if (f != null) addView(text("bis " + tag(f, mitZeit(f)), 16f).apply { setPadding(0, dp(4), 0, 0) })
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

    // ----- Einkauf: nach Abteilungen im Laden, Antippen hakt ab

    private fun einkaufSeite(alle: List<Eintrag>) {
        val sachen = Daten.einkauf(this, alle)
        val zeilen = mutableListOf<View>()
        var letzteGruppe: String? = null
        fun gruppeVon(s: Sache) = if (s.abgehakt) "Im Korb" else Daten.ABTEILUNGEN.getOrElse(s.abteilung) { "Sonstiges" }
        val mitUeberschriften = sachen.map { gruppeVon(it) }.distinct().size > 1 // eine einzige Gruppe braucht keine
        for (s in sachen) {
            val gruppe = gruppeVon(s)
            if (mitUeberschriften && gruppe != letzteGruppe) {
                zeilen += ueberschrift(gruppe)
                letzteGruppe = gruppe
            }
            zeilen += hakenZeile(s.sache, s.details.ifBlank { null }, s.abgehakt) {
                Daten.sacheUmschalten(this, s)
                zeichnen()
            }
        }
        if (sachen.any { it.abgehakt }) {
            zeilen += text("Abgehakte entfernen", 18f, dick = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                background = GradientDrawable().apply { cornerRadius = dpf(12f); setColor(Color.WHITE); setStroke(dp(2), Color.BLACK) }
                layoutParams = breit()
                setOnClickListener {
                    Daten.abgehakteWeg(this@LiteActivity, sachen)
                    seite = 0
                    zeichnen()
                }
            }.let { knopf -> FrameLayout(this).apply { setPadding(0, dp(16), 0, dp(4)); addView(knopf) } }
        }
        rahmen(kopf("Einkauf", zurueckPfeil = true), liste(zeilen, "Die Liste ist leer."))
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

    // ----- Aufnahmen

    private fun aufnahmenListe(alle: List<Eintrag>) {
        val zeilen = alle.map { e ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@LiteActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    addView(LinearLayout(this@LiteActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(e.titel, 19f, dick = e.hatInhalt || e.stand != null || e.laeuft).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        val teile = listOfNotNull(e.start?.let { tag(it, true) }, e.dauerText().ifBlank { null }, e.stand)
                        addView(text(teile.joinToString(" · "), 15f).apply { setPadding(0, dp(5), 0, 0) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(symbol(R.drawable.pfeil_rechts, 24))
                    setOnClickListener { oeffnen(Ansicht.AUFNAHME, e.id) }
                }, breit())
                addView(punktlinie())
            }
        }
        rahmen(kopf("Aufnahmen", zurueckPfeil = true), liste(zeilen, "Noch keine Aufnahmen."))
    }

    // ----- Eine Aufnahme: Text wie im Buch auf Seiten verteilt

    private fun aufnahmeSeite(e: Eintrag?) {
        if (e == null) { zurueck(); return }
        val titel = e.start?.let { SimpleDateFormat("EE d.M. · HH:mm", Locale.GERMANY).format(it) } ?: "Aufnahme"
        rahmen(kopf(titel, zurueckPfeil = true)) { breite, hoehe -> buchSeite(inhaltText(e), breite, hoehe) }
    }

    private fun inhaltText(e: Eintrag): CharSequence {
        val b = SpannableStringBuilder()
        val messen = TextPaint().apply { typeface = normal; textSize = sp(18f) }
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
        absatz(e.titel, 23f, dick = true, abstandVorher = false)
        absatz(listOfNotNull(e.dauerText().ifBlank { null }, e.stand).joinToString(" · ").ifBlank { " " }, 15f, abstandVorher = false)
        val erg = e.erg ?: return b
        if (!e.hatInhalt) {
            absatz("Der Laptop hat in dieser Aufnahme nichts zum Merken gefunden.", 18f)
            return b
        }
        e.kurz?.let { if (it != e.titel) absatz(it, 18f) }

        val aufgaben = erg.optJSONArray("aufgaben")
        if (aufgaben != null && aufgaben.length() > 0) {
            absatz("Aufgaben", 18f, dick = true)
            for (i in 0 until aufgaben.length()) {
                val a = aufgaben.optJSONObject(i) ?: continue
                if (a.optString("art") == "erledigt") continue
                val start = b.length
                b.append("  ")
                getDrawable(R.drawable.kasten)?.let { d ->
                    val g = sp(17f).toInt()
                    d.setBounds(0, 0, g, g)
                    b.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val frist = a.optString("frist").takeIf { it.length >= 10 }?.let {
                    try {
                        val d = SimpleDateFormat(if (it.length >= 16) "yyyy-MM-dd'T'HH:mm" else "yyyy-MM-dd", Locale.ROOT)
                            .parse(it.take(16))!!
                        " · " + tag(d, mitZeit(d))
                    } catch (_: Exception) { "" }
                } ?: ""
                b.append(a.optString("text") + frist)
                b.setSpan(AbsoluteSizeSpan(sp(18f).toInt()), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(TypefaceSpan(normal), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.append("\n")
                b.setSpan(android.text.style.LeadingMarginSpan.Standard(0, sp(17f).toInt() + messen.measureText(" ").toInt()),
                    start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val einkauf = erg.optJSONArray("einkauf")
        if (einkauf != null && einkauf.length() > 0) {
            absatz("Einkauf", 18f, dick = true)
            absatz((0 until einkauf.length()).mapNotNull { einkauf.optJSONObject(it)?.optString("sache") }.joinToString(", "),
                18f, abstandVorher = false)
        }
        val notizen = erg.optJSONArray("notizen")
        if (notizen != null) for (i in 0 until notizen.length()) {
            val n = notizen.optJSONObject(i) ?: continue
            val inhalt = n.optString("inhalt")
            if (inhalt.isBlank()) continue
            if (notizen.length() > 1 || n.optString("titel") != e.titel) absatz(n.optString("titel"), 19f, dick = true)
            var ersteZeile = true
            val punktEinzug = messen.measureText("•  ").toInt()
            for (z in inhalt.lines()) {
                val zeile = z.trimEnd().replace("**", "")
                when {
                    zeile.isBlank() -> {}
                    zeile.trimStart().startsWith("#") -> absatz(zeile.trimStart('#', ' '), 18f, dick = true)
                    Regex("^\\s*[-*]\\s+").containsMatchIn(zeile) ->
                        absatz(zeile.replace(Regex("^\\s*[-*]\\s+"), "•  "), 18f, abstandVorher = ersteZeile, einzug = punktEinzug)
                    else -> absatz(zeile, 18f, abstandVorher = true)
                }
                ersteZeile = false
            }
        }
        return b
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
        }
    }
}
