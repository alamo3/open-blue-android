package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small real-time DSP kit for the layered voices ([Flat6Voice], [HypercarVoice], [JetVoice],
 * [WarpVoice]): everything runs sample by sample on the audio thread, with filter coefficients
 * updated once per block. Ported from the virtual-car-sound prototypes (layered/dsp.py).
 */
internal object Dsp {
    private const val TABLE = 4096
    private val sineTable = FloatArray(TABLE + 1) { sin(2.0 * PI * it / TABLE).toFloat() }

    /** sin(2π·phase) for a phase in cycles (any value), by table. */
    fun sin1(phase: Double): Float {
        val p = phase - kotlin.math.floor(phase)
        val x = p * TABLE
        val i = x.toInt()
        val f = (x - i).toFloat()
        return sineTable[i] + (sineTable[i + 1] - sineTable[i]) * f
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    fun clamp01(x: Float) = x.coerceIn(0f, 1f)

    /** PolyBLEP residual for a unit step at phase 0 (t in [0, 1), dt = f / sr). */
    fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val u = t / dt; u + u - u * u - 1 }
        t > 1 - dt -> { val u = (t - 1) / dt; u * u + u + u + 1 }
        else -> 0.0
    }

    /** Band-limited sawtooth at phase [t] (cycles, wrapped to [0, 1)) and step [dt]. */
    fun saw(t: Double, dt: Double): Float = (2 * t - 1 - blep(t, dt)).toFloat()

    fun softClip(x: Float, drive: Float) = (kotlin.math.tanh(x * drive) / drive)
}

/** A one-pole smoother: follows its target with time constant [sec]. */
internal class Smooth(sec: Float, sr: Int, var value: Float = 0f) {
    private val k = 1f - exp(-1f / (sec * sr))
    fun next(target: Float): Float { value += (target - value) * k; return value }
    /** One step for a whole block of [frames] samples. */
    fun block(target: Float, frames: Int): Float {
        value += (target - value) * (1f - (1f - k).pow(frames)); return value
    }
}

/** Fast noise (xorshift): [white] is uniform in -1..1, [gauss] has unit spread. */
internal class Noise(seed: Int) {
    private var s = if (seed == 0) 0x2545F491 else seed
    fun white(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return s * (1f / Int.MAX_VALUE)
    }
    /** Roughly normal, unit spread (sum of three uniforms): the prototypes' white noise. */
    fun gauss() = white() + white() + white()
    fun uniform(a: Float, b: Float) = a + (b - a) * (white() * 0.5f + 0.5f)
    fun chance(p: Float) = white() * 0.5f + 0.5f < p
}

/** Pink noise (Paul Kellet's economy filter), roughly unit level. */
internal class Pink(seed: Int) {
    private val n = Noise(seed)
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    fun next(): Float {
        val w = n.white()
        b0 = 0.99765f * b0 + w * 0.0990460f
        b1 = 0.96300f * b1 + w * 0.2965164f
        b2 = 0.57000f * b2 + w * 1.0526913f
        return (b0 + b1 + b2 + w * 0.1848f) * 0.18f       // matches the prototypes' pink (rms ~0.3)
    }
}

/** RBJ biquad; call one of the setters (once per block is plenty) and then [process]. */
internal class Biq {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    private fun norm(c0: Double, c1: Double, c2: Double, d0: Double, d1: Double, d2: Double) {
        b0 = (c0 / d0).toFloat(); b1 = (c1 / d0).toFloat(); b2 = (c2 / d0).toFloat()
        a1 = (d1 / d0).toFloat(); a2 = (d2 / d0).toFloat()
    }

    private fun w(f: Float, sr: Int) = 2 * PI * f.toDouble().coerceIn(10.0, sr * 0.45) / sr

    fun lowPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al); return this
    }
    fun highPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); return this
    }
    fun bandPass(f: Float, q: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q)
        norm(al, 0.0, -al, 1 + al, -2 * c, 1 - al); return this
    }
    fun peak(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a); return this
    }
    fun lowShelf(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
        return this
    }

    fun highShelf(f: Float, q: Float, db: Float, sr: Int): Biq {
        val w = w(f, sr); val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(db / 40.0)
        val s = 2 * sqrt(a) * al
        norm(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
        return this
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * A two-pole resonator: struck with an impulse it rings as exp(-t/[decaySec])·sin(2πft), at
 * about unit amplitude -- one formant of a pulse. Retune per block.
 */
internal class Resonator {
    private var c1 = 0f; private var c2 = 0f; private var g = 0f
    private var y1 = 0f; private var y2 = 0f
    fun set(f: Float, decaySec: Float, sr: Int) {
        val r = exp(-1.0 / (decaySec * sr))
        val w = 2 * PI * f.toDouble().coerceIn(10.0, sr * 0.45) / sr
        c1 = (2 * r * cos(w)).toFloat(); c2 = (-r * r).toFloat(); g = sin(w).toFloat()
    }
    fun process(x: Float): Float {
        val y = x * g + c1 * y1 + c2 * y2
        y2 = y1; y1 = y
        return y
    }
}

/** Random wander in about -1..1, gliding to a new random target roughly every 1/[hz] s. */
internal class Wander(private val hz: Float, private val sr: Int, seed: Int) {
    private val n = Noise(seed)
    private var from = 0f; private var to = n.white(); private var t = 0f
    fun next(dtSamples: Int = 1): Float {
        t += hz * dtSamples / sr
        if (t >= 1f) { t -= 1f; from = to; to = n.white() }
        val s = 0.5f - 0.5f * cos(PI.toFloat() * t)
        return from + (to - from) * s
    }
}

/** Common to the layered voices: what they're told each block, and their output level. */
internal abstract class LayeredVoice(protected val sr: Int) {
    @Volatile var volume = 0.8f
    @Volatile protected var st: EngineState? = null
    @Volatile protected var pedal = 0f
    @Volatile protected var accel = 0f
    @Volatile protected var motorRpm = 0f

    /** Once per block: the gearbox / engine state, the car's pedal, acceleration (m/s²) and motor rpm. */
    fun set(state: EngineState, pedal: Float, accel: Float, motorRpm: Float) {
        st = state; this.pedal = pedal; this.accel = accel; this.motorRpm = motorRpm
    }

    /** Renders [frames] stereo samples into [out] / [outR] (overwriting them). */
    abstract fun render(out: FloatArray, outR: FloatArray, frames: Int)

    protected fun speedNorm(kmh: Float) = (kmh / 160f).coerceIn(0f, 1f)
}
