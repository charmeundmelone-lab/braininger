package de.braininger.lite

import android.content.Context
import android.provider.Settings

/**
 * Stellung des Offline+-Schalters. MuditaOS K schreibt sie nach Settings.Global „HWSwitch_lock“ (1 = an),
 * beobachtet am 6.11.2026 per adb. Android 12 kann das Lesen fremder Einstellungen verbieten: dann null,
 * und es bleibt die Prüfung am Ton (AufnahmeDienst).
 */
object Offline {
    fun an(c: Context): Boolean? = try {
        Settings.Global.getString(c.contentResolver, "HWSwitch_lock")?.let { it.trim() == "1" }
    } catch (_: Exception) {
        null
    }
}
