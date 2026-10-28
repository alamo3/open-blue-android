package io.github.alamo3.openblue.sound

/** Plays whichever sound is chosen: an engine ([EngineVoice]) or an EV sound ([FutureVoice]). */
class SoundVoice(sampleRate: Int) {
    private val engine = EngineVoice(sampleRate)
    private val future = FutureVoice(sampleRate)

    var sound: EngineSound = EngineSound.STARSHIP
        set(v) {
            field = v
            if (v.isEngine) engine.sound = v else future.kind = v.kind
        }

    var volume: Float = 0.8f
        set(v) {
            field = v
            engine.volume = v
            future.volume = v
        }

    fun set(state: EngineState) {
        engine.set(state)
        future.set(state)
    }

    fun render(out: FloatArray, frames: Int = out.size) {
        if (sound.isEngine) engine.render(out, frames) else future.render(out, frames)
    }
}
