package de.braininger.app.claude

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Jans Claude-API-Schlüssel. Er liegt nur auf dem Handy, verschlüsselt mit einem Schlüssel aus dem
 * Android-Keystore (verlässt die Sicherheitshardware nie). Nie im Projekt, nie im Drive, nie im Protokoll.
 */
object Schluessel {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "braininger_claude"
    private const val VORLIEBEN = "claude"
    private const val FELD = "api_schluessel"

    private fun keystoreSchluessel(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun vorhanden(context: Context): Boolean = lesen(context) != null

    fun speichern(context: Context, schluessel: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreSchluessel())
        val verschluesselt = c.doFinal(schluessel.trim().toByteArray())
        val wert = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(verschluesselt, Base64.NO_WRAP)
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString(FELD, wert).apply()
    }

    fun loeschen(context: Context) =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().remove(FELD).apply()

    fun lesen(context: Context): String? {
        val wert = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getString(FELD, null) ?: return null
        return try {
            val (iv, daten) = wert.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreSchluessel(), GCMParameterSpec(128, iv))
            c.doFinal(daten).decodeToString()
        } catch (_: Exception) {
            null // z. B. nach Neuinstallation: Keystore-Schlüssel weg, gespeicherter Wert unlesbar
        }
    }

    /** Nur die letzten 4 Zeichen, für die Anzeige. */
    fun anzeige(context: Context): String? = lesen(context)?.let { "…" + it.takeLast(4) }
}
