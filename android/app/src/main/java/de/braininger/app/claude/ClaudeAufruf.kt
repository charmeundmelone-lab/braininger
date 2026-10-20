package de.braininger.app.claude

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Claude hat abgelehnt, zu viel geschrieben oder etwas Unerwartetes geliefert: nicht automatisch wiederholen. */
class ClaudeFehler(meldung: String) : Exception(meldung)

/** Monatsdeckel erreicht (Kosten.DECKEL_DOLLAR). */
class DeckelErreicht : Exception("Monatsdeckel für Claude erreicht")

/** Vorübergehend (Netz, Überlastung, Limit): später noch einmal versuchen. */
class ClaudeSpaeter(meldung: String) : IOException(meldung)

/**
 * Claude direkt vom Handy, über Jans eigenen API-Schlüssel (offizielles Java-SDK von Anthropic).
 * Modell: Claude Haiku 5.5, das günstigste (Prüfbericht 20.10.2026: ca. 0,1–0,3 Cent je Aufnahme).
 */
object ClaudeAufruf {
    const val MODELL = "claude-haiku-5-5"

    data class Antwort(val daten: JSONObject, val dollar: Double)

    /**
     * system: Blöcke der Anweisungen; der letzte wird zwischengespeichert (Prompt-Caching), deshalb stehen die
     * gleichbleibenden Teile dort. schema: JSON-Schema der Antwort (strukturierte Ausgabe).
     */
    suspend fun frage(
        context: Context,
        system: List<String>,
        inhalt: String,
        schema: JSONObject,
        bilder: List<File> = emptyList(),
        maxTokens: Long = 16_000,
        geschaetzt: Double = 0.02,
    ): Antwort = withContext(Dispatchers.IO) {
        val schluessel = Schluessel.lesen(context) ?: throw ClaudeFehler("Kein Claude-Schlüssel eingetragen.")
        if (!Kosten.frei(context, geschaetzt)) throw DeckelErreicht()

        val client = AnthropicOkHttpClient.builder().apiKey(schluessel).build()
        try {
            val systemBloecke = system.mapIndexed { i, text ->
                TextBlockParam.builder().text(text).apply {
                    if (i == system.lastIndex) cacheControl(CacheControlEphemeral.builder().build())
                }.build()
            }
            val bloecke = bilder.mapNotNull { bildBlock(it) } + ContentBlockParam.ofText(inhalt)
            val schemaWert = JsonOutputFormat.Schema.builder()
                .putAllAdditionalProperties(objektZuMap(schema).mapValues { JsonValue.from(it.value) })
                .build()
            val anfrage = MessageCreateParams.builder()
                .model(MODELL)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(systemBloecke)
                .addUserMessageOfBlockParams(bloecke)
                .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schemaWert).build()).build())
                .build()

            val nachricht = try {
                client.messages().create(anfrage)
            } catch (e: UnauthorizedException) {
                throw ClaudeFehler("Der Claude-Schlüssel wird nicht angenommen. Bitte in den Einstellungen prüfen.")
            } catch (e: PermissionDeniedException) {
                throw ClaudeFehler("Der Claude-Schlüssel darf das nicht (Guthaben oder Rechte in der Claude Console prüfen).")
            } catch (e: RateLimitException) {
                throw ClaudeSpaeter("Claude ist gerade ausgelastet.")
            } catch (e: InternalServerException) {
                throw ClaudeSpaeter("Claude meldet einen Serverfehler.")
            } catch (e: AnthropicIoException) {
                throw ClaudeSpaeter("Keine Verbindung zu Claude.")
            } catch (e: AnthropicServiceException) {
                if (e.statusCode() == 529 || e.statusCode() >= 500) throw ClaudeSpaeter("Claude ist überlastet.")
                throw ClaudeFehler("Claude: Fehler ${e.statusCode()}")
            }

            val u = nachricht.usage()
            val dollar = Kosten.berechnen(
                u.inputTokens(), u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L), u.outputTokens(),
            )
            Kosten.buchen(context, dollar)

            val grund = nachricht.stopReason().orElse(null)
            if (grund == StopReason.REFUSAL) throw ClaudeFehler("Claude hat die Auswertung abgelehnt.")
            if (grund == StopReason.MAX_TOKENS) throw ClaudeFehler("Die Antwort von Claude war zu lang und wurde abgeschnitten.")
            val text = nachricht.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("")
            val daten = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw ClaudeFehler("Claude hat keine lesbare Antwort geliefert.")
            }
            Antwort(daten, dollar)
        } finally {
            client.close()
        }
    }

    /** Foto verkleinert (längste Seite 1568 px, das nutzt Claude ohnehin höchstens) als JPEG-Block. */
    private fun bildBlock(datei: File): ContentBlockParam? {
        if (!datei.exists()) return null
        val grenzen = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(datei.absolutePath, grenzen)
        val laengste = maxOf(grenzen.outWidth, grenzen.outHeight)
        if (laengste <= 0) return null
        var stufe = 1
        while (laengste / (stufe * 2) >= 1568) stufe *= 2
        val roh = BitmapFactory.decodeFile(datei.absolutePath, BitmapFactory.Options().apply { inSampleSize = stufe }) ?: return null
        val faktor = 1568.0 / maxOf(roh.width, roh.height)
        val bild = if (faktor < 1) Bitmap.createScaledBitmap(roh, (roh.width * faktor).toInt(), (roh.height * faktor).toInt(), true) else roh
        val aus = ByteArrayOutputStream()
        bild.compress(Bitmap.CompressFormat.JPEG, 85, aus)
        val daten = Base64.encodeToString(aus.toByteArray(), Base64.NO_WRAP)
        return ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(daten).build())
                .build()
        )
    }

    private fun objektZuMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { wert(o.get(it)) }

    private fun wert(w: Any?): Any? = when (w) {
        is JSONObject -> objektZuMap(w)
        is JSONArray -> (0 until w.length()).map { wert(w.get(it)) }
        JSONObject.NULL -> null
        else -> w
    }
}
