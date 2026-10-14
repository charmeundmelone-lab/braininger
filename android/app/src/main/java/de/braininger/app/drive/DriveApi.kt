package de.braininger.app.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveFehler(val code: Int, text: String) : IOException("Drive antwortet $code: $text")

data class DriveDatei(val id: String, val name: String, val geaendert: String, val groesse: Long)

/**
 * Kleiner Drive-Client (REST v3) ohne Zusatzbibliotheken.
 * Mit drive.file sieht er nur Dateien, die Braininger selbst angelegt hat.
 */
class DriveApi(private val context: Context) {

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val ORDNER_MIME = "application/vnd.google-apps.folder"
    }

    private var schluessel: String? = null

    /** Führt eine Anfrage aus; bei abgelaufenem Schlüssel einmal mit neuem Schlüssel wiederholen. */
    private suspend fun <T> mitZugang(anfrage: (String) -> T): T = withContext(Dispatchers.IO) {
        val s = schluessel ?: GoogleKonto.zugang(context).also { schluessel = it }
        try {
            anfrage(s)
        } catch (e: DriveFehler) {
            if (e.code != 401) throw e
            GoogleKonto.schluesselVerwerfen(context, s)
            val neu = GoogleKonto.zugang(context).also { schluessel = it }
            anfrage(neu)
        }
    }

    private fun verbinden(url: String, methode: String, schluessel: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = methode
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $schluessel")
        }

    private fun antwort(v: HttpURLConnection): ByteArray {
        val code = v.responseCode
        if (code !in 200..299) {
            val text = v.errorStream?.readBytes()?.decodeToString()?.take(500) ?: ""
            v.disconnect()
            throw DriveFehler(code, text)
        }
        return v.inputStream.use { it.readBytes() }.also { v.disconnect() }
    }

    private fun q(text: String) = text.replace("\\", "\\\\").replace("'", "\\'")
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    suspend fun suchen(abfrage: String): List<DriveDatei> = mitZugang { s ->
        val url = "$API?q=${enc(abfrage)}&fields=${enc("files(id,name,modifiedTime,size)")}&pageSize=1000&spaces=drive"
        val j = JSONObject(antwort(verbinden(url, "GET", s)).decodeToString())
        val dateien = j.optJSONArray("files") ?: JSONArray()
        (0 until dateien.length()).map {
            val d = dateien.getJSONObject(it)
            DriveDatei(d.getString("id"), d.getString("name"), d.optString("modifiedTime"), d.optLong("size", 0))
        }
    }

    suspend fun liste(elternId: String): List<DriveDatei> =
        suchen("'${q(elternId)}' in parents and trashed = false")

    suspend fun dateiFinden(name: String, elternId: String): DriveDatei? =
        suchen("name = '${q(name)}' and '${q(elternId)}' in parents and trashed = false").firstOrNull()

    /** Sucht einen Ordner (oder legt ihn an). elternId = null bedeutet „Meine Ablage“. */
    suspend fun ordnerSichern(name: String, elternId: String?): String {
        val eltern = elternId ?: "root"
        suchen("name = '${q(name)}' and mimeType = '$ORDNER_MIME' and '${q(eltern)}' in parents and trashed = false")
            .firstOrNull()?.let { return it.id }
        return mitZugang { s ->
            val meta = JSONObject().put("name", name).put("mimeType", ORDNER_MIME)
                .put("parents", JSONArray().put(eltern))
            val v = verbinden("$API?fields=id", "POST", s).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            v.outputStream.use { it.write(meta.toString().toByteArray()) }
            JSONObject(antwort(v).decodeToString()).getString("id")
        }
    }

    /** Lädt eine neue Datei hoch (bis ca. 5 MB) und liefert ihre ID. */
    suspend fun hochladen(name: String, elternId: String, mime: String, inhalt: ByteArray): String = mitZugang { s ->
        val grenze = "braininger" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(elternId))
        val koerper = ByteArrayOutputStream().apply {
            write("--$grenze\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(meta.toString().toByteArray())
            write("\r\n--$grenze\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(inhalt)
            write("\r\n--$grenze--\r\n".toByteArray())
        }.toByteArray()
        val v = verbinden("$UPLOAD?uploadType=multipart&fields=id", "POST", s).apply {
            doOutput = true
            setFixedLengthStreamingMode(koerper.size)
            setRequestProperty("Content-Type", "multipart/related; boundary=$grenze")
        }
        v.outputStream.use { it.write(koerper) }
        JSONObject(antwort(v).decodeToString()).getString("id")
    }

    /** Ersetzt den Inhalt einer vorhandenen Datei, die ID bleibt gleich. */
    suspend fun inhaltErsetzen(id: String, mime: String, inhalt: ByteArray): Unit = mitZugang { s ->
        // HttpURLConnection kennt kein PATCH, Google akzeptiert den Override-Kopf.
        val v = verbinden("$UPLOAD/${enc(id)}?uploadType=media&fields=id", "POST", s).apply {
            doOutput = true
            setFixedLengthStreamingMode(inhalt.size)
            setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Content-Type", mime)
        }
        v.outputStream.use { it.write(inhalt) }
        antwort(v)
        Unit
    }

    suspend fun herunterladen(id: String): ByteArray = mitZugang { s ->
        antwort(verbinden("$API/${enc(id)}?alt=media", "GET", s))
    }
}
