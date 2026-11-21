package io.github.alamo3.openblue.sound

import android.content.Context
import java.io.File

/**
 * The shared native sound engine (src/main/cpp/soundcore, from virtual-car-sound/soundcore): the
 * layered sounds -- Flat-6, Hypercar, Turbofan, Warp bubble -- with their gearbox, drive effects,
 * limiter stutter, speed-aware mix, cornering lean and output limiter. The Raspberry Pi's player
 * runs the very same engine, so a sound is the same sound wherever it plays.
 *
 * Use it from one thread (the audio thread): [open], then per block [render]; [close] at the end.
 */
class SoundCore private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        private const val TAG = "OpenBlueSoundCore"
        @Volatile private var loaded: Boolean? = null

        /** The engine at this sample rate, or null if the native library can't load. */
        fun open(context: Context, sampleRate: Int): SoundCore? {
            if (!load()) return null
            val dir = runCatching { effectsDir(context) }.getOrNull()
            val h = Native.create(sampleRate, dir?.path ?: "")
            return if (h != 0L) SoundCore(h) else null
        }

        @Synchronized
        private fun load(): Boolean {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("soundcore_jni")
                true
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "the native sound engine failed to load", t)
                false
            }
            loaded = ok
            return ok
        }

        /**
         * The drive effects (assets/effects/ *.wav) as files the engine can open: copied out of
         * the APK once, and again whenever one differs in size (an update changed it).
         */
        private fun effectsDir(context: Context): File {
            val dir = File(context.filesDir, "effects").apply { mkdirs() }
            val assets = context.assets
            for (name in assets.list("effects").orEmpty().filter { it.endsWith(".wav") }) {
                val bytes = assets.open("effects/$name").use { it.readBytes() }
                val f = File(dir, name)
                if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
            }
            return dir
        }

        /** The engine's number for each layered sound (sc::Sound); null for the others. */
        fun soundId(s: EngineSound): Int? = when (s.kind) {
            EngineSound.Kind.FLAT6 -> 0
            EngineSound.Kind.HYPERCAR -> 1
            EngineSound.Kind.JET -> 2
            EngineSound.Kind.WARP -> 3
            else -> null
        }
    }

    /** What the engine's gearbox is doing, after each [render]. */
    var rpm = 0f; private set
    var gear = 0; private set
    var load = 0f; private set
    var limiter = false; private set
    private val ro = FloatArray(4)

    fun setSound(id: Int) = Native.setSound(handle, id)
    fun setVolume(v: Float) = Native.setVolume(handle, v)
    fun setGearshifts(on: Boolean) = Native.setGearshifts(handle, on)
    fun skipChime() = Native.skipChime(handle)

    /**
     * One block: the car's inputs in, [frames] stereo samples out, interleaved into [out].
     * [pedal] null: only the phone's estimate ([accel] stands in). [yawRate] rad/s, + = turning
     * left. [revThrottle] non-null: revving in place (the gearbox set aside).
     */
    fun render(
        pedal: Float?, speedMs: Float, accel: Float, motorRpm: Int, reversing: Boolean,
        yawRate: Float, revThrottle: Float?, out: FloatArray, frames: Int,
    ) {
        Native.render(handle, pedal ?: 0f, pedal != null, speedMs, accel, motorRpm, reversing, yawRate,
            revThrottle ?: -1f, out, frames, ro)
        rpm = ro[0]; gear = ro[1].toInt(); load = ro[2]; limiter = ro[3] != 0f
    }

    override fun close() {
        if (handle != 0L) { Native.destroy(handle); handle = 0L }
    }

    /** The JNI bridge: src/main/cpp/soundcore_jni.cpp. */
    private object Native {
        external fun create(sampleRate: Int, effectsDir: String): Long
        external fun destroy(h: Long)
        external fun setSound(h: Long, sound: Int)
        external fun setVolume(h: Long, v: Float)
        external fun setGearshifts(h: Long, on: Boolean)
        external fun skipChime(h: Long)
        external fun render(
            h: Long, pedal: Float, hasPedal: Boolean, speedMs: Float, accel: Float, motorRpm: Int,
            reversing: Boolean, yawRate: Float, revThrottle: Float, out: FloatArray, frames: Int, readout: FloatArray,
        )
    }
}
