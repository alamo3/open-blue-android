package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sci-fi sounds made for an EV rather than imitating an engine. Like the real motor, which is
 * geared straight to the wheels, their pitch follows road speed; how hard you accelerate sets
 * how loud, bright and intense they get. When you're not accelerating they settle to a quiet hum.
 *
 * - Starship: a detuned synth drone that climbs about two octaves with speed and opens up.
 * - Hyperdrive: a Shepard tone, which seems to rise forever while you accelerate.
 * - Ion pulse: an FM bass that growls under load and throbs faster with speed.
 * - Cruiser: a cinematic chord that brightens, and gains a top note, as you push.
 *
 * All share a small reverb for a sense of space. Pure Kotlin, so it can be tested and rendered
 * off the phone. Nothing here allocates per sample: it runs on the audio thread.
 */
class FutureVoice(private val sampleRate: Int, seed: Int = 11) {
    @Volatile var kind: EngineSound.Kind = EngineSound.Kind.STARSHIP
    @Volatile var volume = 0.8f

    @Volatile private var targetSpeed = 0f
    @Volatile private var targetLoad = 0f
    @Volatile private var targetEngaged = false

    fun set(state: EngineState) {
        targetSpeed = state.speedKmh
        targetLoad = state.load
        targetEngaged = state.engaged
    }

    private val sr = sampleRate.toFloat()
    private val rnd = Random(seed)
    private fun coef(seconds: Float) = 1f - exp(-1f / (sr * seconds))
    private val speedK = coef(0.05f)
    private val loadK = coef(0.04f)
    private val attackK = coef(0.06f)
    private val releaseK = coef(0.45f)

    private var speed = 0f
    private var load = 0f
    private var level = 0f
    private val phase = DoubleArray(16)
    private var lfo = 0.0
    private var lfo2 = 0.0
    private var shepard = 0.0
    private var counter = 0

    private val filter = Svf()
    private val air = Svf()
    private val reverb = Reverb(sr)

    fun render(out: FloatArray, frames: Int = out.size) {
        val k = kind
        for (i in 0 until frames) {
            speed += (targetSpeed - speed) * speedK
            load += (targetLoad - load) * loadK
            // Speed on a curve that changes most at low speed, where most driving happens.
            val sn = sqrt((speed / 200f).coerceIn(0f, 1f))
            val want = if (targetEngaged) 0.35f + 0.65f * load else 0.1f + 0.08f * sn
            level += (want - level) * if (want > level) attackK else releaseK

            val x = when (k) {
                EngineSound.Kind.HYPERDRIVE -> hyperdrive(sn)
                EngineSound.Kind.ION -> ion(sn)
                EngineSound.Kind.CRUISER -> cruiser(sn)
                else -> starship(sn)
            }
            counter++
            val dry = tanh(x * level * volume * 1.4f)
            out[i] = tanh(dry + reverb.process(dry) * 0.22f)
        }
    }

    private fun osc(n: Int, hz: Float): Double {
        phase[n] += hz / sr
        if (phase[n] >= 1.0) phase[n] -= 1.0
        return phase[n]
    }

    private fun sine(n: Int, hz: Float) = sin(2 * PI * osc(n, hz)).toFloat()

    /** Band-limited sawtooth (PolyBLEP), so high notes don't alias. */
    private fun saw(n: Int, hz: Float): Float {
        val t = osc(n, hz)
        val dt = hz / sr.toDouble()
        var v = 2 * t - 1
        if (t < dt) {
            val u = t / dt
            v -= u + u - u * u - 1
        } else if (t > 1 - dt) {
            val u = (t - 1) / dt
            v -= u * u + u + u + 1
        }
        return v.toFloat()
    }

    private fun starship(sn: Float): Float {
        // About two octaves across the speed range, lifting a little more under load.
        val f0 = 46f * 2f.pow(sn * 2.3f + load * 0.35f)
        val detune = 0.004f + 0.012f * load
        var x = (saw(0, f0 * (1 - detune)) + saw(1, f0) + saw(2, f0 * (1 + detune))) * 0.33f
        x += sine(3, f0 * 0.5f) * 0.55f
        x += saw(4, f0 * 1.5f) * 0.28f * load
        x += sine(5, f0 * 4f) * 0.08f * sn
        // A slow sweep that quickens with speed.
        lfo += (0.25 + 3.5 * sn) / sr
        val wobble = 1f + 0.15f * sin(2 * PI * lfo).toFloat()
        if ((counter and 15) == 0) {
            filter.set((160f + 3600f * load * level + 900f * sn) * wobble, 1.1f, sr)
            air.set(900f + 2600f * sn, 0.8f, sr)
        }
        val noise = rnd.nextFloat() * 2f - 1f
        return filter.lowPass(x) * 1.3f + air.bandPass(noise) * 0.12f * sn
    }

    /**
     * Six tones an octave apart glide upward together; each fades in at the bottom and out at
     * the top, so the whole seems to rise forever. It glides while you accelerate (faster the
     * harder you push) and holds still otherwise.
     */
    private fun hyperdrive(sn: Float): Float {
        val n = 6
        val rate = if (targetEngaged) 0.04 + 0.5 * load else 0.0
        shepard = (shepard + rate / sr) % 1.0
        val base = 38f * 2f.pow(sn * 1.2f)
        var x = 0f
        for (j in 0 until n) {
            val p = ((j + shepard) % n).toFloat()
            val hz = base * 2f.pow(p)
            val amp = 0.5f * (1f - cos(2f * PI.toFloat() * p / n))
            val ph = 2 * PI * osc(j, hz)
            x += amp * (sin(ph) + 0.35f * load * sin(2 * ph)).toFloat()
        }
        x *= 0.42f
        x += sine(8, base * 0.5f) * 0.35f
        if ((counter and 15) == 0) filter.set(500f + 5000f * load + 1500f * sn, 0.8f, sr)
        return filter.lowPass(x) * 1.5f
    }

    /** FM bass: more modulation under load makes it growl; a pulse throbs faster with speed. */
    private fun ion(sn: Float): Float {
        val f = 44f * 2f.pow(sn * 1.8f)
        val index = 0.6f + 3.2f * load
        val mod = sin(2 * PI * osc(0, f * 2f)).toFloat()
        val carrier = sin(2 * PI * osc(1, f) + index * mod).toFloat()
        val sub = sine(2, f * 0.5f)
        lfo += (2.5 + 12.0 * sn) / sr
        val pulseRaw = 0.5f + 0.5f * sin(2 * PI * lfo).toFloat()
        val depth = 0.35f + 0.4f * load
        val pulse = 1f - depth + depth * pulseRaw * pulseRaw
        if ((counter and 15) == 0) {
            filter.set(350f + 4200f * load + 800f * sn, 0.9f, sr)
            air.set(3800f + 2000f * sn, 1.2f, sr)
        }
        val noise = rnd.nextFloat() * 2f - 1f
        val sparkle = air.bandPass(noise) * 0.35f * load * pulseRaw
        return (filter.lowPass(carrier * 0.8f + sub * 0.5f) * 1.4f + sparkle) * pulse
    }

    /**
     * Root, fifth and octave from detuned saws with a gentle chorus; past half power a major
     * third fades in on top, and the filter opens the harder you push.
     */
    private fun cruiser(sn: Float): Float {
        val root = 55f * 2f.pow(sn * 1.5f)
        lfo2 += 0.6 / sr
        val chorus = 1f + 0.003f * sin(2 * PI * lfo2).toFloat()
        val d = 1.006f
        var x = (saw(0, root) + saw(1, root * d * chorus)) * 0.3f
        x += (saw(2, root * 1.5f) + saw(3, root * 1.5f / d)) * 0.24f
        x += (saw(4, root * 2f * chorus) + saw(5, root * 2f / d)) * 0.2f
        val third = ((load - 0.45f) / 0.35f).coerceIn(0f, 1f)
        x += (saw(6, root * 2.5f) + saw(7, root * 2.5f * d)) * 0.18f * third
        x += sine(8, root * 0.5f) * 0.45f
        // Slight vibrato on the whole thing, like a held section.
        lfo += 5.0 / sr
        val vib = 1f + 0.06f * load * sin(2 * PI * lfo).toFloat()
        if ((counter and 15) == 0) filter.set((250f + 3200f * load + 700f * sn) * vib, 0.9f, sr)
        return filter.lowPass(x) * 1.25f
    }

    /** Topology-preserving state variable filter (low-pass and band-pass). */
    private class Svf {
        private var g = 0f
        private var k = 1f
        private var ic1 = 0f
        private var ic2 = 0f
        private var band = 0f
        private var low = 0f

        fun set(hz: Float, q: Float, sr: Float) {
            g = tan(PI * min(hz, sr * 0.45f) / sr).toFloat()
            k = 1f / q
        }

        private fun step(v0: Float) {
            val v1 = (ic1 + g * (v0 - ic2)) / (1 + g * (g + k))
            val v2 = ic2 + g * v1
            ic1 = 2 * v1 - ic1
            ic2 = 2 * v2 - ic2
            band = v1
            low = v2
        }

        fun lowPass(x: Float): Float { step(x); return low }
        fun bandPass(x: Float): Float { step(x); return band }
    }

    /** A small Schroeder reverb: four damped combs into two all-passes. Returns the wet signal. */
    private class Reverb(sr: Float) {
        private val scale = sr / 44100f
        private val combs = intArrayOf(1557, 1617, 1491, 1422).map { FloatArray((it * scale).toInt()) }
        private val combPos = IntArray(4)
        private val combLp = FloatArray(4)
        private val aps = intArrayOf(556, 225).map { FloatArray((it * scale).toInt()) }
        private val apPos = IntArray(2)

        fun process(x: Float): Float {
            var sum = 0f
            for (c in 0 until 4) {
                val buf = combs[c]
                val y = buf[combPos[c]]
                combLp[c] = y * 0.75f + combLp[c] * 0.25f
                buf[combPos[c]] = x * 0.25f + combLp[c] * 0.8f
                combPos[c] = (combPos[c] + 1) % buf.size
                sum += y
            }
            var y = sum
            for (a in 0 until 2) {
                val buf = aps[a]
                val b = buf[apPos[a]]
                val v = y + b * 0.5f
                buf[apPos[a]] = v
                y = b - v * 0.5f
                apPos[a] = (apPos[a] + 1) % buf.size
            }
            return y
        }
    }
}
