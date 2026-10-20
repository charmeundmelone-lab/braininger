package de.braininger.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Sucht das beste Mikrofon: Lavalier (USB-C oder Klinke) vor Bluetooth-Kopfhörern vor dem eingebauten.
 *
 * Bluetooth-Mikrofone schaltet Android nur für „Gespräche“ ein: Die App muss den Kopfhörer mit
 * setCommunicationDevice als Gesprächsgerät anfordern und den Audiomodus MODE_IN_COMMUNICATION setzen
 * (developer.android.com/reference/android/media/AudioManager#setCommunicationDevice). Solange läuft der
 * Kopfhörer im Telefonie-Modus, Musik pausiert oder klingt schlechter. Nach der Aufnahme wird das zurückgesetzt.
 */
object MikroWahl {
    private val kabel = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val bluetooth = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** Lavalier über Kabel oder USB-C, sonst null. */
    fun bestes(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in kabel }
    }

    /** Mikrofon der Bluetooth-Kopfhörer (erscheint, sobald sie als Gesprächsgerät laufen), sonst null. */
    fun bluetoothEingang(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in bluetooth }
    }

    /** Sind Bluetooth-Kopfhörer verbunden, die ein Mikrofon haben? */
    fun bluetoothDa(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        return am.availableCommunicationDevices.any { it.type in bluetooth }
    }

    /** Fordert die Bluetooth-Kopfhörer als Gesprächsgerät an. true, wenn Android zugestimmt hat. */
    fun bluetoothAn(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val geraet = am.availableCommunicationDevices.firstOrNull { it.type in bluetooth } ?: return false
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = am.setCommunicationDevice(geraet)
            if (!ok) am.mode = AudioManager.MODE_NORMAL
            ok
        } catch (_: Exception) {
            am.mode = AudioManager.MODE_NORMAL
            false
        }
    }

    /** Gesprächsgerät und Audiomodus zurücksetzen (Musik läuft wieder normal). */
    fun bluetoothAus(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        try {
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    fun istBluetooth(geraet: AudioDeviceInfo?) = geraet != null && geraet.type in bluetooth

    /** Externes Mikrofon (Lavalier oder Bluetooth): dann keine Warnung „eingebautes Mikrofon“. */
    fun istExtern(geraet: AudioDeviceInfo?) = geraet != null && (geraet.type in kabel || geraet.type in bluetooth)

    fun name(geraet: AudioDeviceInfo?): String = when {
        geraet == null -> "eingebautes Mikrofon"
        geraet.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "eingebautes Mikrofon"
        else -> geraet.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (geraet.type in bluetooth) "Bluetooth-Kopfhörer" else "externes Mikrofon"
    }
}
