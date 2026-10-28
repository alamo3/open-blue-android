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

class MountMemoryTest {
    private val flat = floatArrayOf(0f, 0f, 9.81f)

    /** Teaches a fusion that forward is +x (driving at 36-50 km/h), returns what it would save. */
    private fun learned(): String {
        val f = MotionFusion()
        var t = 0L
        var v = 10f
        repeat(800) { i ->
            val a = if ((i / 200) % 2 == 0) 2.5f else -2f
            v = maxOf(0f, v + a * 0.01f)
            f.onAccel(floatArrayOf(a, 0f, 0f), flat, t)
            t += 10_000_000L
            if (i % 100 == 0) f.onGps(v, i * 10L)
        }
        return f.exportMount() ?: error("nothing learned")
    }

    @Test fun sameAngleRestoresAtOnce() {
        val f = MotionFusion()
        f.restoreMount(learned())
        var t = 0L
        repeat(80) { f.onAccel(floatArrayOf(-2f, 0f, 0f), flat, t); t += 10_000_000L }
        assertTrue("confidence ${f.confidence}", f.confidence > 0.2f)
        // Braking along -x reads as negative straight away, with no GPS at all.
        assertTrue("accel ${f.accel}", f.accel < -1f)
    }

    @Test fun differentAngleIsIgnored() {
        val f = MotionFusion()
        f.restoreMount(learned())
        val tilted = floatArrayOf(0f, 4f, 8.96f) // about 24° off
        var t = 0L
        repeat(80) { f.onAccel(floatArrayOf(0f, 0f, 0f), tilted, t); t += 10_000_000L }
        assertEquals(0f, f.confidence, 0f)
    }
}

class ReversingTest {
    private val flat = floatArrayOf(0f, 0f, 9.81f)
    private var t = 0L
    private var gpsMs = 0L

    /** [steps] samples 10 ms apart of push [a] along +x (forward), GPS once a second. */
    private fun drive(f: MotionFusion, steps: Int, a: Float, speedAt: (Int) -> Float): Boolean {
        var sawReversing = false
        repeat(steps) { i ->
            f.onAccel(floatArrayOf(a, 0f, 0f), flat, t)
            t += 10_000_000L
            gpsMs += 10
            if (i % 100 == 0) f.onGps(speedAt(i), gpsMs)
            if (f.reversing) sawReversing = true
        }
        return sawReversing
    }

    private fun calibrated(): MotionFusion {
        val f = MotionFusion()
        f.restoreMount("0,0,1,8,0,0,8") // flat, forward = +x
        drive(f, 200, 0f) { 0f } // parked
        return f
    }

    @Test fun reversingOutThenDrivingOff() {
        val f = calibrated()
        // Reverse out: pushed backwards, GPS speed rising (GPS has no direction).
        assertTrue("reversing detected", drive(f, 200, -1.5f) { it * 0.015f })
        // Brake to a stop: that forward push must not count as accelerating.
        drive(f, 200, 1.5f) { 3f - it * 0.015f }
        assertTrue("still reversing while braking", f.reversing)
        drive(f, 200, 0f) { 0f }
        assertTrue("stop clears it", !f.reversing)
        // Drive off forwards, up past 15 km/h.
        drive(f, 600, 2.5f) { it * 0.025f }
        assertTrue(!f.reversing)
        assertTrue("forward still reads forward: ${f.accel}", f.accel > 1.5f)
        assertTrue("axis kept: ${f.confidence}", f.confidence > 0.5f)
    }

    @Test fun phoneTurnedRoundOnAFlatPadIsCaught() {
        val f = calibrated() // remembers forward = +x
        // But today the phone lies the other way: driving forwards pushes it along -x.
        drive(f, 800, -2.5f) { it * 0.025f }
        assertTrue("not stuck thinking it's reversing", !f.reversing)
        assertTrue("relearned: forward push reads ${f.accel}", f.accel > 1.5f)
        // And braking now reads as braking.
        drive(f, 150, 2f) { 20f - it * 0.02f }
        assertTrue("braking reads ${f.accel}", f.accel < -1f)
    }

    @Test fun reversingNeverTeachesTheWrongWay() {
        val f = calibrated()
        repeat(5) {
            drive(f, 200, -1.5f) { it * 0.015f }
            drive(f, 200, 1.5f) { 3f - it * 0.015f }
            drive(f, 200, 0f) { 0f }
        }
        drive(f, 100, 2f) { it * 0.02f }
        assertTrue("accel ${f.accel}", f.accel > 1f)
        assertTrue(!f.reversing)
    }
}
