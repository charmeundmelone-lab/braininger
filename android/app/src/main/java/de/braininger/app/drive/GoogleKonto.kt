package de.braininger.app.drive

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Nutzer muss die Google-Anmeldung einmal bestätigen (Fenster von Google). */
class AnmeldungNoetig(val pendingIntent: PendingIntent) : Exception("Google-Anmeldung nötig")

/**
 * Zugang zu Google Drive mit der Berechtigung drive.file.
 * Nach der ersten Zustimmung liefert Google bei jedem Aufruf ohne Fenster einen gültigen Zugangsschlüssel.
 */
object GoogleKonto {
    const val BEREICH = "https://www.googleapis.com/auth/drive.file"

    private fun anfrage() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(BEREICH)))
        .build()

    /** Liefert einen Zugangsschlüssel oder wirft [AnmeldungNoetig]. */
    suspend fun zugang(context: Context): String {
        val ergebnis = Identity.getAuthorizationClient(context).authorize(anfrage()).warten()
        return auswerten(ergebnis)
    }

    fun auswerten(ergebnis: AuthorizationResult): String {
        if (ergebnis.hasResolution()) throw AnmeldungNoetig(ergebnis.pendingIntent!!)
        return ergebnis.accessToken ?: error("Google hat keinen Zugangsschlüssel geliefert")
    }

    /** Abgelaufenen Schlüssel verwerfen, damit der nächste Aufruf einen neuen holt. */
    suspend fun schluesselVerwerfen(context: Context, schluessel: String) {
        try {
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(schluessel).build()).warten()
        } catch (_: Exception) {
        }
    }

    fun ergebnisAusIntent(context: Context, daten: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(daten)
}

suspend fun <T> Task<T>.warten(): T = suspendCancellableCoroutine { k ->
    addOnSuccessListener { k.resume(it) }
    addOnFailureListener { k.resumeWithException(it) }
    addOnCanceledListener { k.cancel() }
}
