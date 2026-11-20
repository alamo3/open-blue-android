package io.github.alamo3.openblue.sound

import java.io.InputStream

/**
 * Plays whichever sound is chosen: a synthesized engine ([EngineVoice]), an EV sound
 * ([FutureVoice]) or a recorded engine / baked cinematic sound ([LoopVoice], whose sound files
 * [open] reads), plus the drive effects ([DriveEffects]) and a peak limiter on the way out.
 */
class SoundVoice(
    private val sampleRate: Int,
    /** Plays FMOD sound sets (its own output); null where FMOD isn't available (tests). */
    private val external: ExternalVoice? = null,
    open: ((String) -> InputStream?)? = null,
) {
    companion object {
        private const val CEILING = 0.95f          // output peaks never go above this
        private const val LIMIT_RELEASE_SEC = 0.15f
    }

    private val engine = EngineVoice(sampleRate)
    private val future = FutureVoice(sampleRate)
    private val recorded = LoopVoice(sampleRate, open)
    private val effects = DriveEffects(sampleRate, open)
    private var state: EngineState? = null
    @Volatile private var pedal: Float? = null
    @Volatile private var reversing = false
    private var limitEnv = 0f
    private var chimed = false
    private var pitchMul = 1f
    private var shiftsSeen = -1
    private var downSeen = -1

    /**
     * The car's own pedal (0..1) and whether it's reversing, from the Raspberry Pi; null pedal
     * when only the phone's sensors are available.
     */
    fun drive(pedal: Float?, reversing: Boolean) {
        this.pedal = pedal
        this.reversing = reversing
    }

    var sound: EngineSound = EngineSound.STARSHIP
        set(v) {
            field = v
            shiftsSeen = -1      // a different sound's gearbox: don't hear its count change as a shift
            downSeen = -1
            when (v.kind) {
                EngineSound.Kind.PISTON -> engine.sound = v
                EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.setId = v.loopSet
                EngineSound.Kind.FMOD -> {}
                else -> future.kind = v.kind
            }
        }

    var volume: Float = 0.8f
        set(v) {
            field = v
            engine.volume = v
            future.volume = v
            recorded.volume = v
        }

    fun set(state: EngineState) {
        this.state = state
        engine.set(state)
        future.set(state)
        recorded.set(state)
    }

    private var left = FloatArray(0)
    private var right = FloatArray(0)

    /** Renders [frames] samples as mono (left and right mixed) into [out]. */
    fun render(out: FloatArray, frames: Int = out.size) {
        renderLR(frames)
        for (n in 0 until frames) out[n] = 0.5f * (left[n] + right[n])
    }

    /** Renders [frames] stereo samples into [out], interleaved left, right. */
    fun renderStereo(out: FloatArray, frames: Int = out.size / 2) {
        renderLR(frames)
        for (n in 0 until frames) {
            out[2 * n] = left[n]
            out[2 * n + 1] = right[n]
        }
    }

    private fun renderLR(frames: Int) {
        if (left.size < frames) { left = FloatArray(frames); right = FloatArray(frames) }
        val out = left
        val outR = right
        val st = state
        val k = sound.kind
        if (!chimed && st != null) { chimed = true; effects.chime() }    // the sound starting up
        if (st != null) {
            effects.step(
                frames / sampleRate.toFloat(), st.speedKmh, pedal ?: st.load, reversing,
                // launches: the jet booms, the EV and cinematic sounds whoosh; engines (and FMOD
                // jets, which boom by themselves) make their own
                launch = when {
                    k == EngineSound.Kind.BAKED && recorded.isJet -> "boom"
                    !sound.isEngine -> "ev"
                    else -> null
                },
                virtualGears = k == EngineSound.Kind.BAKED,
            )
            // virtual gears: the pitch glides to the new gear (~120 ms) rather than jumping
            val bdt = frames / sampleRate.toFloat()
            pitchMul += (effects.pitchMul - pitchMul) * kotlin.math.min(1f, bdt / 0.12f)
            recorded.pitchMul = pitchMul
            // every gearbox shift of an engine sound (synth, recorded, FMOD car or jet) gets the
            // shift sound; gear >= 1 means gearshifts are on
            if (sound.isEngine && st.gear >= 1) {
                if (shiftsSeen >= 0 && st.shifts != shiftsSeen) effects.shift(up = true)
                if (downSeen >= 0 && st.downshifts != downSeen) effects.shift(up = false)
            }
            shiftsSeen = st.shifts
            downSeen = st.downshifts
        }
        when (k) {
            EngineSound.Kind.PISTON -> { engine.render(out, frames); out.copyInto(outR, 0, 0, frames) }
            EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.render(out, outR, frames)
            EngineSound.Kind.FMOD -> { out.fill(0f, 0, frames); outR.fill(0f, 0, frames) }   // its own output
            else -> { future.render(out, frames); out.copyInto(outR, 0, 0, frames) }
        }
        if (external != null && st != null) {
            // FMOD: same level, fades and ducking as the rest; silent when another sound is chosen
            val level = if (k == EngineSound.Kind.FMOD) volume * effects.duck else 0f
            external.update(st, level, frames / sampleRate.toFloat())
        }
        effects.mix(out, outR, frames, volume)
        limit(out, outR, frames)
    }

    /**
     * Peak limiter, linked across both channels (so the stereo image doesn't shift): instant
     * attack, smooth release, so turning things up never clips.
     */
    private fun limit(out: FloatArray, outR: FloatArray, frames: Int) {
        val rel = 1f - 1f / (LIMIT_RELEASE_SEC * sampleRate)
        var env = limitEnv
        for (n in 0 until frames) {
            val a = kotlin.math.max(kotlin.math.abs(out[n]), kotlin.math.abs(outR[n]))
            env = if (a > env) a else env * rel
            if (env > CEILING) {
                val g = CEILING / env
                out[n] *= g
                outR[n] *= g
            }
        }
        limitEnv = env
    }
}
