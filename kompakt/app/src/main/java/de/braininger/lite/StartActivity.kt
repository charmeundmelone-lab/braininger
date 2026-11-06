package de.braininger.lite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Unsichtbare Zwischenseite: startet oder stoppt die Aufnahme (auch bei gesperrtem Bildschirm). */
class StartActivity : Activity() {
    companion object {
        const val EXTRA_WAS = "was"
        const val STARTEN = "starten"
        const val STOPPEN = "stoppen"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, LiteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        when (intent.getStringExtra(EXTRA_WAS)) {
            STARTEN -> if (!AufnahmeDienst.laeuft) AufnahmeDienst.starten(this)
            STOPPEN -> AufnahmeDienst.stoppen(this)
        }
        finish()
    }
}
