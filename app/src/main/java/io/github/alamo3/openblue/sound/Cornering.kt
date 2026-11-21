package io.github.alamo3.openblue.sound

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How hard the car is cornering, from the car's speed and the phone's gyroscope: lateral
 * acceleration = speed x yaw rate. A phone's accelerometer is easily fooled by its mount (tilt
 * reads as gravity, every bump as force), but the car turning rotates everything in it, mount
 * and all, so the gyroscope sees the turn however the phone sits. Its rotation is projected onto
 * the vertical (the gravity sensor says which way is up), so any mounting angle works, and
 * smoothed: bumps and mount wobble are quick and small, a corner is slow and sustained.
 *
 * Sign: positive yaw = turning left (anticlockwise seen from above), so positive
 * [lateralAccel] = cornering left.
 */
class Cornering(private val smoothSec: Float = SMOOTH_SEC) {
    companion object {
        const val SMOOTH_SEC = 0.25f
        /** Below this yaw rate (rad/s) it's sensor drift or a wobble, not a corner. */
        const val DEADBAND = 0.02f
    }

    /** Smoothed yaw rate, rad/s (positive = turning left). */
    @Volatile var yawRate = 0f
        private set
    private var lastNs = 0L

    /** One gyroscope reading ([gyro], rad/s, device axes) with the latest [gravity] reading. */
    fun onGyro(gyro: FloatArray, gravity: FloatArray, timestampNs: Long) {
        val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (g < 1f) return                                       // no gravity reading yet
        // rotation about "up" (the gravity sensor reports the vector pointing up, away from the ground)
        var yaw = (gyro[0] * gravity[0] + gyro[1] * gravity[1] + gyro[2] * gravity[2]) / g
        if (kotlin.math.abs(yaw) < DEADBAND) yaw = 0f
        val dt = if (lastNs == 0L) 0f else ((timestampNs - lastNs) / 1e9f).coerceIn(0f, 0.2f)
        lastNs = timestampNs
        yawRate += (yaw - yawRate) * (1f - exp(-dt / smoothSec))
    }

    /** Lateral acceleration (m/s², positive = cornering left) at [speedMs]. */
    fun lateralAccel(speedMs: Float, yaw: Float = yawRate) = speedMs * yaw
}
