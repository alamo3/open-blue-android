package io.github.alamo3.openblue.sound

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import io.github.alamo3.openblue.data.SecureStore

/**
 * Links the engine sound to the car. The car's Bluetooth is registered with Android's companion
 * device manager, which is what lets the app start its sound service from the background when
 * the car connects (Android 12+ blocks that otherwise).
 */
object CarLink {
    /** Asks Android to show its device picker; [launch] receives the picker to open. */
    fun pick(context: Context, launch: (IntentSender) -> Unit, failed: (String) -> Unit) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null) {
            failed("This phone doesn't support linking devices.")
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        @Suppress("DEPRECATION")
        cdm.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)
                override fun onFailure(error: CharSequence?) = failed(error?.toString() ?: "Couldn't find the car.")
            },
            null,
        )
    }

    /** Reads the picked car from the picker's result. Returns its (address, name). */
    @SuppressLint("MissingPermission")
    fun picked(context: Context, data: Intent?): Pair<String, String>? {
        if (Build.VERSION.SDK_INT >= 33) {
            @Suppress("DEPRECATION")
            val info = data?.getParcelableExtra<android.companion.AssociationInfo>(CompanionDeviceManager.EXTRA_ASSOCIATION)
                ?: context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations?.lastOrNull()
            val mac = info?.deviceMacAddress?.toString()?.uppercase() ?: return null
            return mac to (info.displayName?.toString() ?: mac)
        }
        @Suppress("DEPRECATION")
        val device = data?.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE) ?: return null
        val name = runCatching { device.name }.getOrNull()
        return device.address.uppercase() to (name ?: device.address)
    }

    /** Forgets any linked car, so a new one can be picked. */
    fun forget(context: Context) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                cdm.myAssociations.forEach { cdm.disassociate(it.id) }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.forEach { cdm.disassociate(it) }
            }
        }
    }
}

/** Starts the engine sound when the linked car's Bluetooth connects, and stops it when it goes. */
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
