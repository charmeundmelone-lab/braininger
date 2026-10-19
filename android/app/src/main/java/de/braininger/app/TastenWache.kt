package de.braininger.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Passt auf, dass die Bedienungshilfe für die Essential Key eingeschaltet bleibt. Android schaltet sie manchmal
 * still ab (zum Beispiel nach einem App-Update); am 18.10.2026 ging die Taste deshalb nicht mehr.
 * - Hat die App einmalig per ADB die Erlaubnis WRITE_SECURE_SETTINGS bekommen, schaltet sie die Bedienungshilfe
 *   selbst wieder ein:  adb shell pm grant de.braininger.app android.permission.WRITE_SECURE_SETTINGS
 * - Sonst gibt es eine Benachrichtigung (einmal je Ausfall) und einen Hinweis auf der Startseite.
 * Geprüft wird beim Öffnen der App, nach Neustart und App-Update und alle 15 Minuten im Hintergrund.
 */
object TastenWache {
    private const val ID_HINWEIS = 9
    private const val VORLIEBEN = "tasten_wache"
    private const val GEMELDET = "gemeldet"

    private fun dienst(context: Context) = ComponentName(context, TastenDienst::class.java)

    fun aktiv(context: Context): Boolean {
        val liste = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val d = dienst(context)
        return liste.split(':').any { ComponentName.unflattenFromString(it) == d }
    }

    fun darfSelbstEinschalten(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Hängt den Dienst an die Liste der eingeschalteten Bedienungshilfen an (andere bleiben unberührt). */
    private fun selbstEinschalten(context: Context): Boolean {
        if (!darfSelbstEinschalten(context)) return false
        return try {
            val cr = context.contentResolver
            val alt = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val neu = (alt.split(':').filter { it.isNotBlank() } + dienst(context).flattenToString()).joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, neu)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            protokoll(context, "Bedienungshilfe war aus, selbst wieder eingeschaltet")
            aktiv(context)
        } catch (_: Exception) {
            false
        }
    }

    /** Prüft und repariert. Gibt zurück, ob die Taste danach funktioniert. */
    fun pruefen(context: Context, benachrichtigen: Boolean = true): Boolean {
        val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        if (aktiv(context) || selbstEinschalten(context)) {
            if (v.getBoolean(GEMELDET, false)) {
                v.edit().putBoolean(GEMELDET, false).apply()
                context.getSystemService(NotificationManager::class.java).cancel(ID_HINWEIS)
            }
            return true
        }
        if (benachrichtigen && !v.getBoolean(GEMELDET, false)) {
            protokoll(context, "Bedienungshilfe ist aus, Hinweis gezeigt")
            if (hinweis(context)) v.edit().putBoolean(GEMELDET, true).apply()
        }
        return false
    }

    /** Seite mit dem Schalter der Braininger-Bedienungshilfe (gibt es ab Android 13). */
    fun einstellungen(context: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, dienst(context).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Öffnet den Schalter, notfalls die Liste aller Bedienungshilfen. */
    fun einstellungenOeffnen(context: Context) {
        try {
            context.startActivity(einstellungen(context))
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun hinweis(context: Context): Boolean {
        AufnahmeDienst.kanaeleAnlegen(context)
        val oeffnen = PendingIntent.getActivity(
            context, 9, einstellungen(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "Android hat die Bedienungshilfe für die Taste ausgeschaltet. Antippen und wieder einschalten."
        val n = NotificationCompat.Builder(context, "hinweise")
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle("Essential Key ist aus")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(ID_HINWEIS, n)
            true
        } catch (_: SecurityException) {
            false // Benachrichtigungen nicht erlaubt: bleibt der Hinweis auf der Startseite
        }
    }

    private fun protokoll(context: Context, text: String) = TastenDienst.protokoll(context, text)
}
