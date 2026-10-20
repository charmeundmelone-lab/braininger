package de.braininger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.braininger.app.claude.Schluessel
import de.braininger.app.drive.Hinweise
import de.braininger.app.erkennung.Modelle

/**
 * Nimmt den Claude-API-Schlüssel vom Laptop entgegen (laptop/werkzeuge/api_schluessel.py handy, per adb), damit Jan
 * ihn nicht am Handy eintippen muss. Im Manifest verlangt der Empfänger die Berechtigung WRITE_SECURE_SETTINGS:
 * Die hat außer dem System nur die adb-Shell. Andere Apps können also keinen fremden Schlüssel unterschieben.
 */
class SchluesselEmpfaenger : BroadcastReceiver() {
    companion object {
        const val AKTION = "de.braininger.SCHLUESSEL_SETZEN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AKTION) return
        val wert = intent.getStringExtra("schluessel")?.trim()
        if (wert == null || !wert.startsWith("sk-ant-") || wert.length < 30) {
            resultCode = 0
            return
        }
        Schluessel.speichern(context, wert)
        Modelle.laden(context) // Spracherkennung gleich laden (nur WLAN)
        Hinweise.zeigen(context, 23, "Claude-Schlüssel übernommen",
            "Neue Aufnahmen wertet ab jetzt das Handy selbst aus.")
        resultCode = 1
    }
}
