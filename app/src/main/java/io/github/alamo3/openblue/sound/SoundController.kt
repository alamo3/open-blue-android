package io.github.alamo3.openblue.sound

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlin.math.max

/** What the screen shows while the sound runs. */
data class SoundReadout(
    val rpm: Float,
    val gear: Int,
    val speedKmh: Float,
    val accel: Float,
    val load: Float,
    /** 0..1: how sure we are which way the car points relative to the phone. */
    val calibrated: Float,
    val gpsFix: Boolean,
    val hasSensors: Boolean,
    /** Live data from the car via the Raspberry Pi is driving the sound. */
    val carData: Boolean = false,
    /** The Pi link: off, searching, connecting or connected. */
    val piState: PiLink.State = PiLink.State.OFF,
    /** The Pi's latest live reading, null when there isn't one. */
    val piCar: CarData? = null,
    /** Silent because "Pi only" is on and the Pi has no live data. */
    val waitingForPi: Boolean = false,
    /** The Pi link is on but Bluetooth permission is missing. */
    val piNoPermission: Boolean = false,
    /** FMOD: what it's playing, or why it isn't. */
    val fmodStatus: String? = null,
    /** A test drive is driving the sound: its current stage (null when it isn't). */
    val testDrive: String? = null,
)

/**
 * Runs the engine sound: the car's own data from the Raspberry Pi ([PiLink]: real speed and
 * pedal, read over OBD) when it's there, else phone sensors and GPS through [MotionFusion],
 * drive [EngineModel], which drives [SoundVoice] on a high-priority audio thread.
 */
class SoundController(
    private val context: Context,
    /** The phone mount learned on an earlier drive, and where to keep the new one. */
    savedMount: String? = null,
    private val saveMount: (String) -> Unit = {},
) {
    @Volatile var sound: EngineSound = EngineSound.STARSHIP
    @Volatile var volume = 0.8f
    @Volatile var holdRevs = false
    /** Which imported FMOD sound set plays when [sound] is [EngineSound.FMOD]. */
    @Volatile var fmodSet: String? = null
    /** Follow the car's own data from the Raspberry Pi when it's there. */
    @Volatile var usePi = true
        set(v) {
            field = v
            if (running) { if (v && canUseBluetooth()) pi.start() else if (!v) pi.stop() }
        }
    /** Without the Pi's live data, stay quiet instead of guessing from the phone's sensors. */
    @Volatile var piOnly = false
    @Volatile var gearshifts = false
    /** 0..1 while the rev slider is held; null otherwise. */
    @Volatile var revThrottle: Float? = null
    /** A scripted drive standing in for the car while it runs ([TestDrive]); null otherwise. */
    @Volatile private var testDrive: TestDrive? = null

    /** Runs a scripted drive (0 to 100 km/h and back to a stop) in place of the car's data. */
    fun startTestDrive() { testDrive = TestDrive() }
    fun stopTestDrive() { testDrive = null }
    /**
     * Predicts the car this far ahead (ms), to hide output delay such as Android Auto's.
     * Larger values react sooner but overshoot more when the car changes its mind.
     */
    @Volatile var lookaheadMs = 0f

    /** Where the phone sits: see [MotionFusion.padSign]. */
    var padSign: Int?
        get() = fusion.padSign
        set(v) { fusion.padSign = v }

    // Delay measurement: the audio thread plays a click when asked and notes when.
    @Volatile private var clickRequested = false
    @Volatile private var muted = false
    private val clickTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    private companion object {
        /** How much of the look-ahead the pedal gets (its moves are less predictable than speed). */
        const val PEDAL_LOOKAHEAD = 0.6f
        /** The most the pedal may run ahead of the real one (0..1 of travel). */
        const val PEDAL_LEAD_MAX = 0.35f
    }

    @Volatile var readout: SoundReadout? = null
        private set
    val running: Boolean get() = audioThread != null

    private val fusion = MotionFusion().apply { restoreMount(savedMount) }
    private val pi = PiLink(context)
    private var sinceMountSave = 0
    @Volatile private var fusedAccel = 0f
    @Volatile private var fusedSpeed = 0f
    @Volatile private var lastGpsAt = 0L

    private var audioThread: Thread? = null
    private val focus = FocusKeeper(context)
    @Volatile private var stopping = false
    private var sensorThread: HandlerThread? = null
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var hasSensors = false
    /** Cornering force: the car's speed x the phone gyroscope's yaw rate. */
    private val cornering = Cornering()
    /** Uphill / downhill: the pedal made heavier or lighter by how hard the car is working. */
    private val effort = Effort()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> e.values.copyInto(gravity, endIndex = 3)
                Sensor.TYPE_GYROSCOPE -> cornering.onGyro(e.values, gravity, e.timestamp)
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    fusion.onAccel(e.values, gravity, e.timestamp)
                    // Remember the mount every half minute or so, for next drive.
                    if (++sinceMountSave >= 1500) {
                        sinceMountSave = 0
                        fusion.exportMount()?.let(saveMount)
                    }
                    // Reversing out of a spot isn't "accelerating": keep the sound at idle.
                    fusedAccel = if (fusion.reversing) 0f else fusion.accel
                    fusedSpeed = fusion.speed
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (!l.hasSpeed()) return
            fusion.onGps(l.speed, l.elapsedRealtimeNanos / 1_000_000)
            fusedSpeed = fusion.speed
            lastGpsAt = System.currentTimeMillis()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Starts listening and playing. [canUseGps] when location permission was granted. */
    @SuppressLint("MissingPermission")
    fun start(canUseGps: Boolean) {
        if (running) return
        val ht = HandlerThread("openblue-motion").apply { start() }
        sensorThread = ht
        val handler = Handler(ht.looper)
        val sm = context.getSystemService(SensorManager::class.java)
        val lin = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        hasSensors = lin != null && grav != null
        if (hasSensors) {
            sm!!.registerListener(sensorListener, grav, SensorManager.SENSOR_DELAY_GAME, handler)
            sm.registerListener(sensorListener, lin, SensorManager.SENSOR_DELAY_GAME, handler)
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        }
        if (canUseGps) {
            runCatching {
                context.getSystemService(LocationManager::class.java)
                    ?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, ht.looper)
            }
        }
        if (usePi && canUseBluetooth()) pi.start()
        focus.start()
        // FMOD's Android layer sets itself up here, on the main thread, before the audio thread uses it
        if (sound.kind == EngineSound.Kind.FMOD || fmodSet != null) FmodNative.ensure(context)
        stopping = false
        audioThread = Thread({ audioLoop() }, "openblue-engine").apply { start() }
    }

    private fun canUseBluetooth(): Boolean {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    fun stop() {
        pi.stop()
        focus.stop()
        val t = audioThread ?: return
        fusion.exportMount()?.let(saveMount)
        stopping = true
        runCatching { t.join(600) }
        audioThread = null
        context.getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(locationListener) }
        sensorThread?.quitSafely()
        sensorThread = null
        readout = null
    }

    /**
     * Measures how long sound takes to come out of whatever is playing it (phone, Bluetooth,
     * Android Auto): plays clicks and listens for them with the microphone. Returns the median
     * delay in ms, or null if the clicks couldn't be heard. Blocks for about 4 seconds; needs the
     * sound running and microphone permission.
     */
    @SuppressLint("MissingPermission")
    fun measureDelayMs(): Int? {
        if (!running) return null
        val rate = 48000
        val minBuf = android.media.AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = runCatching {
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5) * 2,
            )
        }.getOrNull() ?: return null
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val total = rate * 4
        val pcm = ShortArray(total)
        clickTimes.clear()
        muted = true
        var read = 0
        val ts = android.media.AudioTimestamp()
        var haveTs = false
        try {
            rec.startRecording()
            val clickFrames = listOf(rate * 7 / 10, rate * 17 / 10, rate * 27 / 10)
            var nextClick = 0
            while (read < total) {
                val n = rec.read(pcm, read, minOf(960, total - read))
                if (n <= 0) break
                read += n
                if (nextClick < clickFrames.size && read >= clickFrames[nextClick]) {
                    clickRequested = true
                    nextClick++
                }
                if (!haveTs && read > rate / 2) {
                    haveTs = rec.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == android.media.AudioRecord.SUCCESS
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            muted = false
        }
        if (!haveTs || read < total / 2) return null
        val clicks = clickTimes.toList()
        return DelayDetector.median(pcm, read, rate, ts.framePosition, ts.nanoTime, clicks)
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48000
        val frames = 256
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .apply {
                        // keep it out of other apps' playback capture (an EQ app replaying all
                        // media would play the engine twice, once late)
                        if (android.os.Build.VERSION.SDK_INT >= 29) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
                    }
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBytes, frames * 2 * 4 * 2))   // two stereo float blocks: as little delay as is safe
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val model = EngineModel()
        val dct = Dct()
        val fmod = FmodVoice(context)
        val voice = SoundVoice(rate, fmod) { path -> runCatching { context.assets.open(path) }.getOrNull() }
        val buf = FloatArray(frames * 2)                 // stereo, interleaved
        val dt = frames / rate.toFloat()
        var fade = 0f
        var sinceReadout = 0f
        var prevAccel = 0f
        var jerk = 0f
        var lastPedal = -1f
        var lastPedalAt = 0L
        var pedalVel = 0f
        track.play()
        try {
            while (true) {
                val s = sound
                if (voice.sound != s) {
                    voice.sound = s
                    model.idleRpm = s.idleRpm
                    model.redline = s.redline
                }
                model.holdRevs = holdRevs
                // the gearbox is the DCT's (below), for every sound: the model only reads the car
                model.gearshifts = false

                // A test drive, while one runs; else the car's own data when the Pi has it; else
                // the phone's estimate.
                val td = testDrive?.let { t -> if (t.step(dt)) t else { testDrive = null; null } }
                val car = if (td == null && usePi && pi.fresh) pi.latest else null
                val waitingForPi = td == null && usePi && piOnly && car == null
                val speedNow = td?.speed ?: car?.let { it.speedKmh / 3.6f } ?: fusedSpeed
                // Reversing out of a spot isn't "accelerating": keep the sound at idle.
                val pedalNow = td?.pedal ?: car?.let { if (it.reversing) 0f else it.pedal }
                val motorRpm = td?.motorRpm ?: car?.motorRpm ?: 0
                // Pedal velocity, from each new reading the Pi sends (~25 a second).
                if (car != null && pedalNow != null && car.at != lastPedalAt) {
                    val pdt = (car.at - lastPedalAt) / 1000f
                    pedalVel = if (lastPedal >= 0f && pdt in 0.005f..0.2f) (pedalNow - lastPedal) / pdt else 0f
                    lastPedal = pedalNow
                    lastPedalAt = car.at
                } else if (car == null) {
                    lastPedal = -1f
                    pedalVel = 0f
                }
                // Look ahead on the pedal too: a press takes 0.1-0.3 s of travel, so carrying it on
                // lets the sound get there sooner. Presses only (a lift is never anticipated), and
                // capped, so a quick dab to part throttle can't sound like flooring it. Unlike the
                // speed, this works from a standstill: that's when a launch most needs it.
                val pedal = pedalNow?.let { p ->
                    if (pedalVel > 0.5f) {
                        val lead = (pedalVel * lookaheadMs / 1000f * PEDAL_LOOKAHEAD).coerceAtMost(PEDAL_LEAD_MAX)
                        minOf(1f, p + lead)
                    } else p
                }
                // Look ahead: extend the current trend in acceleration by the output delay.
                val a = td?.accel ?: if (car != null) (if (car.reversing) 0f else pi.accel) else fusedAccel
                jerk += ((a - prevAccel) / dt - jerk) * minOf(1f, dt / 0.12f)
                prevAccel = a
                // Not at walking pace, where the jolt of setting off would be stretched into a spike,
                // and never more than a modest nudge.
                val ahead = if (speedNow < 2.5f || td != null) 0f else lookaheadMs / 1000f
                val aPred = (a + (jerk * ahead * 0.7f).coerceIn(-0.8f, 0.8f)).coerceIn(-9f, 10f)
                val vPred = max(0f, speedNow + a * ahead)

                // the pedal as the gearbox and the sound feel it: heavier uphill, lighter downhill
                val effortPedal = pedal?.let { effort.step(dt, it, speedNow, a) }
                var st = model.update(dt, vPred, aPred, revThrottle, effortPedal)
                fmod.setId = if (s.kind == EngineSound.Kind.FMOD) fmodSet else null
                // gearshifts on: every sound runs through the virtual dual-clutch gearbox (shift
                // schedule, kickdown, cut and blip, launch slip), over the sound's own rev range
                if (gearshifts && revThrottle == null) {
                    val (idle, red) = (if (s.kind == EngineSound.Kind.FMOD) fmod.revRange() else null) ?: (s.idleRpm to s.redline)
                    dct.idleRpm = idle
                    dct.redline = red
                    st = dct.step(dt, vPred * 3.6f, effortPedal ?: st.load, motorRpm)
                }
                voice.drive(pedal, car?.reversing == true, a, motorRpm)
                // cornering: speed x yaw rate (the test drive's own corners while it runs)
                voice.corner(cornering.lateralAccel(speedNow, td?.yawRate ?: cornering.yawRate))
                voice.set(st)
                // Fade in at the start and out at the end (or while measuring), so there's no click.
                val target = if (stopping || muted || waitingForPi) 0f else 1f
                fade = if (fade < target) minOf(target, fade + dt * 3f) else maxOf(target, fade - dt * 5f)
                voice.volume = volume * fade
                voice.renderStereo(buf, frames)
                if (clickRequested) {
                    clickRequested = false
                    // 6 ms of a bright tone: easy for the microphone to pick out.
                    val n = minOf(frames, (rate * 0.006f).toInt())
                    for (i in 0 until n) {
                        val env = kotlin.math.sin(Math.PI * i / n).toFloat()
                        val v = 0.9f * env * kotlin.math.sin(2.0 * Math.PI * 2400.0 * i / rate).toFloat()
                        buf[2 * i] = v
                        buf[2 * i + 1] = v
                    }
                    clickTimes += System.nanoTime()
                }
                track.write(buf, 0, frames * 2, AudioTrack.WRITE_BLOCKING)

                sinceReadout += dt
                if (sinceReadout > 0.05f) {
                    sinceReadout = 0f
                    readout = SoundReadout(
                        rpm = st.rpm,
                        gear = st.gear,
                        speedKmh = speedNow * 3.6f,
                        accel = a,
                        load = st.load,
                        calibrated = fusion.confidence,
                        gpsFix = System.currentTimeMillis() - lastGpsAt < 3_000,
                        hasSensors = hasSensors,
                        carData = car != null,
                        piState = pi.state,
                        piCar = car,
                        waitingForPi = waitingForPi,
                        piNoPermission = usePi && pi.state == PiLink.State.OFF && !canUseBluetooth(),
                        fmodStatus = if (s.kind == EngineSound.Kind.FMOD) (fmod.status ?: if (fmodSet == null) "No FMOD sound chosen" else "Starting FMOD…") else null,
                        testDrive = td?.stage?.label,
                    )
                }
                if (stopping && fade <= 0f) break
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { fmod.stop() }
        }
    }
}
