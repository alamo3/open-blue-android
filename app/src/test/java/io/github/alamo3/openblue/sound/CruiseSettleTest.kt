package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class CruiseSettleTest {
    private val dt = 256f / 48000f

    @Test fun settlesAtASteadyCruiseAndComesBackOnAPress() {
        val c = CruiseSettle()
        var t = 0f
        while (t < 3f) { c.step(dt, true, 90f, 0f, 0.25f, false); t += dt }
        assertFalse("not in the first seconds", c.settled)
        while (t < 20f) { c.step(dt, true, 90f, 0f, 0.25f, false); t += dt }
        assertEquals("about 8 dB down", -8.0, 20 * log10(c.gain.toDouble()), 0.5)
        var back = 0f
        while (back < 0.4f) { c.step(dt, true, 90f, 1.5f, 0.6f, false); back += dt }
        assertTrue("back within 0.4 s of a press (${c.gain})", c.gain > 0.9f)
    }

    @Test fun settlesAtIdleAndComesBackOnThePedal() {
        val c = CruiseSettle()
        repeat(2500) { c.step(dt, true, 0f, 0f, 0f, false) }            // 13 s stopped
        assertEquals("about 9 dB down at idle", -9.0, 20 * log10(c.gain.toDouble()), 0.5)
        var t = 0f
        while (t < 0.3f) { c.step(dt, true, 0f, 0.5f, 0.3f, false); t += dt }
        assertTrue("back on the pedal (${c.gain})", c.gain > 0.9f)
    }

    @Test fun neverSettlesCrawlingOrWhenOff() {
        val town = CruiseSettle(); val off = CruiseSettle()
        repeat(4000) { town.step(dt, true, 15f, 0f, 0.2f, false); off.step(dt, false, 90f, 0f, 0.25f, false) }
        assertEquals(1f, town.gain, 1e-3f)
        assertEquals(1f, off.gain, 1e-3f)
    }
}
