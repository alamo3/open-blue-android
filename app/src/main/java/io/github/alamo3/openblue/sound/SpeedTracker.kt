package io.github.alamo3.openblue.sound

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The car's speed between its readings, for a smooth sound. The car reports speed in whole
 * km/h, about 30 times a second: held, or even smoothed, that steps the revs at every reading.
 * This tracks speed and acceleration with an alpha-beta filter (each reading nudges both by how
 * far it is from the prediction), predicts between readings, and glides the result over
 * [glideSec] -- about 25x smoother than smoothing the readings, with no more lag.
 * (virtual-car-sound's sc_player does the same.)
 */
class SpeedTracker(
    private val alpha: Float = 0.1f,
    private val beta: Float = 0.005f,
    private val glideSec: Float = 0.03f,
) {
    private var x = -1f          // speed estimate at the last reading, km/h
    private var v = 0f           // its rate of change, km/h per second
    private var atMs = 0L
    private var out = 0f
    private var stopped = true

    /** Forward acceleration, m/s², from the tracked speed. */
    val accel: Float get() = v / 3.6f

    /** A reading from the car: [kmh] (whole km/h) at [timeMs]. */
    fun reading(kmh: Float, timeMs: Long) {
        val d = (timeMs - atMs) / 1000f
        if (x < 0f || d > 1f) {                       // the first reading, or after a gap: start over
            x = kmh; v = 0f; atMs = timeMs; out = kmh; stopped = kmh < 0.5f
            return
        }
        if (d <= 0f) return
        val predicted = x + v * d
        val r = kmh - predicted
        x = predicted + alpha * r
        v += beta * r / d
        atMs = timeMs
        stopped = kmh < 0.5f && x < 1.5f                // standing still: exactly zero, not drifting
        if (stopped) { x = 0f; v = 0f }
    }

    /** The speed now (km/h), stepped once per audio block of [dt] seconds. */
    fun next(nowMs: Long, dt: Float): Float {
        if (x < 0f) return 0f
        val ahead = ((nowMs - atMs) / 1000f).coerceIn(0f, 0.25f)
        val est = if (stopped) 0f else max(0f, x + v * ahead)
        out += (est - out) * (1f - exp(-dt / glideSec))
        if (stopped && out < 0.05f) out = 0f          // settled at a standstill
        return max(0f, out)
    }

    fun reset() { x = -1f; v = 0f; out = 0f; stopped = true }
}
