package de.braininger.app

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Nimmt lückenlos auf und speichert in Stücken von [stueckDauerUs] (Standard 5 Minuten).
 *
 * Mikrofon → AAC-Encoder → MP4-Datei. Bei jedem Stückwechsel wird nur die Datei gewechselt,
 * der Encoder läuft weiter, deshalb fehlt zwischen zwei Stücken kein einziges Sample.
 * Stürzt die App ab, ist höchstens das gerade offene Stück verloren.
 */
class StueckRecorder(
    private val ordner: File,
    private val geraet: AudioDeviceInfo?,
    /** MIC (Lavalier, eingebaut) oder VOICE_COMMUNICATION (Bluetooth-Kopfhörer im Gesprächsmodus). */
    private val quelle: Int = MediaRecorder.AudioSource.MIC,
    private val stueckDauerUs: Long = 5L * 60 * 1_000_000,
    private val beiStueckFertig: (File) -> Unit,
    private val beiFehler: (String) -> Unit,
) {
    companion object {
        const val ABTASTRATE = 32_000
        const val BITRATE = 64_000
        private const val TAG = "StueckRecorder"
    }

    @Volatile private var laeuft = false
    private var faden: Thread? = null
    private var aufnahme: AudioRecord? = null

    var stueckNummer = 0
        private set

    fun start() {
        laeuft = true
        faden = Thread({ schleife() }, "braininger-aufnahme").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Beendet die Aufnahme und schließt das letzte Stück sauber ab. */
    fun stopp() {
        laeuft = false
        faden?.join(10_000)
        faden = null
    }

    /** Wechselt während der Aufnahme das Mikrofon (z. B. wenn das Lavalier eingesteckt wird). */
    fun bevorzugtesGeraet(neu: AudioDeviceInfo?) {
        aufnahme?.setPreferredDevice(neu)
    }

    fun aktuellesGeraet(): AudioDeviceInfo? = aufnahme?.routedDevice

    @SuppressLint("MissingPermission")
    private fun schleife() {
        val minPuffer = AudioRecord.getMinBufferSize(
            ABTASTRATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                quelle, ABTASTRATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minPuffer * 4, ABTASTRATE) // mindestens eine halbe Sekunde Puffer
            )
        } catch (e: Exception) {
            beiFehler("Mikrofon lässt sich nicht öffnen: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            beiFehler("Mikrofon ist belegt oder nicht verfügbar.")
            return
        }
        if (geraet != null) rec.setPreferredDevice(geraet)
        aufnahme = rec

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ABTASTRATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        var muxer: MediaMuxer? = null
        var spur = -1
        var stueckDatei: File? = null
        var stueckStartUs = 0L
        var ausgabeFormat: MediaFormat? = null
        var samplesGesamt = 0L
        var eingabeBeendet = false
        val info = MediaCodec.BufferInfo()

        fun stueckSchliessen() {
            val m = muxer ?: return
            try {
                m.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stück ließ sich nicht abschließen", e)
            }
            m.release()
            muxer = null
            stueckDatei?.let(beiStueckFertig)
            stueckDatei = null
        }

        fun stueckOeffnen(startUs: Long) {
            stueckNummer++
            val datei = File(ordner, String.format(Locale.ROOT, "teil_%03d.m4a", stueckNummer))
            val m = MediaMuxer(datei.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            spur = m.addTrack(ausgabeFormat!!)
            m.start()
            muxer = m
            stueckDatei = datei
            stueckStartUs = startUs
        }

        try {
            rec.startRecording()
            var ausgabeBeendet = false
            while (!ausgabeBeendet) {
                // 1. Rohdaten vom Mikrofon in den Encoder
                if (!eingabeBeendet) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val zeitUs = samplesGesamt * 1_000_000L / ABTASTRATE
                        if (!laeuft) {
                            codec.queueInputBuffer(idx, 0, 0, zeitUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eingabeBeendet = true
                        } else {
                            val puffer = codec.getInputBuffer(idx)!!
                            val gelesen = rec.read(puffer, minOf(puffer.capacity(), 4096), AudioRecord.READ_BLOCKING)
                            if (gelesen < 0) {
                                codec.queueInputBuffer(idx, 0, 0, zeitUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eingabeBeendet = true
                                beiFehler("Das Mikrofon wurde von einer anderen App oder einem Anruf übernommen (Fehler $gelesen).")
                            } else {
                                codec.queueInputBuffer(idx, 0, gelesen, zeitUs, 0)
                                samplesGesamt += gelesen / 2
                            }
                        }
                    }
                }

                // 2. Fertige AAC-Pakete aus dem Encoder in die Datei
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ausgabeFormat = codec.outputFormat
                        continue
                    }
                    if (idx < 0) continue
                    val daten = codec.getOutputBuffer(idx)!!
                    val istKonfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!istKonfig && info.size > 0 && ausgabeFormat != null) {
                        if (muxer == null) {
                            stueckOeffnen(info.presentationTimeUs)
                        } else if (info.presentationTimeUs - stueckStartUs >= stueckDauerUs) {
                            stueckSchliessen()
                            stueckOeffnen(info.presentationTimeUs)
                        }
                        val verschoben = MediaCodec.BufferInfo().apply {
                            set(info.offset, info.size, info.presentationTimeUs - stueckStartUs, info.flags)
                        }
                        muxer!!.writeSampleData(spur, daten, verschoben)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        ausgabeBeendet = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aufnahme abgebrochen", e)
            beiFehler("Aufnahme abgebrochen: ${e.message}")
        } finally {
            stueckSchliessen()
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            aufnahme = null
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }
}
