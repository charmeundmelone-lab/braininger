package de.braininger.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Sucht das beste Mikrofon: externes (Lavalier über USB-C oder Klinke) vor dem eingebauten. */
object MikroWahl {
    private val extern = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    fun bestes(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in extern }
    }

    fun istExtern(geraet: AudioDeviceInfo?) = geraet != null && geraet.type in extern

    fun name(geraet: AudioDeviceInfo?): String = when {
        geraet == null -> "eingebautes Mikrofon"
        geraet.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "eingebautes Mikrofon"
        else -> geraet.productName?.toString()?.takeIf { it.isNotBlank() } ?: "externes Mikrofon"
    }
}
