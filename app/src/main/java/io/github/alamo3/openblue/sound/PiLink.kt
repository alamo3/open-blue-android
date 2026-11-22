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
 *
 * One link is shared ([shared]) by everyone who needs the Pi -- the sound, the Pi remote --
 * each holding it with [acquire] and letting go with [release]; it runs while anyone holds it.
 * Through it the app also reads the Pi's [status] and sends it commands ([send]): the remote
 * for the Pi's own sound.
 */
@SuppressLint("MissingPermission")
class PiLink(private val context: Context) {
    companion object {
        @Volatile private var instance: PiLink? = null
        /** The app's one link to the Pi. */
        fun shared(context: Context): PiLink =
            instance ?: synchronized(this) { instance ?: PiLink(context.applicationContext).also { instance = it } }

        val SERVICE: UUID = UUID.fromString("7a6b0001-3c1e-4b7a-9d55-2f1c0e5b8a01")
        val COMMAND: UUID = UUID.fromString("7a6b0002-3c1e-4b7a-9d55-2f1c0e5b8a01")
        val STATUS: UUID = UUID.fromString("7a6b0003-3c1e-4b7a-9d55-2f1c0e5b8a01")
        val TELEMETRY: UUID = UUID.fromString("7a6b0005-3c1e-4b7a-9d55-2f1c0e5b8a01")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** The MTU asked for: the status JSON is a couple of hundred bytes. */
        private const val MTU = 517
        /** Older than this, the data no longer counts as live. */
        const val STALE_MS = 500L
        /** Through a gap this long, the sound keeps following the last reading. */
        const val HOLD_MS = 1500L
        /** Between readings, the speed carries on at the current acceleration for up to this long. */
        const val CARRY_MAX_SEC = 0.25f
        /** A gap between readings longer than this counts as one (they come every ~35 ms). */
        const val GAP_MS = 250L
        /**
         * The car reports speed in whole km/h, which would make the pitch step; it's smoothed
         * over this long, and the lag that adds is taken back out using the acceleration.
         */
        private const val SPEED_SMOOTH_SEC = 0.15f
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

    /**
     * Good enough to keep following: fresh, or was within the last [HOLD_MS]. A hiccup in the
     * readings (an OBD retry, a busy Bluetooth link) holds the last reading instead of the sound
     * dropping to the phone's guess -- or going quiet -- for a moment.
     */
    val usable: Boolean
        get() = latest != null && SystemClock.elapsedRealtime() - lastFreshAt < HOLD_MS
    @Volatile private var lastFreshAt = -HOLD_MS

    /** The speed now, km/h: the latest reading carried on at the current acceleration (between readings). */
    fun speedNow(): Float {
        val d = latest ?: return 0f
        if (d.speedKmh <= 0f) return 0f
        val age = ((SystemClock.elapsedRealtime() - d.at) / 1000f).coerceIn(0f, CARRY_MAX_SEC)
        return maxOf(0f, d.speedKmh + accel * 3.6f * age)
    }

    /** Gaps in the car's data while connected: how many (over [GAP_MS]) and the longest, ms. */
    @Volatile var gaps = 0
        private set
    @Volatile var longestGapMs = 0L
        private set

    /** The Pi's latest status; null until it sends one (or with a Pi too old to). */
    @Volatile var status: PiStatus? = null
        private set

    private val holders = mutableSetOf<Any>()

    /** Starts the link for [holder] (if it isn't running already). */
    @Synchronized fun acquire(holder: Any) {
        holders += holder
        if (holders.size == 1) main.post { start() }
    }

    /** Lets go for [holder]; the link stops when nobody holds it. */
    @Synchronized fun release(holder: Any) {
        if (holders.remove(holder) && holders.isEmpty()) main.post { if (holders.isEmpty()) stop() }
    }

    /** Sends the Pi a command (ble_control.py: "voice Hypercar", "volume 0.7", "testdrive start"...). */
    fun send(command: String) {
        main.post {
            enqueue { g ->
                val c = g.getService(SERVICE)?.getCharacteristic(COMMAND) ?: return@enqueue false
                val bytes = command.toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    c.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
        }
    }

    // GATT allows one operation at a time: they queue here (on the main thread), and each
    // completion callback starts the next
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var opBusy = false

    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        if (ops.size > 20) ops.removeFirst()
        ops.addLast(op)
        nextOp()
    }

    private fun nextOp() {
        while (!opBusy) {
            val g = gatt ?: run { ops.clear(); return }
            val op = ops.removeFirstOrNull() ?: return
            opBusy = runCatching { op(g) }.getOrDefault(false)
            if (opBusy) main.postDelayed(opTimeout, 2_000)   // a lost callback mustn't stall the queue
        }
    }

    private fun opDone() {
        main.post {
            main.removeCallbacks(opTimeout)
            opBusy = false
            nextOp()
        }
    }

    private val opTimeout = Runnable { opBusy = false; nextOp() }

    private fun subscribe(uuid: UUID) = enqueue { g ->
        val c = g.getService(SERVICE)?.getCharacteristic(uuid) ?: return@enqueue false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return@enqueue false
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    private var lastSpeed = -1f
    private var lastAt = 0L
    private var smoothSpeed = -1f

    private fun start() {
        if (running) return
        running = true
        gaps = 0
        longestGapMs = 0
        scan()
    }

    private fun stop() {
        running = false
        ops.clear()
        opBusy = false
        status = null
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
        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)   // 25 Hz data
                // room for the status (a couple of hundred bytes) in one notification
                if (!g.requestMtu(MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                main.post {
                    gatt = null
                    piFresh = false
                    status = null
                    ops.clear()
                    opBusy = false
                    state = if (running) State.SEARCHING else State.OFF
                    retry()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, gattStatus: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            val service = g.getService(SERVICE)
            if (service?.getCharacteristic(TELEMETRY) == null) {   // an older Pi build without telemetry
                runCatching { g.disconnect() }
                return
            }
            main.post {
                subscribe(TELEMETRY)
                if (service.getCharacteristic(STATUS) != null) {
                    subscribe(STATUS)
                    send("status")                               // the status straight away
                }
                state = State.CONNECTED
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, gattStatus: Int) = opDone()
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, gattStatus: Int) = opDone()

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                TELEMETRY -> parse(value)
                STATUS -> parseStatus(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            when (c.uuid) {
                TELEMETRY -> parse(v)
                STATUS -> parseStatus(v)
            }
        }
    }

    /** The status JSON (ble_control.py's _status). */
    private fun parseStatus(v: ByteArray) {
        status = parsePiStatus(String(v, Charsets.UTF_8)) ?: return
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
        if (piFresh) {
            val gap = now - lastFreshAt
            if (lastFreshAt > 0 && gap > GAP_MS && gap < 60_000) {
                gaps++
                longestGapMs = maxOf(longestGapMs, gap)
            }
            lastFreshAt = now
        }
        // acceleration from successive speeds, smoothed over ~0.3 s (OBD speed is coarse);
        // the speed itself smoothed, plus what the smoothing lags behind at this acceleration
        if (lastSpeed >= 0f && now > lastAt) {
            val dt = (now - lastAt) / 1000f
            val a = (speed - lastSpeed) / 3.6f / dt
            accel += (a - accel) * minOf(1f, dt / 0.3f)
            smoothSpeed += (speed - smoothSpeed) * minOf(1f, dt / SPEED_SMOOTH_SEC)
        } else {
            smoothSpeed = speed
        }
        lastSpeed = speed
        lastAt = now
        val shown = if (speed < 0.5f) 0f else maxOf(0f, smoothSpeed + accel * 3.6f * SPEED_SMOOTH_SEC)
        latest = CarData(shown, pedal.coerceIn(0f, 1f), rpm, amps, flags and 2 != 0, now)
    }
}

