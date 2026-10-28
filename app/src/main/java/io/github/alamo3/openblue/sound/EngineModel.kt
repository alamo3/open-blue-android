package io.github.alamo3.openblue.sound

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the synth needs each moment. */
data class EngineState(
    val rpm: Float,
    val load: Float,
    val gear: Int,
    /** True while the "throttle" is in (the car is accelerating, or the rev slider is up). */
    val engaged: Boolean,
    /** Increments on every upshift, so the synth can play the shift. */
    val shifts: Int,
    val limiter: Boolean,
    /** Increments when lifting off hard and high in the revs, for pops and crackles. */
    val liftOffs: Int = 0,
)

/**
 * A virtual petrol engine and 6-speed gearbox, driven by the car's speed and how hard it's
 * accelerating. Not accelerating means the revs fall back to idle (or, with [holdRevs], stay
 * on the gear's revs for the speed, like cruising in gear).
 */
class EngineModel(
    var idleRpm: Float = 850f,
    var redline: Float = 6900f,
    var holdRevs: Boolean = false,
) {
    /** Engine rpm per km/h in each gear: about 60, 100, 140, 180, 220, 265 km/h at 6500 rpm. */
    private val rpmPerKmh = floatArrayOf(108f, 65f, 47f, 36.5f, 29.5f, 24.5f)

    /** Acceleration (m/s²) treated as full throttle. */
    var fullAccel = 4.0f

    private var rpm = idleRpm
    private var gear = 1
    private var engaged = false
    private var quietFor = 0f
    private var shifts = 0
    private var liftOffs = 0
    /** Hardest the current pull has been (load), to tell a big lift-off from easing off. */
    private var pullPeak = 0f
    private var shiftHold = 0f
    private var load = 0f

    /** Revs this gear would turn at this speed. */
    private fun gearRpm(g: Int, kmh: Float) = kmh * rpmPerKmh[g - 1]

    /** Where an upshift happens: early when gentle, near the redline when flat out. */
    private fun shiftPoint(load: Float) = 2600f + (redline - 400f - 2600f) * load.coerceIn(0f, 1f)

    /**
     * Advances the model by [dt] seconds. [accel] is forward acceleration in m/s², [speed] in m/s.
     * [revThrottle], when set (0..1), revs the engine in neutral and ignores the car.
     */
    fun update(dt: Float, speed: Float, accel: Float, revThrottle: Float? = null): EngineState {
        if (revThrottle != null) return neutral(dt, revThrottle)

        val kmh = max(0f, speed) * 3.6f
        val rawLoad = (accel / fullAccel).coerceIn(0f, 1f)
        // Throttle "in" above ~0.3 m/s²; it needs a short calm spell to count as lifted, so
        // bumps and gear changes don't make it flicker.
        if (accel > 0.3f) {
            if (!engaged) {
                engaged = true
                pullPeak = 0f
                gear = kickdownGear(kmh, rawLoad)
            }
            quietFor = 0f
        } else if (engaged) {
            quietFor += dt
            if (quietFor > 0.35f) {
                engaged = false
                if (pullPeak > 0.45f && rpm > 2800f) liftOffs++
            }
        }
        // Load follows quickly going up, more gently going down.
        val targetLoad = if (engaged) max(rawLoad, 0.12f) else 0f
        load += (targetLoad - load) * min(1f, dt * if (targetLoad > load) 12f else 5f)
        if (engaged) pullPeak = max(pullPeak, load)

        var limiter = false
        val target: Float
        if (engaged) {
            // Upshift when past the shift point for how hard we're going, but hold the gear briefly
            // after a shift so it doesn't skip gears.
            shiftHold = max(0f, shiftHold - dt)
            if (shiftHold == 0f && gear < 6 && gearRpm(gear, kmh) > shiftPoint(load)) {
                gear++
                shifts++
                shiftHold = 0.45f
            } else if (shiftHold == 0f && load > 0.55f && gear > 1 && gearRpm(gear - 1, kmh) < shiftPoint(load) * 0.8f) {
                // Pushing harder mid-pull: drop a gear, like a kickdown.
                gear--
                shiftHold = 0.6f
            }
            // Pulling away: the clutch slips, so revs sit above what first gear's speed gives
            // until it bites, around 15-25 km/h.
            val launch = if (gear == 1) idleRpm + 400f + 1800f * load - kmh * 10f else 0f
            var t = max(max(gearRpm(gear, kmh), launch), idleRpm)
            if (t > redline) {
                t = redline
                limiter = true
            }
            target = t
        } else {
            // Keep a sensible gear for the speed, so getting back on it starts from the right revs.
            while (gear > 1 && gearRpm(gear, kmh) < 1300f) gear--
            while (gear < 6 && gearRpm(gear, kmh) > 3000f) gear++
            target = if (holdRevs && kmh > 10f) max(idleRpm, gearRpm(gear, kmh)) else idleRpm
        }
        rpm = slew(rpm, target, dt, up = 9000f, down = if (engaged) 7000f else 2600f)
        return EngineState(rpm, load, gear, engaged, shifts, limiter, liftOffs)
    }

    /** Lowest gear that stays under the shift point: flooring it at speed drops a gear or two. */
    private fun kickdownGear(kmh: Float, load: Float): Int {
        val limit = shiftPoint(load) * 0.92f
        for (g in 1..6) if (gearRpm(g, kmh) <= limit) return g
        return 6
    }

    private fun neutral(dt: Float, throttle: Float): EngineState {
        val t = throttle.coerceIn(0f, 1f)
        val was = engaged
        engaged = t > 0.02f
        if (was && !engaged && load > 0.3f && rpm > 2800f) liftOffs++
        load += ((if (engaged) t else 0f) - load) * min(1f, dt * 10f)
        val target = idleRpm + (redline - idleRpm) * t
        rpm = slew(rpm, target, dt, up = 6000f, down = 3500f)
        val limiter = t > 0.98f
        return EngineState(rpm, load, 0, engaged, shifts, limiter, liftOffs)
    }

    private fun slew(from: Float, to: Float, dt: Float, up: Float, down: Float): Float {
        val d = to - from
        val step = (if (d > 0) up else down) * dt
        return if (abs(d) <= step) to else from + step * (if (d > 0) 1 else -1)
    }
}
