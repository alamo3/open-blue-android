package io.github.alamo3.openblue.sound

import kotlin.math.abs
import kotlin.math.sqrt

/** Finds played clicks in a microphone recording and works out how late each one was heard. */
object DelayDetector {
    /**
     * [pcm] was recorded at [rate]; frame [refFrame] was captured at [refNs] (System.nanoTime
     * clock). [clickNs] are the times each click was handed to the speaker. Returns the median
     * delay in ms, or null if fewer than two clicks stood out from the background.
     */
    fun median(pcm: ShortArray, frames: Int, rate: Int, refFrame: Long, refNs: Long, clickNs: List<Long>): Int? {
        fun frameAt(ns: Long) = refFrame + (ns - refNs) * rate / 1_000_000_000L
        // High-pass (first difference) so engine rumble and road noise matter less than the click.
        val hp = FloatArray(frames)
        for (i in 1 until frames) hp[i] = (pcm[i] - pcm[i - 1]).toFloat()
        val delays = clickNs.mapNotNull { c ->
            val start = frameAt(c).toInt()
            if (start < 0 || start >= frames) return@mapNotNull null
            val end = minOf(frames, start + rate * 7 / 10)
            // Background level from the 100 ms before the click was played.
            val bgFrom = maxOf(1, start - rate / 10)
            var sum = 0.0
            for (i in bgFrom until start) sum += hp[i] * hp[i].toDouble()
            val bg = sqrt(sum / maxOf(1, start - bgFrom)).toFloat()
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, abs(hp[i]))
            if (peak < bg * 6f || peak < 200f) return@mapNotNull null
            val threshold = maxOf(bg * 4f, peak * 0.35f)
            val onset = (start until end).firstOrNull { abs(hp[it]) >= threshold } ?: return@mapNotNull null
            ((onset - start) * 1000L / rate).toInt()
        }
        if (delays.size < 2) return null
        return delays.sorted()[delays.size / 2]
    }
}
