package de.braininger.lite

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Abgleich im Hintergrund über den JobScheduler von Android (braucht keine Google-Dienste). */
class AbgleichJob : JobService() {
    companion object {
        private const val REGELMAESSIG = 1
        private const val SOFORT = 2
        private const val BEIM_LADEN = 3

        /**
         * Alle 15 Minuten, wenn Netz da ist (kürzer erlaubt Android nicht), und alle 15 Minuten beim Laden.
         * Das Kabel zum Laptop zählt für Android nicht als Netz, lädt aber: so klappt es auch ohne WLAN.
         * Sofort nach dem Einstecken stößt der Laptop den Abgleich selbst an (Aktion ABGLEICHEN per adb).
         */
        fun planen(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(REGELMAESSIG) == null) js.schedule(
                JobInfo.Builder(REGELMAESSIG, ComponentName(c, AbgleichJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
            if (js.getPendingJob(BEIM_LADEN) == null) js.schedule(
                JobInfo.Builder(BEIM_LADEN, ComponentName(c, AbgleichJob::class.java))
                    .setRequiresCharging(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Gleich versuchen (nach einer Aufnahme). Ohne Netz wartet Android, bis Netz da ist. */
        fun jetzt(c: Context) {
            planen(c)
            Thread { Abgleich.laufen(c) }.start() // per USB-Kabel klappt es auch ohne WLAN
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(SOFORT, ComponentName(c, AbgleichJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(60_000)
                    .build()
            )
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Abgleich.laufen(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

class NeustartEmpfaenger : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = AbgleichJob.planen(c)
}

/**
 * Laptop-Adresse und Schlüssel vom Laptop (laptop/kompakt_empfang.py einrichten, per adb). Im Manifest verlangt der
 * Empfänger WRITE_SECURE_SETTINGS: die hat außer dem System nur die adb-Shell.
 */
class EinrichtungEmpfaenger : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            "de.braininger.lite.EINRICHTEN" -> {
                Lager.einrichten(c, i.getStringExtra("adresse"), i.getStringExtra("schluessel"))
                Lager.protokoll(c, "Eingerichtet: Adresse ${i.getStringExtra("adresse") ?: "-"}")
                AbgleichJob.planen(c)
                resultCode = 1
            }
            // Der Laptop sieht den Kompakt am Kabel (oder ein Ergebnis ist fertig) und stößt den Abgleich an.
            "de.braininger.lite.ABGLEICHEN" -> {
                val ende = goAsync()
                Thread {
                    try {
                        Abgleich.laufen(c)
                    } finally {
                        ende.finish()
                    }
                }.start()
            }
        }
    }
}
