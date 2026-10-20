package de.braininger.app.erkennung

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Wandelt die AAC-Stücke (teil_001.m4a …) in 16-kHz-Mono-Werte um, wie Whisper sie braucht.
 * Die App nimmt mit 32 kHz auf (StueckRecorder.ABTASTRATE); andere Raten werden ebenfalls umgerechnet.
 */
object AudioDekoder {
    const val ZIELRATE = 16_000

    /** Liefert die Werte eines Stücks (16 kHz, mono, −1…1). */
    fun dekodieren(datei: File): FloatArray {
        val extraktor = MediaExtractor()
        extraktor.setDataSource(datei.absolutePath)
        val spur = (0 until extraktor.trackCount).firstOrNull {
            extraktor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (spur == null) {
            extraktor.release()
            return FloatArray(0)
        }
        extraktor.selectTrack(spur)
        val format = extraktor.getTrackFormat(spur)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var kanaele = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = Wachsend()
        val info = MediaCodec.BufferInfo()
        var eingabeFertig = false
        var fertig = false
        try {
            while (!fertig) {
                if (!eingabeFertig) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val puffer = codec.getInputBuffer(idx)!!
                        val n = extraktor.readSampleData(puffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eingabeFertig = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extraktor.sampleTime, 0)
                            extraktor.advance()
                        }
                    }
                }
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    kanaele = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (idx >= 0) {
                    if (info.size > 0) {
                        val puffer = codec.getOutputBuffer(idx)!!
                        puffer.position(info.offset)
                        puffer.limit(info.offset + info.size)
                        val kurz = puffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = info.size / 2
                        var i = 0
                        while (i + kanaele <= n) {
                            var summe = 0f
                            for (k in 0 until kanaele) summe += kurz.get(i + k)
                            mono.add(summe / kanaele / 32768f)
                            i += kanaele
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fertig = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extraktor.release()
        }
        return umrechnen(mono.alles(), rate)
    }

    /** Auf 16 kHz bringen. 32 kHz (Normalfall) → Tiefpass und jeden zweiten Wert nehmen. */
    fun umrechnen(werte: FloatArray, rate: Int): FloatArray {
        if (rate == ZIELRATE || werte.isEmpty()) return werte
        if (rate == 2 * ZIELRATE) return halbieren(werte)
        // allgemeiner Fall: lineare Umrechnung (für Whisper ausreichend)
        val faktor = rate.toDouble() / ZIELRATE
        val n = (werte.size / faktor).toInt()
        return FloatArray(n) { i ->
            val pos = i * faktor
            val a = pos.toInt().coerceAtMost(werte.size - 1)
            val b = (a + 1).coerceAtMost(werte.size - 1)
            val t = (pos - a).toFloat()
            werte[a] * (1 - t) + werte[b] * t
        }
    }

    // Tiefpass (FIR, 31 Werte, Hann-Fenster, Grenze 7,2 kHz) gegen Spiegelfrequenzen beim Halbieren
    private val fir: FloatArray by lazy {
        val n = 31
        val mitte = n / 2
        val grenze = 7_200.0 / 32_000.0
        val k = DoubleArray(n) { i ->
            val x = (i - mitte).toDouble()
            val sinc = if (x == 0.0) 2 * grenze else Math.sin(2 * Math.PI * grenze * x) / (Math.PI * x)
            sinc * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)))
        }
        val summe = k.sum()
        FloatArray(n) { (k[it] / summe).toFloat() }
    }

    private fun halbieren(werte: FloatArray): FloatArray {
        val mitte = fir.size / 2
        return FloatArray(werte.size / 2) { j ->
            val i = 2 * j
            var s = 0f
            for (t in fir.indices) {
                val q = i + t - mitte
                if (q >= 0 && q < werte.size) s += werte[q] * fir[t]
            }
            s
        }
    }

    /** FloatArray, die mitwächst, ohne für jeden Wert ein Objekt anzulegen. */
    private class Wachsend {
        private var daten = FloatArray(1 shl 20)
        private var n = 0
        fun add(w: Float) {
            if (n == daten.size) daten = daten.copyOf(daten.size * 2)
            daten[n++] = w
        }
        fun alles(): FloatArray = daten.copyOf(n)
    }
}
