package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sounds made for an EV rather than imitating an engine. Like the real motor, which is geared
 * straight to the wheels, their pitch follows road speed; how hard you're accelerating sets how
 * loud, bright and intense they get. When you're not accelerating they settle to a quiet hum.
 *
 * - Starship: a detuned, filtered synth drone that climbs about two octaves with speed.
 * - Turbine: a jet-style whine that spools up, with roar under load.
 * - Electric+: the motor's own whine, exaggerated: electrical tones, gear mesh, inverter.
 *
 * Pure Kotlin, so it can be tested and rendered off the phone.
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
    private val spoolUpK = coef(0.22f)
    private val spoolDownK = coef(0.8f)

    private var speed = 0f
    private var load = 0f
    private var level = 0f
    private var spool = 0.15f
    private val phase = DoubleArray(8)
    private var lfo = 0.0
    private var counter = 0

    private val filter = Svf()
    private val roar = Svf()
    private val rumble = Svf()
    private val air = Svf()

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
                EngineSound.Kind.TURBINE -> turbine(sn)
                EngineSound.Kind.ELECTRIC -> electric()
                else -> starship(sn)
            }
            counter++
            out[i] = tanh(x * level * volume * 1.4f)
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

    private fun turbine(sn: Float): Float {
        // Like a real turbine, it takes a moment to spool up and longer to wind down.
        val want = if (targetEngaged) 0.12f + 0.62f * sn + 0.26f * load else 0.12f + 0.4f * sn
        spool += (want - spool) * if (want > spool) spoolUpK else spoolDownK
        val fw = 380f + 2600f * spool
        val body = 0.3f + 0.7f * spool
        var x = (sine(0, fw) * 0.32f + sine(1, fw * 1.52f) * 0.16f + sine(2, fw * 2.31f) * 0.08f + sine(3, fw * 0.5f) * 0.2f) * body
        if ((counter and 15) == 0) {
            roar.set(220f + 1400f * spool, 0.7f, sr)
            rumble.set(110f, 0.7f, sr)
        }
        val noise = rnd.nextFloat() * 2f - 1f
        x += roar.bandPass(noise) * (0.25f + 1.2f * load) * spool
        x += rumble.lowPass(noise) * 1.6f * load
        lfo += 7.0 / sr
        return x * 2f * (1f + 0.05f * sin(2 * PI * lfo).toFloat())
    }

    private fun electric(): Float {
        // Motor turns about 1.28 times a second per km/h (Ioniq 5 gearing); 4 pole pairs.
        val rot = speed * 1.28f
        val elec = 4f * rot
        val push = 0.4f + 0.6f * load
        var x = sine(0, elec) * 0.32f + sine(1, elec * 2f) * 0.13f + sine(2, elec * 6f) * 0.05f
        x += sine(3, elec * 0.25f) * 0.3f * load // body underneath
        x += sine(4, rot * 23f) * 0.06f // gear mesh
        // Inverter switching tones either side of a fixed carrier, louder under load.
        x += (sine(5, 1600f + 2f * elec) + sine(6, (1600f - 2f * elec).coerceAtLeast(40f))) * 0.07f * load
        return x * push * 2.1f
    }

    /** Topology-preserving state variable filter (low-pass and band-pass). */
    private class Svf {
        private var g = 0f
        private var k = 1f
        private var ic1 = 0f
        private var ic2 = 0f

        fun set(hz: Float, q: Float, sr: Float) {
            g = tan(PI * min(hz, sr * 0.45f) / sr).toFloat()
            k = 1f / q
        }

        private var band = 0f
        private var low = 0f

        // No allocation per sample: this runs on the audio thread.
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
}
