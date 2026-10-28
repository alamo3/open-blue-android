package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** Cylinders, how they fire, and how the exhaust resonates. */
class PistonSpec(
    val cylinders: Int,
    /** Main exhaust resonance and a higher pipe resonance, in Hz. */
    val exhaustHz: Float,
    val pipeHz: Float,
    /** Per-cylinder strength, which gives the engine its lumpiness. */
    val pulseGain: FloatArray,
    /** Per-cylinder timing offsets, as a fraction of the firing interval (uneven firing). */
    val pulseTiming: FloatArray,
    /** How much each pulse varies at random. */
    val roughness: Float,
)

/** The sounds on offer: four sci-fi sounds made for an EV, and four classic engines. */
enum class EngineSound(
    val label: String,
    val kind: Kind,
    val idleRpm: Float = 900f,
    val redline: Float = 6900f,
    val piston: PistonSpec? = null,
) {
    STARSHIP("Starship", Kind.STARSHIP),
    HYPERDRIVE("Hyperdrive", Kind.HYPERDRIVE),
    ION("Ion pulse", Kind.ION),
    CRUISER("Cruiser", Kind.CRUISER),
    INLINE_4(
        "Inline-4 turbo", Kind.PISTON, 900f, 6900f,
        PistonSpec(4, 125f, 440f, floatArrayOf(1f, 0.88f, 0.97f, 0.84f), floatArrayOf(0f, 0f, 0f, 0f), 0.25f),
    ),
    INLINE_5(
        "Inline-5", Kind.PISTON, 850f, 7000f,
        PistonSpec(5, 110f, 390f, floatArrayOf(1f, 0.8f, 0.93f, 0.78f, 0.9f), floatArrayOf(0f, 0.04f, -0.03f, 0.05f, -0.02f), 0.3f),
    ),
    V6(
        "V6", Kind.PISTON, 750f, 7200f,
        PistonSpec(6, 100f, 340f, floatArrayOf(1f, 0.86f, 0.95f, 0.9f, 0.97f, 0.84f), floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f), 0.22f),
    ),
    V8(
        "V8 burble", Kind.PISTON, 700f, 6800f,
        PistonSpec(
            8, 82f, 290f,
            floatArrayOf(1f, 0.74f, 0.92f, 0.7f, 0.97f, 0.76f, 0.9f, 0.72f),
            floatArrayOf(0f, 0.16f, -0.1f, 0.13f, 0.02f, -0.15f, 0.1f, -0.12f), 0.35f,
        ),
    ),
    ;

    enum class Kind { STARSHIP, HYPERDRIVE, ION, CRUISER, PISTON }

    val isEngine: Boolean get() = kind == Kind.PISTON
}

/**
 * Makes engine sound from nothing: each cylinder firing is a pressure pulse, and the train of
 * pulses runs through resonances like an exhaust system's. Faster revs push the pulses together
 * into a tone; more load makes them stronger, rougher and brighter. Also plays the ignition cut
 * on upshifts, pops and crackles when lifting off high in the revs, and the rev limiter.
 *
 * Pure Kotlin so it can be tested and rendered off the phone. Not thread-safe: [set] from one
 * thread and [render] from the audio thread is fine because the fields are simple and @Volatile.
 */
class EngineVoice(private val sampleRate: Int, seed: Int = 7) {
    @Volatile var sound: EngineSound = EngineSound.INLINE_4
    @Volatile var volume: Float = 0.8f

    @Volatile private var targetRpm = 850f
    @Volatile private var targetLoad = 0f
    @Volatile private var targetShifts = 0
    @Volatile private var targetLimiter = false
    @Volatile private var targetLiftOffs = 0

    fun set(state: EngineState) {
        targetRpm = state.rpm
        targetLoad = state.load
        targetShifts = state.shifts
        targetLimiter = state.limiter
        targetLiftOffs = state.liftOffs
    }

    private val rnd = Random(seed)
    private val sr = sampleRate.toFloat()
    private val smooth = 1f - exp(-1f / (sr * 0.015f)).toFloat()

    private var rpm = 850f
    private var load = 0f
    private var crank = 0.0
    private var fired = 0L
    private var nextFire = 1.0

    private var pulseT = 1f
    private var pulseW = 0.002f
    private var pulseAmp = 0f
    private var noiseEnv = 0f

    private var shiftsSeen = 0
    private var liftOffsSeen = 0
    private var cut = 0f
    private var crackleFor = 0f
    private var popEnv = 0f
    private var limiterPhase = 0f

    private val exhaust = Biquad()
    private val pipe = Biquad()
    private val body = Biquad()
    private val popTone = Biquad()
    private var dcX = 0f
    private var dcY = 0f
    private var counter = 0
    private var configured: EngineSound? = null

    private fun configure(s: EngineSound) {
        val p = s.piston ?: EngineSound.INLINE_4.piston!!
        exhaust.bandPass(p.exhaustHz, 0.9f, sr)
        pipe.bandPass(p.pipeHz, 2.2f, sr)
        popTone.bandPass(1100f, 1.4f, sr)
        configured = s
    }

    fun render(out: FloatArray, frames: Int = out.size) {
        val snd = sound
        if (snd !== configured) configure(snd)
        val s = snd.piston ?: EngineSound.INLINE_4.piston!!
        val cyl = s.cylinders
        if (targetShifts != shiftsSeen) {
            if (targetShifts > shiftsSeen) {
                cut = 0.075f // ignition cut on the upshift
                popEnv = 0.7f
            }
            shiftsSeen = targetShifts
        }
        if (targetLiftOffs != liftOffsSeen) {
            if (targetLiftOffs > liftOffsSeen) crackleFor = 1.1f
            liftOffsSeen = targetLiftOffs
        }

        for (i in 0 until frames) {
            rpm += (targetRpm - rpm) * smooth
            load += (targetLoad - load) * smooth

            if ((counter and 31) == 0) {
                body.lowPass(350f + 2600f * load + rpm * 0.25f, 0.7f, sr)
            }
            counter++

            // Turn the crank; fire each cylinder when its turn comes round.
            val firingHz = rpm / 60f * cyl / 2f
            crank += firingHz / sr
            if (crank >= nextFire) {
                val c = (fired % cyl).toInt()
                fired++
                nextFire = fired + 1 + s.pulseTiming[(fired % cyl).toInt()].toDouble()
                var a = s.pulseGain[c] * (1f + (rnd.nextFloat() - 0.5f) * s.roughness * (0.6f + load))
                a *= 0.32f + 0.68f * load
                if (cut > 0f) a *= 0.06f
                if (targetLimiter && limiterPhase < 0.35f) a *= 0.05f
                pulseAmp = a
                pulseT = 0f
                // Pulses widen at low revs (a soft thump) and narrow as they crowd together.
                pulseW = (0.5f / firingHz).coerceIn(0.0012f, 0.006f)
                noiseEnv = a * (0.25f + 0.75f * load)
            }

            var e = 0f
            if (pulseT < pulseW) {
                e = pulseAmp * sin(PI.toFloat() * pulseT / pulseW)
            }
            pulseT += 1f / sr
            val white = rnd.nextFloat() * 2f - 1f
            noiseEnv *= 0.993f
            val x = e + white * noiseEnv * 0.35f

            var y = body.process(x) * 1.1f + exhaust.process(x) * 6f + pipe.process(x) * 2.2f

            // Pops: short bursts through a mid resonance, plus a thump.
            if (crackleFor > 0f) {
                crackleFor -= 1f / sr
                if (rnd.nextFloat() < 22f * crackleFor / sr) popEnv = 0.4f + rnd.nextFloat() * 0.6f
            }
            if (popEnv > 0.001f) {
                y += popTone.process(white * popEnv) * 5f + white * popEnv * 0.25f
                popEnv *= 0.9965f
            } else {
                popTone.process(0f)
            }

            // Push harder, sound harsher.
            val drive = 1.4f + 2.6f * load
            y = tanh(y * drive) / tanh(drive)

            // Remove any DC offset.
            val dc = y - dcX + 0.995f * dcY
            dcX = y
            dcY = dc

            if (cut > 0f) cut -= 1f / sr
            if (targetLimiter) limiterPhase = (limiterPhase + 16f / sr) % 1f

            out[i] = tanh(dc * volume * (0.5f + 0.5f * load) * 1.2f)
        }
    }

    /** RBJ biquad filters. */
    private class Biquad {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun bandPass(f: Float, q: Float, sr: Float) {
            val w = 2.0 * PI * min(f, sr * 0.45f) / sr
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            b0 = (alpha / a0).toFloat(); b1 = 0f; b2 = (-alpha / a0).toFloat()
            a1 = (-2 * kotlin.math.cos(w) / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
        }

        fun lowPass(f: Float, q: Float, sr: Float) {
            val w = 2.0 * PI * min(f, sr * 0.45f) / sr
            val alpha = sin(w) / (2 * q)
            val cw = kotlin.math.cos(w)
            val a0 = 1 + alpha
            b0 = ((1 - cw) / 2 / a0).toFloat(); b1 = ((1 - cw) / a0).toFloat(); b2 = b0
            a1 = (-2 * cw / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
}
