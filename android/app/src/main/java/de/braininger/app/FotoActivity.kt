package de.braininger.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import java.io.File
import kotlin.concurrent.thread

/**
 * Foto während einer Aufnahme (Knopf „FOTO“ in der Aufnahme-Benachrichtigung). Erscheint auch über dem
 * Sperrbildschirm; dort sieht man nur die Kamera, sonst nichts vom Handy. Das Foto landet verkleinert im
 * Aufnahme-Ordner, der Laptop gibt es Claude zum Mitlesen.
 */
class FotoActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ORDNER = "ordner"
        private const val EXTRA_START = "start"
        private const val MAX_KANTE = 1600

        fun intent(context: Context, ordner: File, start: Long): Intent =
            Intent(context, FotoActivity::class.java)
                .putExtra(EXTRA_ORDNER, ordner.absolutePath)
                .putExtra(EXTRA_START, start)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val kameraErlaubt = mutableStateOf(false)
    private val meldung = mutableStateOf<String?>(null)
    private var ausloeser: ImageCapture? = null
    private lateinit var ordner: File
    private var start = 0L

    private val erlaubnis = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) kameraErlaubt.value = true else meldung.value = "Ohne Kamera-Erlaubnis geht kein Foto."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        ordner = File(intent.getStringExtra(EXTRA_ORDNER) ?: "")
        start = intent.getLongExtra(EXTRA_START, 0L)
        if (!ordner.isDirectory) {
            finish()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            kameraErlaubt.value = true
        } else {
            erlaubnis.launch(Manifest.permission.CAMERA)
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Schwarz)) {
                if (kameraErlaubt.value) {
                    AndroidView(factory = { ctx -> PreviewView(ctx).also { kameraVerbinden(it) } }, modifier = Modifier.fillMaxSize())
                }
                TextButton(onClick = { finish() }, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
                    Text("‹ ZURÜCK", color = Weiss, fontWeight = FontWeight.Bold)
                }
                meldung.value?.let {
                    Text(it, color = Weiss, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 40.dp)
                        .size(84.dp)
                        .border(4.dp, Weiss, CircleShape)
                        .padding(8.dp)
                        .background(Weiss, CircleShape)
                        .clickable(enabled = kameraErlaubt.value) { ausloesen() }
                )
            }
        }
    }

    private fun kameraVerbinden(vorschauFlaeche: PreviewView) {
        val anbieter = ProcessCameraProvider.getInstance(this)
        anbieter.addListener({
            try {
                val p = anbieter.get()
                val vorschau = Preview.Builder().build().also { it.setSurfaceProvider(vorschauFlaeche.surfaceProvider) }
                val bild = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vorschau, bild)
                ausloeser = bild
            } catch (e: Exception) {
                meldung.value = "Kamera lässt sich nicht öffnen: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ausloesen() {
        val bild = ausloeser ?: return
        ausloeser = null // kein Doppel-Tipp
        val sek = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        val roh = File(cacheDir, "foto_roh.jpg")
        meldung.value = "Speichert …"
        bild.takePicture(
            ImageCapture.OutputFileOptions.Builder(roh).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    thread {
                        val name = try {
                            ablegen(roh, sek)
                        } catch (e: Exception) {
                            null
                        }
                        runOnUiThread {
                            if (name == null) {
                                meldung.value = "Foto ließ sich nicht speichern."
                                ausloeser = bild
                            } else {
                                AufnahmeDienst.fotoMelden(this@FotoActivity, name, sek)
                                rueckmeldung()
                                finish()
                            }
                        }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    meldung.value = "Foto fehlgeschlagen: ${e.message}"
                    ausloeser = bild
                }
            },
        )
    }

    /** Richtig drehen, auf höchstens 1600 Pixel verkleinern und als foto_<Sekunde>.jpg ablegen. */
    private fun ablegen(roh: File, sek: Long): String {
        val masse = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(roh.path, masse)
        var teiler = 1
        while (maxOf(masse.outWidth, masse.outHeight) / (teiler * 2) >= MAX_KANTE) teiler *= 2
        var b = BitmapFactory.decodeFile(roh.path, BitmapFactory.Options().apply { inSampleSize = teiler })
            ?: throw IllegalStateException("Foto nicht lesbar")
        val grad = when (ExifInterface(roh.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val faktor = minOf(1f, MAX_KANTE.toFloat() / maxOf(b.width, b.height))
        if (grad != 0f || faktor < 1f) {
            val m = Matrix().apply { postScale(faktor, faktor); postRotate(grad) }
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        var datei = File(ordner, "foto_%05d.jpg".format(sek))
        var n = 2
        while (datei.exists()) datei = File(ordner, "foto_%05d_%d.jpg".format(sek, n++))
        val tmp = File(ordner, datei.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(datei)
        roh.delete()
        return datei.name
    }

    private fun rueckmeldung() {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
