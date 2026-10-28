package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EngineModelTest {
    private val dt = 0.01f

    @Test fun idlesWhenStill() {
        val m = EngineModel(idleRpm = 900f)
        var st = m.update(dt, 0f, 0f)
        repeat(200) { st = m.update(dt, 0f, 0f) }
        assertEquals(900f, st.rpm, 1f)
        assertTrue(!st.engaged)
    }

    @Test fun hardPullShiftsUpAndRevsHigh() {
        val m = EngineModel(gearshifts = true)
        var v = 0f
        var maxRpm = 0f
        var st = m.update(dt, v, 0f)
        repeat(1000) {
            st = m.update(dt, v, 4.5f)
            v += 4.5f * dt
            maxRpm = maxOf(maxRpm, st.rpm)
        }
        assertTrue("reached ${v * 3.6f} km/h in gear ${st.gear}", st.gear >= 3)
        assertTrue(st.shifts >= 2)
        assertTrue("revs ran high: $maxRpm", maxRpm > 6000f)
    }

    @Test fun liftingOffFallsToIdleAndCrackles() {
        val m = EngineModel(idleRpm = 900f)
        var v = 10f
        var st = m.update(dt, v, 0f)
        repeat(300) { st = m.update(dt, v, 4f); v += 0.04f }
        val lifts = st.liftOffs
        repeat(400) { st = m.update(dt, v, -0.5f) }
        assertEquals(900f, st.rpm, 1f)
        assertEquals(lifts + 1, st.liftOffs)
    }

    @Test fun holdRevsKeepsGearRpmWhileCruising() {
        val m = EngineModel(holdRevs = true, gearshifts = true)
        var st = m.update(dt, 27.8f, 0f) // 100 km/h
        repeat(300) { st = m.update(dt, 27.8f, 0f) }
        assertTrue("cruising revs ${st.rpm}", st.rpm in 1300f..3100f)
    }

    @Test fun flooringItAtSpeedKicksDown() {
        val m = EngineModel(gearshifts = true)
        var st = m.update(dt, 27.8f, 0f)
        repeat(100) { st = m.update(dt, 27.8f, 0f) }
        val cruiseGear = st.gear
        repeat(30) { st = m.update(dt, 27.8f, 3.8f) }
        assertTrue("gear ${st.gear} vs $cruiseGear", st.gear < cruiseGear)
    }

    @Test fun directDriveRevsRiseSmoothlyWithoutShifts() {
        val m = EngineModel()
        var v = 0f
        var st = m.update(dt, v, 0f)
        var prev = 0f
        var drops = 0
        repeat(800) {
            st = m.update(dt, v, 3f)
            v += 3f * dt
            if (it > 50 && st.rpm < prev - 1f) drops++
            prev = st.rpm
        }
        assertEquals(0, st.shifts)
        assertEquals(-1, st.gear)
        assertEquals("revs never dip mid-pull", 0, drops)
        assertTrue(st.rpm > 4000f)
    }

    @Test fun evSoundsStayInRange() {
        for (kind in listOf(EngineSound.STARSHIP, EngineSound.TURBINE, EngineSound.ELECTRIC)) {
            val voice = SoundVoice(48000).apply { sound = kind }
            val m = EngineModel()
            val buf = FloatArray(480)
            var peak = 0f
            for (i in 0 until 400) {
                voice.set(m.update(dt, i * 0.1f, if (i < 250) 3f else -1f))
                voice.render(buf)
                buf.forEach { peak = maxOf(peak, abs(it)); assertTrue(!it.isNaN()) }
            }
            assertTrue("$kind peak $peak", peak in 0.05f..1f)
        }
    }

    @Test fun revSliderRevsInNeutral() {
        val m = EngineModel(idleRpm = 900f, redline = 6900f)
        var st = m.update(dt, 0f, 0f, 1f)
        repeat(200) { st = m.update(dt, 0f, 0f, 1f) }
        assertEquals(6900f, st.rpm, 1f)
        assertTrue(st.limiter)
        assertEquals(0, st.gear)
    }

    @Test fun voiceStaysInRange() {
        val v = EngineVoice(48000)
        val m = EngineModel()
        val buf = FloatArray(480)
        var peak = 0f
        for (i in 0 until 300) {
            v.set(m.update(dt, i * 0.1f, if (i < 200) 4f else -1f))
            v.render(buf)
            buf.forEach { peak = maxOf(peak, abs(it)) ; assertTrue(!it.isNaN()) }
        }
        assertTrue(peak in 0.05f..1f)
    }
}
