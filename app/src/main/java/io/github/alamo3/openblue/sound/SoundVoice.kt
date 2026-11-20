package io.github.alamo3.openblue.sound

import java.io.InputStream

/**
 * Plays whichever sound is chosen: a synthesized engine ([EngineVoice]), an EV sound
 * ([FutureVoice]) or a recorded engine ([LoopVoice], whose sound files [open] reads).
 */
class SoundVoice(sampleRate: Int, open: ((String) -> InputStream?)? = null) {
    private val engine = EngineVoice(sampleRate)
    private val future = FutureVoice(sampleRate)
    private val recorded = LoopVoice(sampleRate, open)

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
        engine.set(state)
        future.set(state)
        recorded.set(state)
    }

    fun render(out: FloatArray, frames: Int = out.size) {
        when (sound.kind) {
            EngineSound.Kind.PISTON -> engine.render(out, frames)
            EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.render(out, frames)
            else -> future.render(out, frames)
        }
    }
}
