package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestDriveTest {
    private val dt = 256f / 48000f                      // one audio block, as the app steps it

    @Test fun drivesUpTo100AndBackToAStop() {
        val d = TestDrive()
        val stages = ArrayList<TestDrive.Stage>()
        var t = 0f
        var top = 0f
        val box = Dct(900f, 7000f)
        val gears = ArrayList<Int>()
        var cuts = 0
        var wasCut = false
        while (d.step(dt)) {
            t += dt
            top = maxOf(top, d.kmh)
            if (stages.lastOrNull() != d.stage) stages += d.stage
            val st = box.step(dt, d.kmh, d.pedal, d.motorRpm)
            val g = st.gear
            if (st.cut == true && !wasCut) cuts++
            wasCut = st.cut == true
            if (gears.lastOrNull() != g) gears += g
            assertTrue("runs away (${t}s)", t < 60f)
        }
        println("test drive: %.1f s, top %.0f km/h, stages %s, gears %s, %d limiter cuts".format(t, top, stages, gears, cuts))
        assertTrue("bounces off the rev limiter on the hard pull ($cuts cuts)", cuts >= 5)
        assertEquals(TestDrive.Stage.entries.dropLast(1), stages)
        assertTrue("reaches 100 km/h ($top)", top >= 100f && top < 106f)
        assertEquals(0f, d.speed, 0f)
        assertTrue("about half a minute (${t}s)", t in 15f..40f)
        // the gearbox shifts up pulling away, kicks down to 1st for the floored pull (2nd runs to
        // 106 km/h, so 40-100 is 1st, the limiter, then 2nd), and comes back down to 1st
        assertTrue("shifts up ($gears)", gears.max() >= 2 && gears.indexOf(1) < gears.lastIndexOf(2))
        assertEquals(1, gears.last())
    }

    @Test fun pedalMovesLikeAFoot() {
        val d = TestDrive()
        var maxStep = 0f
        var last = 0f
        while (d.step(dt)) { maxStep = maxOf(maxStep, kotlin.math.abs(d.pedal - last)); last = d.pedal }
        assertTrue("pedal jumps $maxStep per block", maxStep <= TestDrive.PEDAL_PER_SEC * dt + 1e-6f)
    }
}
