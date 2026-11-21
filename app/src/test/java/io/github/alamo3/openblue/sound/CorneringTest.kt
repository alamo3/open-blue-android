package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CorneringTest {
    /** Feeds [seconds] of a steady gyro reading at 100 Hz; returns the smoothed yaw. */
    private fun settle(c: Cornering, gyro: FloatArray, gravity: FloatArray, seconds: Float = 2f): Float {
        var t = 0L
        repeat((seconds * 100).toInt()) { t += 10_000_000L; c.onGyro(gyro, gravity, t) }
        return c.yawRate
    }

    @Test fun yawRateWhateverWayThePhoneIsMounted() {
        val yaw = 0.3f                                            // turning left at 0.3 rad/s
        // flat on its back: up is +z
        assertEquals(yaw, settle(Cornering(), floatArrayOf(0f, 0f, yaw), floatArrayOf(0f, 0f, 9.81f)), 0.01f)
        // upright in a vent mount: up is +y
        assertEquals(yaw, settle(Cornering(), floatArrayOf(0f, yaw, 0f), floatArrayOf(0f, 9.81f, 0f)), 0.01f)
        // tilted back 30°: up is between +y and +z, and so is the turn's axis
        val a = Math.toRadians(30.0)
        val up = floatArrayOf(0f, cos(a).toFloat(), sin(a).toFloat())
        assertEquals(yaw, settle(Cornering(), floatArrayOf(0f, up[1] * yaw, up[2] * yaw), floatArrayOf(0f, up[1] * 9.81f, up[2] * 9.81f)), 0.01f)
    }

    @Test fun mountWobbleIsntACorner() {
        // pitching on its mount (rotation about a horizontal axis) and a wobble back and forth
        val c = Cornering()
        val up = floatArrayOf(0f, 9.81f, 0f)
        var t = 0L
        var worst = 0f
        repeat(300) { i ->
            t += 10_000_000L
            val wobble = if (i % 2 == 0) 0.5f else -0.5f          // fast, back and forth about "up"
            c.onGyro(floatArrayOf(1.5f, wobble, 0.8f), up, t)
            if (i > 50) worst = maxOf(worst, kotlin.math.abs(c.yawRate))
        }
        assertTrue("wobble reads as $worst rad/s", worst < 0.05f)
    }

    @Test fun lateralAccelIsSpeedTimesYaw() {
        val c = Cornering()
        settle(c, floatArrayOf(0f, 0f, 0.2f), floatArrayOf(0f, 0f, 9.81f))
        assertEquals(20f * 0.2f, c.lateralAccel(20f), 0.1f)     // 72 km/h, 0.2 rad/s: 4 m/s², left
        settle(c, floatArrayOf(0f, 0f, -0.2f), floatArrayOf(0f, 0f, 9.81f))
        assertTrue("right turn is negative", c.lateralAccel(20f) < -3.5f)
    }

    /** Cornering left, the sound leans right (the outside of the turn), at the same overall level. */
    @Test fun soundLeansToTheOutside() {
        fun energies(lateral: Float): Pair<Double, Double> {
            val v = SoundVoice(48000).apply { sound = EngineSound.STARSHIP; volume = 0.8f }
            val buf = FloatArray(960)
            var l = 0.0; var r = 0.0
            repeat(400) { i ->
                v.drive(0.5f, false)
                v.corner(lateral)
                v.set(EngineState(3000f, 0.5f, -1, true, 0, false, speedKmh = 60f))
                v.renderStereo(buf)
                if (i > 200) for (n in 0 until 480) { l += buf[2 * n] * buf[2 * n]; r += buf[2 * n + 1] * buf[2 * n + 1] }
            }
            return l to r
        }
        val (sl, sr) = energies(0f)
        val (ll, lr) = energies(5.9f)                              // 0.6 g to the left
        val (rl, rr) = energies(-5.9f)
        println("straight %.0f/%.0f, left turn %.0f/%.0f, right turn %.0f/%.0f".format(sl, sr, ll, lr, rl, rr))
        assertTrue("left turn leans right", lr > ll * 1.4)
        assertTrue("right turn leans left", rl > rr * 1.4)
        assertEquals("same overall level", sl + sr, ll + lr, (sl + sr) * 0.1)
    }

    @Test fun testDriveCornersBothWays() {
        val d = TestDrive()
        var left = 0f; var right = 0f
        while (d.step(256f / 48000f)) { left = maxOf(left, d.yawRate * d.speed); right = minOf(right, d.yawRate * d.speed) }
        println("test drive cornering: up to %.1f m/s² left, %.1f m/s² right".format(left, -right))
        assertTrue("a left bend", left > 1.5f)
        assertTrue("a right bend", right < -3f)
    }
}
