package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class DriveEffectsTest {
    private val assets = File("src/main/assets")
    private val dt = 480 / 48000f
    private fun opener(): (String) -> java.io.InputStream? = { p -> File(assets, p).takeIf { it.exists() }?.inputStream() }

    @Test fun drivetrainShiftsAtThePisSpeeds() {
        val d = Drivetrain(2200f, 7000f)
        var kmh = 0f
        val upAt = ArrayList<Float>()
        var lastShifts = 0
        var sawCut = false
        while (kmh < 70f) {
            val st = d.step(dt, kmh, 0.5f)
            if (st.shifts != lastShifts) { upAt += kmh; lastShifts = st.shifts }
            if (st.shifting > 0.5f) sawCut = true
            kmh += 3f * 3.6f * dt                  // 3 m/s²
        }
        assertEquals(2, upAt.size)
        assertEquals(30f, upAt[0], 0.5f)
        assertEquals(60f, upAt[1], 0.5f)
        assertTrue("torque cut through the shift", sawCut)
        // lift off at speed: engine braking, and the lift-off counts (for pops)
        var st = d.step(dt, 70f, 0f)
        repeat(100) { st = d.step(dt, 70f, 0f) }
        assertTrue("overrun ${st.overrun}", (st.overrun ?: 0f) > 0.2f)
        // slow right down: downshifts
        var k = 70f
        while (k > 5f) { st = d.step(dt, k, 0f); k -= 4f * 3.6f * dt }
        assertTrue("downshifted (${st.downshifts})", st.downshifts >= 2)
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)

    @Test fun launchAndReversePlay() {
        if (!File(assets, "effects/launch_ev.wav").exists()) return
        val v = SoundVoice(48000, open = opener()).apply { sound = EngineSound.STARSHIP; volume = 0f }
        val buf = FloatArray(480)
        fun block(speed: Float, pedal: Float, rev: Boolean): Double {
            v.drive(pedal, rev)
            v.set(EngineState(900f, pedal, -1, pedal > 0.04f, 0, false, speedKmh = speed))
            v.render(buf)
            return rms(buf)
        }
        // volume 0 silences the main sound and the effects, so check with the effects' own level
        v.volume = 1f
        repeat(1000) { block(0f, 0f, false) }      // past the start-up chime
        val quiet = (0 until 20).maxOf { block(0f, 0f, false) }
        // stab the pedal from rest: launch
        var launch = 0.0
        for (i in 0 until 60) launch = maxOf(launch, block(2f, minOf(1f, i * 0.2f), false))
        assertTrue("launch heard ($launch vs $quiet)", launch > quiet + 0.05)
        // reverse: the loop comes in
        repeat(400) { block(0f, 0f, false) }      // launch rings out
        var rev = 0.0
        for (i in 0 until 200) rev = maxOf(rev, block(3f, 0f, true))
        assertTrue("reverse heard ($rev)", rev > 0.03)
    }

    /**
     * Recorded engines within ~8 dB of the synthesized V8 (which runs close to a full-scale
     * square wave; matching it outright would mean limiting the recordings into distortion).
     */
    @Test fun recordedEnginesAsLoudAsTheV8() {
        if (!File(assets, "loopsets/bmw_m3_gtr_int/set.json").exists()) return
        fun loudness(sound: EngineSound): Double {
            val v = SoundVoice(48000, open = opener()).apply { this.sound = sound; volume = 0.8f }
            val m = EngineModel(idleRpm = sound.idleRpm, redline = sound.redline, gearshifts = true)
            val d = Drivetrain(sound.idleRpm, sound.redline)
            val buf = FloatArray(480)
            var e = 0.0
            for (i in 0 until 800) {
                val speed = i * 0.03f
                var st = m.update(dt, speed, 2.5f, null, 0.7f)
                if (sound.kind == EngineSound.Kind.RECORDED) st = d.step(dt, speed * 3.6f, 0.7f)
                v.drive(0.7f, false); v.set(st); v.render(buf)
                if (i >= 200) e += buf.sumOf { (it * it).toDouble() }
            }
            return sqrt(e / (600 * 480))
        }
        val v8 = loudness(EngineSound.V8)
        for (s in listOf(EngineSound.BMW_GTR, EngineSound.AUDI_R8, EngineSound.IONIQ_N)) {
            val l = loudness(s)
            println("$s rms %.3f vs V8 %.3f (%+.1f dB)".format(l, v8, 20 * kotlin.math.log10(l / v8)))
            assertTrue("$s too quiet: $l vs V8 $v8", l > v8 * 0.4)
        }
    }

    /** Every gearbox shift of an engine sound plays the shift sound. */
    @Test fun gearshiftsPlayTheShiftSound() {
        if (!File(assets, "effects/shift_cin_up.wav").exists()) return
        fun energyAfter(shift: Boolean): Double {
            val v = SoundVoice(48000, open = opener()).apply { sound = EngineSound.V8; volume = 0.3f }
            val buf = FloatArray(480)
            fun block(shifts: Int): Double {
                v.drive(0.5f, false)
                v.set(EngineState(3000f, 0.5f, 2, true, shifts, false, speedKmh = 40f))
                v.render(buf)
                return buf.sumOf { (it * it).toDouble() }
            }
            repeat(1000) { block(0) }                // past the start-up chime
            return (0 until 50).sumOf { block(if (shift) 1 else 0) }   // the next 0.5 s (the hit lands ~0.12 s in)
        }
        val with = energyAfter(true)
        val without = energyAfter(false)
        assertTrue("shift sound heard (${"%.1f".format(10 * kotlin.math.log10(with / without))} dB)", with > without * 1.5)
    }
}
