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
        while (d.step(dt)) {
            t += dt
            top = maxOf(top, d.kmh)
            if (stages.lastOrNull() != d.stage) stages += d.stage
            val g = box.step(dt, d.kmh, d.pedal, d.motorRpm).gear
            if (gears.lastOrNull() != g) gears += g
            assertTrue("runs away (${t}s)", t < 60f)
        }
        println("test drive: %.1f s, top %.0f km/h, stages %s, gears %s".format(t, top, stages, gears))
        assertEquals(TestDrive.Stage.entries.dropLast(1), stages)
        assertTrue("reaches 100 km/h ($top)", top >= 100f && top < 106f)
        assertEquals(0f, d.speed, 0f)
        assertTrue("about half a minute (${t}s)", t in 15f..40f)
        // the gearbox goes up through the gears, kicks down for the hard pull, and back to 1st
        assertTrue("shifts up ($gears)", gears.max() >= 3)
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
