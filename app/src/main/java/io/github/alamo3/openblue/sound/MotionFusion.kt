package io.github.alamo3.openblue.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Turns the phone's accelerometer and GPS into the car's forward acceleration and speed.
 *
 * The phone can sit in any orientation, as long as it stays put. Which way is "forward" is
 * learned while driving: horizontal acceleration that lines up with GPS speeding up is forward.
 * Speed comes from adding up that acceleration, pulled towards each GPS fix so it can't drift.
 */
class MotionFusion {
    /** Forward acceleration in m/s², smoothed. */
    var accel = 0f
        private set
    /** Speed in m/s. */
    var speed = 0f
        private set
    /** How sure we are which way is forward, 0..1. */
    val confidence: Float get() = (evidence / 8f).coerceIn(0f, 1f)

    private val gravity = FloatArray(3)
    private var haveGravity = false
    private val forward = FloatArray(3)
    private var evidence = 0f

    // Horizontal acceleration since the last GPS fix, to compare with GPS's change in speed.
    private val sinceFix = FloatArray(3)
    private var sinceFixCount = 0
    private var lastFixSpeed = -1f
    private var lastFixTime = 0L
    private var lastGpsAccel = 0f
    private var lastSensorTime = 0L
    private var stillFor = 0f

    // A remembered mount from an earlier drive, checked once gravity has settled.
    private var pendingMount: FloatArray? = null
    private var samples = 0

    /**
     * The learned mount (how the phone is tilted and which way is forward), to remember for next
     * time; null until it's known well enough.
     */
    fun exportMount(): String? {
        if (!haveGravity || evidence < 2f) return null
        return (gravity.toList() + forward.toList() + evidence).joinToString(",")
    }

    /**
     * Offers a mount from [exportMount]. It's used if the phone turns out to sit at the same angle
     * (within about 10°), so the sound follows the car from the start; otherwise it's ignored.
     */
    fun restoreMount(saved: String?) {
        val v = saved?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return
        if (v.size == 7) pendingMount = v.toFloatArray()
    }

    /** Phone moved in its mount (gravity swung): forget which way is forward. */
    private fun resetAxis() {
        forward.fill(0f)
        evidence = 0f
    }

    /**
     * One accelerometer sample. [linear] is acceleration without gravity (TYPE_LINEAR_ACCELERATION)
     * and [grav] is gravity (TYPE_GRAVITY), both in the phone's frame; [timeNs] from the sensor.
     */
    fun onAccel(linear: FloatArray, grav: FloatArray, timeNs: Long) {
        val dt = if (lastSensorTime == 0L) 0f else ((timeNs - lastSensorTime) / 1e9f).coerceIn(0f, 0.1f)
        lastSensorTime = timeNs

        val gl = norm(grav)
        if (gl < 1f) return
        val g = floatArrayOf(grav[0] / gl, grav[1] / gl, grav[2] / gl)
        if (haveGravity && dot(g, gravity) < 0.97f) resetAxis() // tilted more than ~14°
        g.copyInto(gravity)
        haveGravity = true
        samples++
        // Gravity takes a moment to settle after the sensor starts; then compare with the saved mount.
        pendingMount?.let { m ->
            if (samples >= 50) {
                if (g[0] * m[0] + g[1] * m[1] + g[2] * m[2] > 0.985f) {
                    m.copyInto(forward, 0, 3, 6)
                    evidence = minOf(m[6], 8f)
                }
                pendingMount = null
            }
        }

        // Horizontal part of the acceleration (the road plane).
        val up = dot(linear, g)
        val h = floatArrayOf(linear[0] - up * g[0], linear[1] - up * g[1], linear[2] - up * g[2])
        for (i in 0..2) sinceFix[i] += h[i]
        sinceFixCount++

        val raw = if (evidence > 1.5f) {
            dot(h, forward) / max(1e-3f, norm(forward))
        } else {
            // Not calibrated yet: take the size of the push, with GPS saying which way.
            norm(h) * if (lastGpsAccel < -0.2f) -1f else 1f
        }
        // Smooth out road vibration (time constant ~0.15 s).
        val k = 1f - exp(-dt / 0.15f)
        accel += (raw - accel) * k

        // Standing still: GPS near zero and the phone barely moving.
        stillFor = if (norm(h) < 0.15f && speed < 1.5f) stillFor + dt else 0f
        speed = if (stillFor > 1f) 0f else max(0f, speed + accel * dt)
    }

    /** A GPS fix with a speed (m/s). [timeMs] is the fix's time. */
    fun onGps(gpsSpeed: Float, timeMs: Long) {
        if (lastFixSpeed >= 0f && timeMs > lastFixTime) {
            val dt = (timeMs - lastFixTime) / 1000f
            if (dt in 0.2f..3f) {
                lastGpsAccel = (gpsSpeed - lastFixSpeed) / dt
                // Learn forward: the average horizontal push over this interval, signed by
                // whether GPS saw us speed up or slow down.
                if (sinceFixCount > 0 && abs(lastGpsAccel) > 0.5f) {
                    val w = sign(lastGpsAccel) * minOf(abs(lastGpsAccel), 3f) / sinceFixCount
                    for (i in 0..2) forward[i] += sinceFix[i] * w
                    // Evidence only builds when the push actually agrees with GPS.
                    val n = norm(forward)
                    if (n > 0f) {
                        val agree = dot(sinceFix, forward) / n / sinceFixCount * sign(lastGpsAccel)
                        evidence = (evidence + if (agree > 0.2f) 1f else -0.5f).coerceIn(0f, 20f)
                    }
                }
            }
        }
        sinceFix.fill(0f)
        sinceFixCount = 0
        lastFixSpeed = gpsSpeed
        lastFixTime = timeMs
        // GPS can't drift, the accelerometer can't lag: blend towards the fix.
        speed += (gpsSpeed - speed) * 0.5f
        if (gpsSpeed < 0.5f && speed < 1.5f) speed = gpsSpeed
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: FloatArray) = sqrt(dot(a, a))
}
