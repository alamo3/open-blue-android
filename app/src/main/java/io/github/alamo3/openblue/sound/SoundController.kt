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
)

/**
 * Runs the engine sound: phone sensors and GPS feed [MotionFusion], which drives [EngineModel],
 * which drives [SoundVoice] on a high-priority audio thread.
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
    @Volatile var gearshifts = false
    /** 0..1 while the rev slider is held; null otherwise. */
    @Volatile var revThrottle: Float? = null
    /**
     * Predicts the car this far ahead (ms), to hide output delay such as Android Auto's.
     * Larger values react sooner but overshoot more when the car changes its mind.
     */
    @Volatile var lookaheadMs = 0f

    // Delay measurement: the audio thread plays a click when asked and notes when.
    @Volatile private var clickRequested = false
    @Volatile private var muted = false
    private val clickTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    @Volatile var readout: SoundReadout? = null
        private set
    val running: Boolean get() = audioThread != null

    private val fusion = MotionFusion().apply { restoreMount(savedMount) }
    private var sinceMountSave = 0
    @Volatile private var fusedAccel = 0f
    @Volatile private var fusedSpeed = 0f
    @Volatile private var lastGpsAt = 0L

    private var audioThread: Thread? = null
    @Volatile private var stopping = false
    private var sensorThread: HandlerThread? = null
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private var hasSensors = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> e.values.copyInto(gravity, endIndex = 3)
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    fusion.onAccel(e.values, gravity, e.timestamp)
                    // Remember the mount every half minute or so, for next drive.
                    if (++sinceMountSave >= 1500) {
                        sinceMountSave = 0
                        fusion.exportMount()?.let(saveMount)
                    }
                    fusedAccel = fusion.accel
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
        }
        if (canUseGps) {
            runCatching {
                context.getSystemService(LocationManager::class.java)
                    ?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, ht.looper)
            }
        }
        stopping = false
        audioThread = Thread({ audioLoop() }, "openblue-engine").apply { start() }
    }

    fun stop() {
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
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBytes, frames * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val model = EngineModel()
        val voice = SoundVoice(rate)
        val buf = FloatArray(frames)
        val dt = frames / rate.toFloat()
        var fade = 0f
        var sinceReadout = 0f
        var prevAccel = 0f
        var jerk = 0f
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
                model.gearshifts = gearshifts && s.isEngine

                // Look ahead: extend the current trend in acceleration by the output delay.
                val a = fusedAccel
                jerk += ((a - prevAccel) / dt - jerk) * minOf(1f, dt / 0.12f)
                prevAccel = a
                val ahead = lookaheadMs / 1000f
                val aPred = (a + jerk * ahead * 0.7f).coerceIn(-9f, 10f)
                val vPred = max(0f, fusedSpeed + a * ahead)

                val st = model.update(dt, vPred, aPred, revThrottle)
                voice.set(st)
                // Fade in at the start and out at the end (or while measuring), so there's no click.
                val target = if (stopping || muted) 0f else 1f
                fade = if (fade < target) minOf(target, fade + dt * 3f) else maxOf(target, fade - dt * 5f)
                voice.volume = volume * fade
                voice.render(buf, frames)
                if (clickRequested) {
                    clickRequested = false
                    // 6 ms of a bright tone: easy for the microphone to pick out.
                    val n = minOf(frames, (rate * 0.006f).toInt())
                    for (i in 0 until n) {
                        val env = kotlin.math.sin(Math.PI * i / n).toFloat()
                        buf[i] = 0.9f * env * kotlin.math.sin(2.0 * Math.PI * 2400.0 * i / rate).toFloat()
                    }
                    clickTimes += System.nanoTime()
                }
                track.write(buf, 0, frames, AudioTrack.WRITE_BLOCKING)

                sinceReadout += dt
                if (sinceReadout > 0.05f) {
                    sinceReadout = 0f
                    readout = SoundReadout(
                        rpm = st.rpm,
                        gear = st.gear,
                        speedKmh = fusedSpeed * 3.6f,
                        accel = fusedAccel,
                        load = st.load,
                        calibrated = fusion.confidence,
                        gpsFix = System.currentTimeMillis() - lastGpsAt < 3_000,
                        hasSensors = hasSensors,
                    )
                }
                if (stopping && fade <= 0f) break
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
