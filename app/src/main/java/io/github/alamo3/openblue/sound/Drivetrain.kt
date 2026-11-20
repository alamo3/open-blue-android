package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A virtual multi-gear drivetrain for the recorded engines: turns road speed and pedal into
 * the engine state a racing game would drive its sound with. A port of drivetrain.py from the
 * virtual-car-sound Raspberry Pi, so the phone's shifts behave like the Pi's:
 *
 *  - gears: each [GEAR_STEP] of the one before; gear 1 reaches the redline at [GEAR1_TOP_KMH]
 *  - shifting: 1->2 and 2->3 at set road speeds ([SHIFT_KMH]), higher gears on revs (later
 *    the harder you push); kickdown on a pedal stab. A shift takes 0.25-0.45 s, during which
 *    torque is cut and the revs swing over to the new gear
 *  - downshifts off the pedal blip the throttle to match revs
 *  - clutch slip pulling away; flywheel lag; overrun (negative load) off the pedal
 *  - limiter: flat out, every gear bounces off the redline briefly before the upshift
 */
class Drivetrain(var idleRpm: Float = 2200f, var redline: Float = 7000f) {
    companion object {
        const val GEARS = 6
        const val GEAR_STEP = 0.68f
        const val GEAR1_TOP_KMH = 50f
        const val LAUNCH_RPM = 3800f
        val SHIFT_KMH = floatArrayOf(30f, 60f)
        const val UPSHIFT_LIGHT = 0.50f
        const val UPSHIFT_FULL = 0.97f
        const val DOWNSHIFT_MARGIN = 0.85f
        const val KICKDOWN_STAB = 0.30f
        const val SHIFT_SEC_FULL = 0.25f
        const val SHIFT_SEC_LIGHT = 0.45f
        const val MIN_GEAR_SEC = 1.0f
        const val INERTIA_SEC = 0.07f
        const val PEDAL_SMOOTH_SEC = 0.25f   // how the gearbox reads the pedal (shift decisions)
        const val TORQUE_SMOOTH_SEC = 0.04f  // how the sound follows it (load): barely smoothed
        const val OVERRUN_KMH = 6f
        const val LIMITER_HZ = 12f
        const val LIMITER_PEDAL = 0.85f
        const val LIMITER_HOLD_SEC = 0.35f
        const val LIMITER_DIP = 0.04f
        const val DOWNSHIFT_BLIP = 0.07f
    }

    var gear = 1
        private set
    private var rpm = idleRpm
    private var pedalS = 0f
    private var pedalT = 0f
    private var pedalSlow = 0f
    private var sinceShift = 1e9f
    private var shiftT = 0f
    private var shiftLen = 0f
    private var limiterT = 0f
    private var shiftUp = true
    private var shifts = 0
    private var downshifts = 0
    private var liftOffs = 0
    private var wasOn = false

    private fun topKmh(g: Int) = GEAR1_TOP_KMH / GEAR_STEP.pow(g - 1)
    private fun wheelRpm(g: Int, kmh: Float) = redline * max(0f, kmh) / topKmh(g)
    private fun upshiftRpm(pedal: Float): Float {
        val frac = UPSHIFT_LIGHT + (UPSHIFT_FULL - UPSHIFT_LIGHT) * min(1f, pedal).pow(1.3f)
        return max(frac * redline, idleRpm * 1.15f / GEAR_STEP)    // never land below idle
    }

    /** One control step: [kmh] road speed, [pedalIn] 0..1. */
    fun step(dt: Float, kmh: Float, pedalIn: Float): EngineState {
        val pedal = pedalIn.coerceIn(0f, 1f)
        pedalS += (pedal - pedalS) * min(1f, dt / PEDAL_SMOOTH_SEC)
        pedalT += (pedal - pedalT) * min(1f, dt / TORQUE_SMOOTH_SEC)
        pedalSlow += (pedal - pedalSlow) * min(1f, dt / 0.25f)
        sinceShift += dt

        // gear choice (not while a shift is still in progress)
        if (shiftT <= 0f && sinceShift >= MIN_GEAR_SEC) {
            val w = wheelRpm(gear, kmh)
            val flatOut = pedalS >= LIMITER_PEDAL
            val fixed = if (gear <= SHIFT_KMH.size) SHIFT_KMH[gear - 1] else 0f
            val ready = when {
                fixed > 0f -> kmh >= fixed || limiterT >= LIMITER_HOLD_SEC   // low gears: set speeds
                flatOut -> limiterT >= LIMITER_HOLD_SEC                      // hold into the limiter
                else -> w >= upshiftRpm(pedalS)
            }
            if (gear < GEARS && ready) {
                shift(gear + 1, pedal)
            } else if (gear > 1) {
                val downAt = max(idleRpm * 1.1f, upshiftRpm(pedalS) * GEAR_STEP * DOWNSHIFT_MARGIN)
                val lower = wheelRpm(gear - 1, kmh)
                val stab = pedal - pedalSlow > KICKDOWN_STAB
                val below = gear - 2 < SHIFT_KMH.size && SHIFT_KMH[gear - 2] > 0f
                val down = if (below) kmh < 0.8f * SHIFT_KMH[gear - 2] else w < downAt
                if (down || (stab && lower < 0.9f * redline)) shift(gear - 1, pedal)
            }
            if (kmh < 1f) gear = 1
        }

        // engine speed target: the wheels, never below idle; clutch slip from rest
        var target = max(wheelRpm(gear, kmh), idleRpm)
        if (gear == 1) target = max(target, idleRpm + (LAUNCH_RPM - idleRpm) * pedal)
        var limiter = false
        if (target >= redline) {
            target = redline
            limiter = pedal > 0.5f
        }
        var cut = false
        if (limiter) {
            limiterT += dt
            cut = (limiterT * LIMITER_HZ * 2).toInt() % 2 == 1
            if (cut) target = redline * (1f - LIMITER_DIP)
        } else {
            limiterT = 0f
        }
        var blip = 0f
        if (shiftT > 0f && !shiftUp && pedalS < 0.3f) {
            val frac = 1f - shiftT / shiftLen
            if (frac < 0.6f) {
                blip = sin(PI.toFloat() * frac / 0.6f)
                target *= 1f + DOWNSHIFT_BLIP * blip
            }
        }
        val tau = if (shiftT > 0f) max(INERTIA_SEC, shiftLen / 3f) else INERTIA_SEC   // swing to the new gear
        rpm += (target - rpm) * min(1f, dt / tau)

        // torque: follows the pedal, cut through a shift, negative on the overrun
        var shifting = 0f
        if (shiftT > 0f) {
            val frac = 1f - shiftT / shiftLen      // deepest at the start, back over the last 40 %
            shifting = if (frac < 0.6f) 1f else max(0f, (1f - frac) / 0.4f)
            shiftT -= dt
        }
        if (cut) shifting = 1f
        val rpmNorm = ((rpm - idleRpm) / (redline - idleRpm)).coerceIn(0f, 1f)
        var load = if (pedalT > 0.04f || kmh < OVERRUN_KMH) pedalT * (1f - shifting)
            else -min(1f, (rpm - idleRpm) / (redline - idleRpm) + 0.2f)
        if (blip > 0f) load = max(load, 0.4f * blip)       // the blip is a throttle stab

        // lift-off pops: from pulling to the overrun, revs up
        if (load < 0f && wasOn && rpmNorm > 0.35f) liftOffs++
        wasOn = load > 0.2f

        return EngineState(
            rpm = rpm, load = max(0f, load), gear = gear, engaged = load > 0f, shifts = shifts,
            limiter = limiter, liftOffs = liftOffs, speedKmh = kmh,
            overrun = max(0f, -load), shifting = shifting, cut = cut, downshifts = downshifts,
            throttle = pedalT,
        )
    }

    private fun shift(newGear: Int, pedal: Float) {
        shiftUp = newGear > gear
        if (shiftUp) shifts++ else downshifts++
        gear = newGear
        sinceShift = 0f
        shiftLen = SHIFT_SEC_LIGHT + (SHIFT_SEC_FULL - SHIFT_SEC_LIGHT) * pedal
        shiftT = shiftLen
    }
}
