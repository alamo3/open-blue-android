package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class DelayDetectorTest {
    private val rate = 48000

    /** A recording with road-ish noise and each click arriving [delayMs] after it was played. */
    private fun recording(delayMs: Int, clickAtFrames: List<Int>, loud: Boolean = true): ShortArray {
        val rnd = Random(3)
        val pcm = ShortArray(rate * 4) { (rnd.nextFloat() * 2f - 1f).times(300f).toInt().toShort() }
        val n = (rate * 0.006).toInt()
        for (c in clickAtFrames) {
            val start = c + delayMs * rate / 1000
            for (i in 0 until n) {
                val env = sin(PI * i / n)
                val v = (if (loud) 9000 else 100) * env * sin(2 * PI * 2400 * i / rate)
                pcm[start + i] = (pcm[start + i] + v.toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        return pcm
    }

    @Test fun findsTheDelay() {
        val clicks = listOf(rate * 7 / 10, rate * 17 / 10, rate * 27 / 10)
        val refNs = 5_000_000_000L
        val clickNs = clicks.map { refNs + it * 1_000_000_000L / rate }
        val ms = DelayDetector.median(recording(180, clicks), rate * 4, rate, 0L, refNs, clickNs)
        assertTrue("got $ms", ms != null)
        assertEquals(180f, ms!!.toFloat(), 3f)
    }

    @Test fun quietClicksAreNotGuessed() {
        val clicks = listOf(rate * 7 / 10, rate * 17 / 10, rate * 27 / 10)
        val clickNs = clicks.map { it * 1_000_000_000L / rate }
        assertEquals(null, DelayDetector.median(recording(180, clicks, loud = false), rate * 4, rate, 0L, 0L, clickNs))
    }
}
