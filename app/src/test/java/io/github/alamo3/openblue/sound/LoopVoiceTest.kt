package io.github.alamo3.openblue.sound

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Renders each recorded engine and baked voice through a pull and a lift-off, from the real asset files. */
class LoopVoiceTest {
    private val assets = File("src/main/assets")
    private val dt = 480 / 48000f

    @Test fun recordedEnginesPlayAndStayInRange() {
        val kinds = setOf(EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED)
        for (sound in EngineSound.entries.filter { it.kind in kinds }) {
            if (!File(assets, "loopsets/${sound.loopSet}/set.json").exists()) continue
            val voice = SoundVoice(48000) { p -> File(assets, p).takeIf { it.exists() }?.inputStream() }
                .apply { this.sound = sound }
            val m = EngineModel(idleRpm = sound.idleRpm, redline = sound.redline, gearshifts = true)
            val buf = FloatArray(480)
            var peak = 0f
            var energy = 0.0
            var n = 0
            for (i in 0 until 1000) {           // 10 s: pull to ~100 km/h, then lift off
                val pedal = if (i < 700) 1f else 0f
                val speed = if (i < 700) i * 0.04f else 28f - (i - 700) * 0.02f
                voice.set(m.update(dt, speed, if (i < 700) 3f else -1f, null, pedal))
                voice.render(buf)
                for (x in buf) {
                    assertTrue("$sound produced NaN", !x.isNaN())
                    peak = maxOf(peak, abs(x)); energy += x * x; n++
                }
            }
            val rms = sqrt(energy / n)
            println("$sound: peak %.2f, rms %.3f".format(peak, rms))
            assertTrue("$sound peak $peak", peak in 0.05f..1.2f)
            assertTrue("$sound is silent (rms $rms)", rms > 0.01)
        }
    }

    @Test fun bakedVoiceFadesAtStandstill() {
        val sound = EngineSound.CINEMATIC_DRIVE
        if (!File(assets, "loopsets/${sound.loopSet}/set.json").exists()) return
        val voice = SoundVoice(48000) { p -> File(assets, p).takeIf { it.exists() }?.inputStream() }
            .apply { this.sound = sound }
        val m = EngineModel()
        val buf = FloatArray(480)
        fun level(blocks: Int, speed: Float, pedal: Float): Float {
            var peak = 0f
            repeat(blocks) {
                voice.set(m.update(dt, speed, 0f, null, pedal))
                voice.render(buf)
                buf.forEach { peak = maxOf(peak, abs(it)) }
            }
            return peak
        }
        assertTrue("plays while moving", level(200, 10f, 0.3f) > 0.05f)
        level(700, 0f, 0f)                        // 7 s stopped: past the hold and the fade
        assertTrue("silent when stopped", level(50, 0f, 0f) < 1e-3f)
        assertTrue("back on the pedal", level(100, 0f, 0.5f) > 0.05f)
    }
}
