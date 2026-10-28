package io.github.alamo3.openblue.sound

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.alamo3.openblue.data.SecureStore

/**
 * Starts the engine sound when the chosen car's Bluetooth connects, and stops it when it goes.
 * Android lets the app start its sound service from here because battery optimisation is off
 * for it (the "unrestricted battery" setting on the Sound screen).
 */
class CarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SecureStore(context)
        if (!store.autoSound) return
        val car = store.soundCarAddress ?: return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!device.address.equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> runCatching { EngineSoundService.start(context) }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> EngineSoundService.stop(context)
        }
    }
}
