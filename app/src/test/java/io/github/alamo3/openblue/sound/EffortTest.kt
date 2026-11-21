package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class EffortTest {
    private val dt = 0.02f

    /**
     * Drives [seconds] at a steady [pedal] on a slope (rise per unit distance, e.g. 0.06 = 6 %),
     * with a car that pulls [carStrength] x what the model expects; returns the effective pedal.
     */
    private fun drive(e: Effort, pedal: Float, slope: Float, seconds: Float, carStrength: Float = 1f, startKmh: Float = 60f): Float {
        var v = startKmh / 3.6f
        var out = pedal
        var t = 0f
        while (t < seconds) {
            // hold the speed roughly steady: the pedal is fixed, the slope and drag do the rest
            val a = carStrength * Effort.expectedPush(pedal, v) - Effort.drag(v) - 9.81f * slope
            v = max(1f, v + a * dt)
            out = e.step(dt, pedal, v, a)
            t += dt
        }
        return out
    }

    @Test fun flatRoadLeavesThePedalAlone() {
        val e = Effort()
        val p = drive(e, 0.3f, 0f, 120f)
        assertEquals(0.3f, p, 0.01f)
    }

    @Test fun uphillIsHeavierDownhillLighter() {
        val e = Effort()
        drive(e, 0.3f, 0f, 120f)                                  // learn the car on the flat
        val up = drive(e, 0.3f, 0.06f, 8f)                        // a 6 % hill
        println("6 %% uphill: pedal 0.30 -> %.2f (strain %.2f)".format(up, e.strain))
        assertTrue("heavier uphill ($up)", up > 0.33f)
        val e2 = Effort()
        drive(e2, 0.3f, 0f, 120f)
        val down = drive(e2, 0.3f, -0.05f, 8f)
        println("5 %% downhill: pedal 0.30 -> %.2f (strain %.2f)".format(down, e2.strain))
        assertTrue("lighter downhill ($down)", down < 0.28f)
    }

    /** A car that simply pulls harder (or softer) than the model is learned, not taken for a hill. */
    @Test fun learnsTheCarsOwnResponse() {
        val e = Effort()
        val p = drive(e, 0.3f, 0f, 400f, carStrength = 1.3f)
        assertEquals("a stronger car on the flat is still neutral", 0.3f, p, 0.02f)
    }

    @Test fun offThePedalOrCrawlingItPassesThrough() {
        val e = Effort()
        assertEquals(0.05f, drive(e, 0.05f, 0.08f, 10f), 1e-4f)            // light pedal (regen zone)
        assertEquals(0.5f, drive(Effort(), 0.5f, 0.08f, 3f, startKmh = 8f), 0.005f) // crawling (and just past it, still unlearned)
    }
}
