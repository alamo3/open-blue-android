package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

class SpeedTrackerTest {
    // a launch easing off from 3 m/s², then braking at 1.5 m/s² to a stop
    private fun trueKmh(t: Float): Float {
        if (t < 1f) return 0f
        var v = 3.6f * 3f * 6f * (1f - exp(-(t - 1f) / 6f))
        if (t > 10f) v -= 3.6f * 1.5f * (t - 10f)
        return max(0f, v)
    }

    @Test fun smoothBetweenWholeKmhReadings() {
        val tr = SpeedTracker()
        val blk = 256f / 48000f
        var t = 0f; var nextReading = 0f
        var prev = 0f; var prevTrue = 0f
        var worst = 0f; var errSum = 0f; var errN = 0
        var endSpeed = -1f
        while (t < 28f) {
            if (t >= nextReading) { tr.reading(trueKmh(t).roundToInt().toFloat(), (t * 1000).toLong()); nextReading += 0.035f }
            val s = tr.next((t * 1000).toLong(), blk)
            val tv = trueKmh(t)
            if (t in 2f..13f) {
                worst = maxOf(worst, abs((s - prev) - (tv - prevTrue)))     // a step the car didn't make
                errSum += s - tv; errN++
            }
            prev = s; prevTrue = tv
            endSpeed = s
            t += blk
        }
        println("speed tracker: worst step %.3f km/h, mean error %+.2f km/h".format(worst, errSum / errN))
        assertTrue("steps of $worst km/h", worst < 0.06f)
        assertEquals("tracks the speed", 0f, errSum / errN, 0.4f)
        assertEquals("stops at zero", 0f, endSpeed, 0.01f)
    }
}
