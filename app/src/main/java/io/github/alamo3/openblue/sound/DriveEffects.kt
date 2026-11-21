package io.github.alamo3.openblue.sound

import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * The drive effects of the virtual-car-sound Raspberry Pi (its fx.py), with the same rules
 * for when they play. The sounds are the Pi's own, rendered to assets/effects/ by
 * export_effects.py:
 *
 *  - launch: a fast, deep pedal stab from low speed (EV and cinematic sounds; the engines
 *    make their own launch)
 *  - reverse: the reversing pad and ping, looping while the car is in reverse (the car's
 *    own data from the Pi); the main sound ducks right down meanwhile
 *  - gearshifts: the hydraulic shift sound on every shift of the gearbox ([Dct]), with a
 *    brief duck of the main sound on upshifts for sounds that have no torque cut of their own
 *
 * Pure Kotlin apart from [open]; with no [open] it plays nothing.
 */
class DriveEffects(private val sampleRate: Int, private val open: ((String) -> InputStream?)?) {
    companion object {
        const val LAUNCH_PEDAL_RATE = 2f      // pedal travel per second that counts as a stab
        const val LAUNCH_MIN_PEDAL = 0.65f
        const val LAUNCH_MAX_KMH = 50f
        const val LAUNCH_COOLDOWN_SEC = 4f
        // EV launch: the build plays on hard acceleration from low speed, to completion; the
        // bang at its end only with the pedal (nearly) floored
        const val LAUNCH_BUILD_PEDAL = 0.75f
        const val LAUNCH_BANG_PEDAL = 0.9f
        const val EV_LAUNCH_COOLDOWN_SEC = 5f
        const val SHIFT_DUCK = 0.75f          // a gentle dip under the shift sound, not a cut
        const val SHIFT_DUCK_SEC = 0.18f
        const val REVERSE_DUCK = 0.2f
        const val SHIFT_GAIN = 3f             // the gearshift sound sits over the engine, not under it
        const val LAUNCH_GAIN = 1.8f          // launches (build, bang, boom): over the engine too
        // crossed on purpose: the "down" take sounds right going up, and the other way round
        const val SHIFT_UP = "shift_cin_down"
        const val SHIFT_DOWN = "shift_cin_up"
        private const val ASSET_RATE = 48000
    }

    /** A sound: [pcm] left, [pcmR] right (the same array for a mono file). */
    private class Clip(val pcm: FloatArray, val pcmR: FloatArray) { var pos = -1.0 }

    private var clips: Map<String, Clip>? = null
    private var loadTried = false

    /** Multiply the main sound by this (ducks under shifts and reversing). */
    var duck = 1f
        private set
    var reverse = false
        private set

    private var prevPedal = -1f
    private var pedalRate = 0f
    private var cooldown = 0f
    private var lastPedal = 1f                  // (starts high: no launch from the first block)
    private var buildLeft = 0f
    private var duckT = 0f
    private var reverseLevel = 0f
    private var duckNow = 1f

    private fun load(): Map<String, Clip>? {
        val opener = open ?: return null
        val loaded = listOf("launch_build", "launch_bang", "boom", SHIFT_UP, SHIFT_DOWN, "reverse", "chime").mapNotNull { n ->
            opener("effects/$n.wav")?.use { readWavStereo(it.readBytes()) }?.let { (l, r) -> n to Clip(l, r) }
        }.toMap().takeIf { it.isNotEmpty() }
        loaded?.let { levelMatch(listOfNotNull(it[SHIFT_UP], it[SHIFT_DOWN])) }
        return loaded
    }

    /**
     * Brings [clips] to the same loudness (rms), that of the loudest: the two shift takes were
     * made at different levels, so which one plays for which direction mustn't change how loud
     * a shift is.
     */
    private fun levelMatch(clips: List<Clip>) {
        fun rms(c: Clip): Float {
            var e = 0.0
            for (i in c.pcm.indices) e += (c.pcm[i] * c.pcm[i] + c.pcmR[i] * c.pcmR[i]) / 2.0
            return kotlin.math.sqrt(e / max(1, c.pcm.size)).toFloat()
        }
        val levels = clips.map { rms(it) }
        val target = levels.maxOrNull() ?: return
        for ((c, l) in clips.zip(levels)) {
            if (l <= 1e-6f) continue
            val g = target / l
            for (i in c.pcm.indices) c.pcm[i] *= g
            if (c.pcmR !== c.pcm) for (i in c.pcmR.indices) c.pcmR[i] *= g
        }
    }

    /**
     * Once per audio block. [pedal] 0..1 (the car's own, else the estimated load); [launch] is
     * the kind of launch this sound makes ("ev": build, then a bang if floored; "boom": the
     * sonic boom on a pedal stab) or null for none.
     */
    fun step(dt: Float, speedKmh: Float, pedal: Float, reversing: Boolean, launch: String?) {
        if (!loadTried) { loadTried = true; clips = runCatching { load() }.getOrNull() }
        reverse = reversing

        // launch: a fast, deep pedal stab from low speed
        if (prevPedal >= 0f && dt > 0f) {
            val rate = (pedal - prevPedal) / dt
            pedalRate += (rate - pedalRate) * min(1f, dt / 0.08f)
        }
        prevPedal = pedal
        cooldown = max(0f, cooldown - dt)
        val wasBelow = lastPedal < LAUNCH_BUILD_PEDAL
        lastPedal = pedal
        if (launch == "ev") {
            // hard acceleration from low speed: the build, played to the end...
            if (wasBelow && pedal >= LAUNCH_BUILD_PEDAL && speedKmh < LAUNCH_MAX_KMH && cooldown == 0f && !reverse) {
                cooldown = EV_LAUNCH_COOLDOWN_SEC
                fire("launch_build")
                buildLeft = clips?.get("launch_build")?.let { it.pcm.size / ASSET_RATE.toFloat() } ?: 0f
            }
        } else if (pedalRate > LAUNCH_PEDAL_RATE && pedal > LAUNCH_MIN_PEDAL && speedKmh < LAUNCH_MAX_KMH &&
            cooldown == 0f && !reverse && launch != null
        ) {
            cooldown = LAUNCH_COOLDOWN_SEC
            fire(launch)
        }
        // ...then the bang, only if the pedal is (nearly) floored when the build ends
        if (buildLeft > 0f) {
            buildLeft -= dt
            if (buildLeft <= 0f && pedal >= LAUNCH_BANG_PEDAL && !reverse) fire("launch_bang")
        }

        duckT = max(0f, duckT - dt)
        duck = (if (reverse) REVERSE_DUCK else 1f) * (if (duckT > 0f) SHIFT_DUCK else 1f)
    }

    private fun fire(name: String) {
        clips?.get(name)?.pos = 0.0
    }

    /**
     * The hydraulic gearshift sound, on a shift of the gearbox. [duck] dips the main sound
     * briefly under an upshift, for sounds with no torque cut of their own.
     */
    fun shift(up: Boolean, duck: Boolean = false) {
        fire(if (up) SHIFT_UP else SHIFT_DOWN)
        if (up && duck) duckT = SHIFT_DUCK_SEC
    }

    /** The start-up chime. */
    fun chime() = fire("chime")

    /**
     * Ducks [out] (the main sound, already rendered) and mixes the effects in at [volume].
     */
    /** Ducks [out] / [outR] (the main sound, already rendered) and mixes the effects in at [volume]. */
    fun mix(out: FloatArray, outR: FloatArray, frames: Int, volume: Float) {
        val c = clips
        val step = ASSET_RATE / sampleRate.toDouble()
        val revTarget = if (reverse) 1f else 0f
        val k = 1f / (0.15f * sampleRate)          // ~150 ms fades for the duck and the reverse loop
        for (n in 0 until frames) {
            duckNow += (duck - duckNow) * min(1f, k * 2.5f)     // ~60 ms: eased in and out
            reverseLevel += (revTarget - reverseLevel) * k * 3f
            var x = out[n] * duckNow
            var xr = outR[n] * duckNow
            if (c != null) {
                for ((name, clip) in c) {
                    if (name == "reverse") {
                        if (reverseLevel < 1e-4f) continue
                        if (clip.pos < 0) clip.pos = 0.0
                        val g = reverseLevel * volume
                        x += sample(clip.pcm, clip.pos) * g
                        xr += sample(clip.pcmR, clip.pos) * g
                        clip.pos = (clip.pos + step) % clip.pcm.size
                    } else if (clip.pos >= 0) {
                        val g = volume * when (name) {
                            SHIFT_UP, SHIFT_DOWN -> SHIFT_GAIN
                            "launch_build", "launch_bang", "boom" -> LAUNCH_GAIN
                            else -> 1f
                        }
                        x += sample(clip.pcm, clip.pos) * g
                        xr += sample(clip.pcmR, clip.pos) * g
                        clip.pos += step
                        if (clip.pos >= clip.pcm.size - 1) clip.pos = -1.0
                    }
                }
            }
            out[n] = x
            outR[n] = xr
        }
        if (reverseLevel < 1e-4f) c?.get("reverse")?.pos = -1.0   // start the loop afresh next time
    }

    private fun sample(pcm: FloatArray, pos: Double): Float {
        val i = pos.toInt()
        val f = (pos - i).toFloat()
        val a = pcm[i % pcm.size]
        val b = pcm[(i + 1) % pcm.size]
        return a + (b - a) * f
    }
}

/**
 * A 16-bit PCM WAV as (left, right) floats -- for a mono file both are the same array; extra
 * channels beyond two are ignored. Null if it isn't one.
 */
internal fun readWavStereo(bytes: ByteArray): Pair<FloatArray, FloatArray>? {
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
    var p = 12
    var channels = 1
    var bits = 16
    while (p + 8 <= bytes.size) {
        val tag = String(bytes, p, 4)
        val len = b.getInt(p + 4)
        if (tag == "fmt ") {
            channels = b.getShort(p + 10).toInt()
            bits = b.getShort(p + 22).toInt()
        } else if (tag == "data") {
            if (bits != 16 || channels < 1) return null
            val n = min(len, bytes.size - p - 8) / (2 * channels)
            val left = FloatArray(n)
            val right = if (channels >= 2) FloatArray(n) else left
            var q = p + 8
            for (i in 0 until n) {
                left[i] = b.getShort(q) / 32768f
                if (channels >= 2) right[i] = b.getShort(q + 2) / 32768f
                q += 2 * channels
            }
            return left to right
        }
        p += 8 + len + (len and 1)
    }
    return null
}
