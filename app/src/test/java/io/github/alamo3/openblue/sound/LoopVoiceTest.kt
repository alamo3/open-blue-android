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

    /**
     * The loops must actually play through within a block: a player that only moves on once per
     * block holds one sample value for the whole block, which sounds like static. Also writes each
     * render to build/loopvoice/ so it can be listened to or analysed.
     */
    @Test fun loopsPlayThroughEachBlock() {
        val outDir = File("build/loopvoice").apply { mkdirs() }
        val kinds = setOf(EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED)
        for (sound in EngineSound.entries.filter { it.kind in kinds }) {
            if (!File(assets, "loopsets/${sound.loopSet}/set.json").exists()) continue
            val voice = SoundVoice(48000) { p -> File(assets, p).takeIf { it.exists() }?.inputStream() }
                .apply { this.sound = sound }
            val m = EngineModel(idleRpm = sound.idleRpm, redline = sound.redline)
            val buf = FloatArray(480)
            val all = FloatArray(480 * 600)
            for (i in 0 until 600) {             // 6 s: a steady pull from 20 to ~80 km/h
                voice.set(m.update(dt, 6f + i * 0.03f, 2f, null, 0.7f))
                voice.render(buf)
                buf.copyInto(all, i * 480)
            }
            var held = 0
            var live = 0
            for (n in 1 until all.size) {
                if (abs(all[n]) < 1e-4f) continue
                live++
                if (all[n] == all[n - 1]) held++
            }
            writeWav(File(outDir, "${sound.name.lowercase()}.wav"), all)
            assertTrue("$sound is silent", live > all.size / 2)
            assertTrue("$sound holds samples (${held * 100 / live}% repeated): playback not advancing", held < live / 20)
        }
    }

    private fun writeWav(f: File, x: FloatArray) {
        val b = java.nio.ByteBuffer.allocate(44 + x.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(x.size * 2)
        for (v in x) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }
}
