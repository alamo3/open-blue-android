package io.github.alamo3.openblue.sound

import java.io.InputStream

/**
 * Plays whichever sound is chosen: a synthesized engine ([EngineVoice]), an EV sound
 * ([FutureVoice]) or a recorded engine / baked cinematic sound ([LoopVoice], whose sound files
 * [open] reads), plus the drive effects ([DriveEffects]) and a peak limiter on the way out.
 */
class SoundVoice(private val sampleRate: Int, open: ((String) -> InputStream?)? = null) {
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
            when (v.kind) {
                EngineSound.Kind.PISTON -> engine.sound = v
                EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.setId = v.loopSet
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

    fun render(out: FloatArray, frames: Int = out.size) {
        val st = state
        val k = sound.kind
        if (st != null) {
            effects.step(
                frames / sampleRate.toFloat(), st.speedKmh, pedal ?: st.load, reversing,
                launch = !sound.isEngine, virtualGears = k == EngineSound.Kind.BAKED,
            )
            recorded.pitchMul = effects.pitchMul
        }
        when (k) {
            EngineSound.Kind.PISTON -> engine.render(out, frames)
            EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.render(out, frames)
            else -> future.render(out, frames)
        }
        effects.mix(out, frames, volume)
        limit(out, frames)
    }

    /** Peak limiter: instant attack, smooth release, so turning things up never clips. */
    private fun limit(out: FloatArray, frames: Int) {
        val rel = 1f - 1f / (LIMIT_RELEASE_SEC * sampleRate)
        var env = limitEnv
        for (n in 0 until frames) {
            val a = kotlin.math.abs(out[n])
            env = if (a > env) a else env * rel
            if (env > CEILING) out[n] *= CEILING / env
        }
        limitEnv = env
    }
}
