package de.braininger.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Unsichtbare Zwischenseite für die Widget-Knöpfe.
 * Startet oder stoppt die Aufnahme und fragt beim Gespräch nach der Zustimmung der anderen.
 */
class StartActivity : Activity() {

    companion object {
        const val EXTRA_WAS = "was"
        const val WAS_GEDANKE = "gedanke"
        const val WAS_GESPRAECH = "gespraech"
        const val WAS_STOPP = "stopp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true) // Essential Key bei gesperrtem Handy: Seite muss sichtbar sein, sonst kein Mikrofon
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Erst die Erlaubnis in der App einholen.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        val laeuft = AufnahmeStatus.zustand.value.laeuft
        when (intent.getStringExtra(EXTRA_WAS)) {
            WAS_STOPP -> {
                AufnahmeDienst.stoppen(this)
                BrainingerWidget.aktualisieren(this)
                finish()
            }
            WAS_GEDANKE -> {
                if (!laeuft) AufnahmeDienst.starten(this, Modus.GEDANKE)
                finish()
            }
            WAS_GESPRAECH -> {
                if (laeuft) { finish(); return }
                AlertDialog.Builder(this)
                    .setTitle(R.string.zustimmung_titel)
                    .setMessage(R.string.zustimmung_text)
                    .setPositiveButton(R.string.zustimmung_ja) { _, _ ->
                        AufnahmeDienst.starten(this, Modus.GESPRAECH)
                        finish()
                    }
                    .setNegativeButton(R.string.zustimmung_nein) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
            else -> finish()
        }
    }
}
