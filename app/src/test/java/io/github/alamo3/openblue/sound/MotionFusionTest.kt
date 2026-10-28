package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFusionTest {
    /** Phone lying flat, screen up, top of the phone pointing to the car's right (x = forward). */
    private val gravity = floatArrayOf(0f, 0f, 9.81f)

    @Test fun learnsForwardAndTracksSpeed() {
        val f = MotionFusion()
        var t = 0L
        var v = 0f
        var gpsT = 0L
        // Accelerate along the phone's -x axis (so forward is -x), with GPS once a second.
        for (step in 0 until 1000) {
            val a = if (step < 600) 3f else -2f
            v = maxOf(0f, v + a * 0.01f)
            // Add sideways noise so it has to separate forward from cornering.
            val side = if ((step / 50) % 2 == 0) 0.6f else -0.6f
            f.onAccel(floatArrayOf(-a, side, 0f), gravity, t)
            t += 10_000_000L
            if (step % 100 == 0) {
                f.onGps(v, gpsT)
            }
            gpsT += 10
        }
        assertTrue("confidence ${f.confidence}", f.confidence > 0.2f)
        // Last segment was braking: forward accel should read negative.
        assertTrue("accel ${f.accel}", f.accel < -1f)
        assertEquals(v, f.speed, 1.5f)
    }

    @Test fun phoneMovedResetsCalibration() {
        val f = MotionFusion()
        var t = 0L
        repeat(600) { i ->
            f.onAccel(floatArrayOf(2f, 0f, 0f), gravity, t)
            t += 10_000_000L
            if (i % 100 == 0) f.onGps(i * 0.02f, i * 10L)
        }
        assertTrue(f.confidence > 0f)
        f.onAccel(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 9.81f, 0f), t)
        assertEquals(0f, f.confidence, 0f)
    }
}
