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
 *  - virtual gears (cinematic sounds): 1->2 at 30 km/h, 2->3 at 60 and so on; pitch climbs
 *    8 % through each gear and drops back on the upshift, with a thump and a brief duck
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
        val GEAR_SHIFT_KMH = floatArrayOf(30f, 60f, 90f, 120f)
        const val GEAR_HYSTERESIS_KMH = 2.5f
        const val GEAR_PITCH_DEPTH = 0.08f
        const val SHIFT_DUCK = 0.75f          // a gentle dip under the shift sound, not a cut
        const val SHIFT_DUCK_SEC = 0.18f
        const val REVERSE_DUCK = 0.2f
        const val SHIFT_GAIN = 2.5f           // the gearshift sound sits over the engine, not under it
        const val LAUNCH_GAIN = 1.8f          // launches (build, bang, boom): over the engine too
        const val SHIFT_UP = "shift_cin_up"
        const val SHIFT_DOWN = "shift_cin_down"
        private const val ASSET_RATE = 48000
    }

    private class Clip(val pcm: FloatArray) { var pos = -1.0 }

    private var clips: Map<String, Clip>? = null
    private var loadTried = false

    /** Multiply the main sound by this (ducks under shifts and reversing). */
    var duck = 1f
        private set
    /** Pitch multiplier for sounds with virtual gears. */
    var pitchMul = 1f
        private set
    var gear = 1
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
        return listOf("launch_build", "launch_bang", "boom", SHIFT_UP, SHIFT_DOWN, "reverse", "chime").mapNotNull { n ->
            opener("effects/$n.wav")?.use { readWav16(it.readBytes()) }?.let { n to Clip(it) }
        }.toMap().takeIf { it.isNotEmpty() }
    }

    /**
     * Once per audio block. [pedal] 0..1 (the car's own, else the estimated load); [launch]
     * and [virtualGears] say which effects this sound uses.
     */
    /**
     * Once per audio block. [pedal] 0..1 (the car's own, else the estimated load); [launch] is
     * the kind of launch this sound makes ("ev": build, then a bang if floored; "boom": the
     * sonic boom on a pedal stab) or null for none; [virtualGears] whether this sound gets the
     * virtual gears.
     */
    fun step(dt: Float, speedKmh: Float, pedal: Float, reversing: Boolean, launch: String?, virtualGears: Boolean) {
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

        // virtual gears
        if (virtualGears && !reverse) {
            val upAt = if (gear <= GEAR_SHIFT_KMH.size) GEAR_SHIFT_KMH[gear - 1] else 1e9f
            val downAt = if (gear > 1) GEAR_SHIFT_KMH[gear - 2] - GEAR_HYSTERESIS_KMH else -1f
            if (speedKmh > upAt) {
                gear++
                duckT = SHIFT_DUCK_SEC
                fire(SHIFT_UP)
            } else if (speedKmh < downAt) {
                gear--
                fire(SHIFT_DOWN)
            }
            val lo = if (gear > 1) GEAR_SHIFT_KMH[gear - 2] else 0f
            val hi = if (gear <= GEAR_SHIFT_KMH.size) GEAR_SHIFT_KMH[gear - 1] else lo + 40f
            val frac = ((speedKmh - lo) / (hi - lo)).coerceIn(0f, 1f)
            pitchMul = 1f - GEAR_PITCH_DEPTH + GEAR_PITCH_DEPTH * frac
        } else {
            gear = 1
            pitchMul = 1f
        }

        duckT = max(0f, duckT - dt)
        duck = (if (reverse) REVERSE_DUCK else 1f) * (if (duckT > 0f) SHIFT_DUCK else 1f)
    }

    private fun fire(name: String) {
        clips?.get(name)?.pos = 0.0
    }

    /** The hydraulic gearshift sound, for a sound's own gearbox shifting. */
    fun shift(up: Boolean) = fire(if (up) SHIFT_UP else SHIFT_DOWN)

    /** The start-up chime. */
    fun chime() = fire("chime")

    /**
     * Ducks [out] (the main sound, already rendered) and mixes the effects in at [volume].
     */
    fun mix(out: FloatArray, frames: Int, volume: Float) {
        val c = clips
        val step = ASSET_RATE / sampleRate.toDouble()
        val revTarget = if (reverse) 1f else 0f
        val k = 1f / (0.15f * sampleRate)          // ~150 ms fades for the duck and the reverse loop
        for (n in 0 until frames) {
            duckNow += (duck - duckNow) * min(1f, k * 2.5f)     // ~60 ms: eased in and out
            reverseLevel += (revTarget - reverseLevel) * k * 3f
            var x = out[n] * duckNow
            if (c != null) {
                for ((name, clip) in c) {
                    if (name == "reverse") {
                        if (reverseLevel < 1e-4f) continue
                        if (clip.pos < 0) clip.pos = 0.0
                        x += sample(clip) * reverseLevel * volume
                        clip.pos = (clip.pos + step) % clip.pcm.size
                    } else if (clip.pos >= 0) {
                        val g = when (name) {
                            SHIFT_UP, SHIFT_DOWN -> SHIFT_GAIN
                            "launch_build", "launch_bang", "boom" -> LAUNCH_GAIN
                            else -> 1f
                        }
                        x += sample(clip) * volume * g
                        clip.pos += step
                        if (clip.pos >= clip.pcm.size - 1) clip.pos = -1.0
                    }
                }
            }
            out[n] = x
        }
        if (reverseLevel < 1e-4f) c?.get("reverse")?.pos = -1.0   // start the loop afresh next time
    }

    private fun sample(c: Clip): Float {
        val i = c.pos.toInt()
        val f = (c.pos - i).toFloat()
        val a = c.pcm[i % c.pcm.size]
        val b = c.pcm[(i + 1) % c.pcm.size]
        return a + (b - a) * f
    }
}

/** 16-bit PCM WAV (mono or stereo, mixed down) as floats; null if it isn't one. */
internal fun readWav16(bytes: ByteArray): FloatArray? {
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
            if (bits != 16) return null
            val n = min(len, bytes.size - p - 8) / (2 * channels)
            val out = FloatArray(n)
            var q = p + 8
            for (i in 0 until n) {
                var s = 0f
                for (ch in 0 until channels) { s += b.getShort(q) / 32768f; q += 2 }
                out[i] = s / channels
            }
            return out
        }
        p += 8 + len + (len and 1)
    }
    return null
}
