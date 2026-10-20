package de.braininger.app

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager

/**
 * Rückmeldung über die Glyph-Lichter auf der Rückseite (Nothing Phone 3a), nur bei Start, Markieren und Ende
 * (Grill 16.10.2026). Grundlage: offizielles Glyph Developer Kit von Nothing (github.com/Nothing-Developer-Programme/
 * Glyph-Developer-Kit), ab Android 16 ohne eigenen Schlüssel. Auf anderen Geräten oder wenn Nothing die Lichter
 * nicht freigibt, passiert einfach nichts.
 */
object GlyphLicht {
    private val haupt = Handler(Looper.getMainLooper())
    private var manager: GlyphManager? = null
    private var bereit = false

    fun verbinden(context: Context) {
        manager?.let { m ->
            // Nothing beendet die Sitzung manchmal ohne Rückmeldung („GlyphService: session is null“ im logcat,
            // gesehen am 20.10.2026 beim Fokus-Timer). Darum bei jedem Start neu öffnen.
            try {
                m.openSession()
                bereit = true
            } catch (_: Exception) {
            }
            return
        }
        try {
            if (!Common.is24111()) return // nur Phone (3a) / (3a) Pro
            val m = GlyphManager.getInstance(context.applicationContext)
            manager = m
            m.init(object : GlyphManager.Callback {
                override fun onServiceConnected(name: ComponentName?) {
                    try {
                        m.register(Glyph.DEVICE_24111)
                        m.openSession()
                        bereit = true
                    } catch (_: Exception) {
                        bereit = false
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    bereit = false
                }
            })
        } catch (_: Throwable) {
            manager = null // kein Nothing-Gerät oder Dienst fehlt
        }
    }

    /** Start: der lange Streifen (C) läuft einmal schnell hoch. */
    fun start() = lauf(hoch = true)

    /** Ende: der Streifen läuft einmal herunter. */
    fun ende() = lauf(hoch = false)

    /** Markieren: kurzes doppeltes Aufblitzen des Streifens A. */
    fun markieren() = sicher { m ->
        val blitz = m.glyphFrameBuilder.buildChannelA().buildPeriod(120).buildCycles(2).buildInterval(100).build()
        m.animate(blitz)
        haupt.postDelayed({ sicher { it.turnOff() } }, 600)
    }

    /** Fokus-Timer: Streifen C zeigt die Restzeit in Prozent und bleibt an. */
    fun restzeit(prozent: Int) = sicher { m ->
        m.displayProgress(m.glyphFrameBuilder.buildChannelC().build(), prozent.coerceIn(0, 100))
    }

    /** Fokus-Timer zu Ende: Streifen C blinkt dreimal ruhig. */
    fun fokusEnde() = sicher { m ->
        val blinken = m.glyphFrameBuilder.buildChannelC().buildPeriod(400).buildCycles(3).buildInterval(300).build()
        m.animate(blinken)
        haupt.postDelayed({ sicher { it.turnOff() } }, 2_600)
    }

    fun aus() = sicher { it.turnOff() }

    private fun lauf(hoch: Boolean) = sicher { m ->
        val streifen = m.glyphFrameBuilder.buildChannelC().build()
        val schritte = (0..10).map { it * 10 }.let { if (hoch) it else it.reversed() }
        schritte.forEachIndexed { i, p ->
            haupt.postDelayed({ sicher { it.displayProgress(streifen, p) } }, i * 35L)
        }
        haupt.postDelayed({ sicher { it.turnOff() } }, schritte.size * 35L + 250L)
    }

    private fun sicher(aktion: (GlyphManager) -> Unit) {
        val m = manager ?: return
        if (!bereit) return
        try {
            aktion(m)
        } catch (_: Throwable) {
        }
    }
}
