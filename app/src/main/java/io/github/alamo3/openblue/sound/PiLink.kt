package io.github.alamo3.openblue.sound

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** One reading of the car, as the Raspberry Pi reads it over OBD. */
data class CarData(
    val speedKmh: Float,
    /** Accelerator pedal, 0..1. */
    val pedal: Float,
    /** Motor rpm, signed (negative in reverse); 0 if the Pi doesn't know it. */
    val motorRpm: Int,
    /** Battery current in amps, negative while regenerating; 0 if unknown. */
    val currentA: Float,
    val reversing: Boolean,
    /** When it arrived ([SystemClock.elapsedRealtime] ms). */
    val at: Long,
)

/**
 * The car's live data from the virtual-car-sound Raspberry Pi (ble_control.py), over
 * Bluetooth LE: speed, pedal and motor rpm read from the car's OBD port, 25 times a second.
 * No pairing: it scans for the Pi's service, subscribes to its telemetry and reconnects by
 * itself if the link drops. [latest] is null until data arrives; [fresh] says whether it's
 * recent and the Pi itself has live car data.
 */
@SuppressLint("MissingPermission")
class PiLink(private val context: Context) {
    companion object {
        val SERVICE: UUID = UUID.fromString("7a6b0001-3c1e-4b7a-9d55-2f1c0e5b8a01")
        val TELEMETRY: UUID = UUID.fromString("7a6b0005-3c1e-4b7a-9d55-2f1c0e5b8a01")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Older than this, the data no longer counts as live. */
        const val STALE_MS = 500L
    }

    enum class State { OFF, SEARCHING, CONNECTING, CONNECTED }

    @Volatile var state = State.OFF
        private set
    @Volatile var latest: CarData? = null
        private set
    /** Smoothed forward acceleration from the speed readings, m/s² (positive = speeding up). */
    @Volatile var accel = 0f
        private set

    /** Live data from the Pi: recent, and the Pi itself has fresh readings from the car. */
    val fresh: Boolean
        get() {
            val d = latest ?: return false
            return piFresh && SystemClock.elapsedRealtime() - d.at < STALE_MS
        }
    @Volatile private var piFresh = false

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    private var lastSpeed = -1f
    private var lastAt = 0L

    fun start() {
        if (running) return
        running = true
        scan()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        state = State.OFF
        latest = null
    }

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun scan() {
        if (!running || scanning || gatt != null) return
        val a = adapter()
        val scanner = if (a?.isEnabled == true) a.bluetoothLeScanner else null
        if (scanner == null) { retry(); return }
        state = State.SEARCHING
        scanning = true
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }.onFailure { scanning = false; retry(); return }
        // Nothing found for a while: rest, then look again (scans are rate-limited by Android).
        main.postDelayed({ if (scanning) { stopScan(); retry() } }, 12_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun retry() {
        if (running) main.postDelayed({ scan() }, 4_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            if (gatt != null) return
            stopScan()
            state = State.CONNECTING
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)   // 25 Hz data
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                main.post {
                    gatt = null
                    piFresh = false
                    state = if (running) State.SEARCHING else State.OFF
                    retry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(TELEMETRY)
            if (c == null) {               // an older Pi build without telemetry
                runCatching { g.disconnect() }
                return
            }
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            state = State.CONNECTED
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TELEMETRY) parse(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == TELEMETRY) parse(c.value ?: return)
        }
    }

    /** Packet layout: see ble_control.py on the Pi (10 bytes, little-endian, version 1). */
    private fun parse(v: ByteArray) {
        if (v.size < 10 || v[0].toInt() != 1) return
        val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        b.get()                                            // version
        val flags = b.get().toInt()
        val speed = (b.short.toInt() and 0xFFFF) / 100f
        val pedal = (b.get().toInt() and 0xFF) / 200f
        val rpm = b.short.toInt()
        val amps = b.short / 10f
        val now = SystemClock.elapsedRealtime()
        piFresh = flags and 1 != 0
        // acceleration from successive speeds, smoothed over ~0.3 s (OBD speed is coarse)
        if (lastSpeed >= 0f && now > lastAt) {
            val dt = (now - lastAt) / 1000f
            val a = (speed - lastSpeed) / 3.6f / dt
            accel += (a - accel) * minOf(1f, dt / 0.3f)
        }
        lastSpeed = speed
        lastAt = now
        latest = CarData(speed, pedal.coerceIn(0f, 1f), rpm, amps, flags and 2 != 0, now)
    }
}
