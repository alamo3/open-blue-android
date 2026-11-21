package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A virtual 7-speed dual-clutch gearbox and the control unit that drives it, for every sound
 * with gearshifts on. The car itself is a single-speed EV; this puts a sports-car transmission
 * between its motor and the sound:
 *
 *  - **Gear train.** Road speed (and the motor rpm the Pi reads, which calibrates it) gives the
 *    wheel speed: motor rpm through the car's own [EV_REDUCTION]. A virtual engine turns those
 *    wheels through [RATIOS] and a final drive, chosen so 1st reaches the redline at
 *    [GEAR1_TOP_KMH]. That engine's rpm is what the sound follows.
 *  - **Shift schedule.** Upshift at an rpm that rises with the pedal (early and calm when
 *    light, near the redline when floored); never into a gear that would labour the engine
 *    ([lugRpm]); downshift when it would. No upshifts while slowing down, so coasting and
 *    braking never hunt between gears.
 *  - **Adaptive.** [sport] (0..1) learns the driving style from how deep and how fast the pedal
 *    goes and how hard the car accelerates: quick to rise, settling back over ~30 s. Sportier
 *    moves the shift points later, downshifts earlier on the brakes, and shifts faster.
 *  - **Reactive.** Kickdown on a deep, fast press: straight to the lowest gear that lands in the
 *    power band (under 75 % of the redline), skipping gears. A quick lift ("fast off") holds the gear for a moment instead of
 *    upshifting, ready to pull again. Braking downshifts, earlier the sportier.
 *  - **Shifts.** Upshifts hand over in 0.10-0.30 s with a torque dip (deep and sharp at full
 *    load, barely there when light); downshifts blip the throttle to match revs.
 *  - **Launch.** Pulling away the clutch slips: the engine sits at a launch rpm (higher the
 *    harder you press) until the wheels catch up; gently, the revs settle as it bites.
 *
 * [step] returns the same [EngineState] the voices already read, so every sound can use it.
 */
class Dct(var idleRpm: Float = 900f, var redline: Float = 7000f) {
    companion object {
        val RATIOS = floatArrayOf(3.91f, 2.29f, 1.65f, 1.29f, 1.08f, 0.88f, 0.62f)
        val GEARS = RATIOS.size
        const val EV_REDUCTION = 10.65f        // IONIQ 5: motor to wheels
        const val MOTOR_RPM_PER_KMH = 77f      // first guess; learned from the Pi's motor rpm
        const val RATIO_LEARN = 0.1f
        const val GEAR1_TOP_KMH = 62f          // 1st gear at the redline (sets the final drive)

        const val MIN_GEAR_SEC = 0.5f
        const val FAST_OFF_HOLD_SEC = 2.5f
        const val KICKDOWN_JUMP = 0.125f       // pedal this far above its ~0.3 s average, past 80 %
        const val BRAKE_DECEL = 2.5f           // m/s² off the pedal that counts as braking (regen alone ~1.7)
        const val CLUTCH_CLOSE_SEC = 1.2f
        const val INERTIA_SEC = 0.06f
        const val TORQUE_SMOOTH_SEC = 0.04f
        const val OVERRUN_KMH = 6f
        const val LIMITER_HZ = 12f

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
        private fun smoothstep(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    }

    var gear = 1
        private set
    /** 0..1: how sporty the driving has been lately. */
    var sport = 0f
        private set
    /** Learned motor rpm per km/h (the car's reduction and tyre size). */
    var motorRpmPerKmh = MOTOR_RPM_PER_KMH
        private set

    private var rpm = idleRpm
    private var pedalT = 0f            // what the torque (and the sound's load) follows: barely smoothed
    private var pedalAvg = 0f          // ~0.3 s average: kickdown and fast-off detection
    private var lastPedal = 0f
    private var pedalRate = 0f
    private var lastKmh = -1f
    private var accel = 0f
    private var lastMotor = 0
    private var sinceShift = 1e9f
    private var shiftT = 0f
    private var shiftLen = 0f
    private var shiftFromRpm = 0f
    private var shiftUp = true
    private var hold = 0f
    private var clutchLocked = false
    private var slipT = 0f
    private var limiterT = 0f
    private var shifts = 0
    private var downshifts = 0
    private var liftOffs = 0
    private var pullPeak = 0f

    // --- gear train ---------------------------------------------------------------------
    private fun finalDrive(): Float {
        val wheelPerKmh = motorRpmPerKmh / EV_REDUCTION
        return (redline / GEAR1_TOP_KMH) / (wheelPerKmh * RATIOS[0])
    }

    /** The virtual engine's rpm in gear [g] at [kmh]: wheels (motor / reduction) through the box. */
    fun gearRpm(g: Int, kmh: Float): Float {
        val wheelRpm = kmh * motorRpmPerKmh / EV_REDUCTION
        return wheelRpm * finalDrive() * RATIOS[g - 1]
    }

    // --- shift schedule -----------------------------------------------------------------
    private fun upRpm(p: Float): Float {
        val light = lerp(0.22f, 0.50f, sport)
        val frac = light + (0.97f - light) * p.coerceIn(0f, 1f).pow(lerp(1.6f, 1.0f, sport))
        return frac * redline
    }

    /** The lowest rpm it will run at for this pedal: it won't labour the engine. */
    private fun lugRpm(p: Float) = lerp(idleRpm * 1.25f, 0.40f * redline, p) * lerp(1f, 1.25f, sport)

    /**
     * One control step: [dt] seconds, [kmh] road speed, [pedalIn] 0..1, [motorRpm] the motor rpm
     * the Pi last read (0 if unknown; it only calibrates the gear train).
     */
    fun step(dt: Float, kmh: Float, pedalIn: Float, motorRpm: Int = 0): EngineState {
        val pedal = pedalIn.coerceIn(0f, 1f)
        val r = redline
        val idle = idleRpm
        if (motorRpm != 0 && motorRpm != lastMotor && kmh > 15f) {
            motorRpmPerKmh += (abs(motorRpm) / kmh - motorRpmPerKmh) * RATIO_LEARN
        }
        lastMotor = motorRpm
        if (lastKmh >= 0f && dt > 0f) {
            val a = (kmh - lastKmh) / 3.6f / dt
            accel += (a - accel) * min(1f, dt / 0.25f)
        }
        lastKmh = kmh
        val prevAvg = pedalAvg
        pedalT += (pedal - pedalT) * min(1f, dt / TORQUE_SMOOTH_SEC)
        pedalAvg += (pedal - pedalAvg) * min(1f, dt / 0.3f)
        sinceShift += dt
        hold = max(0f, hold - dt)

        // driving style: a deep pedal, a pedal stamped on, hard acceleration
        if (dt > 0f) pedalRate += ((pedal - lastPedal) / dt - pedalRate) * min(1f, dt / 0.1f)
        lastPedal = pedal
        val inst = maxOf((pedal - 0.35f) / 0.55f, (pedalRate - 1.5f) / 3f * pedal, (accel - 1f) / 3f).coerceIn(0f, 1f)
        sport += (inst - sport) * min(1f, dt / if (inst > sport) 1.5f else 30f)

        // fast off: a quick lift holds the gear rather than upshifting
        if (prevAvg > 0.45f && pedal < 0.1f && prevAvg - pedal > 0.35f) hold = FAST_OFF_HOLD_SEC

        val braking = pedal < 0.04f && accel < -BRAKE_DECEL
        val g = gear
        if (shiftT <= 0f && kmh > 2f) {
            val here = gearRpm(g, kmh)
            // kickdown: a deep, fast press -- or floored and well down the revs (not just after a
            // shift, or it would undo the upshift it just made)
            val kick = (pedal > 0.8f && pedal - pedalAvg > KICKDOWN_JUMP) ||
                (pedal > 0.9f && here < 0.55f * r && g > 1 && sinceShift >= MIN_GEAR_SEC)
            var target = g
            if (kick) {
                // the lowest gear that lands in the power band: under 75 % of the redline and under
                // the upshift point (may skip several)
                val limit = min(0.75f * r, 0.9f * upRpm(pedal))
                for (c in 1 until g) if (gearRpm(c, kmh) < limit) { target = c; break }
            } else if (sinceShift >= MIN_GEAR_SEC) {
                val slowing = accel < -0.5f && pedal < 0.2f
                if (g < GEARS && hold <= 0f && !slowing && here >= upRpm(pedalAvg) &&
                    gearRpm(g + 1, kmh) >= lugRpm(pedalAvg) * 0.9f
                ) {
                    target = g + 1
                } else if (g > 1) {
                    val lower = gearRpm(g - 1, kmh)
                    var want = lugRpm(pedalAvg)
                    if (braking) want = max(want, lerp(0.30f, 0.55f, sport) * r)   // downshift on the brakes
                    if (here < want && lower < min(0.88f * r, 0.92f * upRpm(pedalAvg) + 0.2f * r)) target = g - 1
                }
            }
            if (hold > 0f && here > 0.97f * r && g < GEARS) target = g + 1   // held, but not into the limiter
            if (target != g) beginShift(target, pedal)
        }
        if (kmh <= 2f && shiftT <= 0f) gear = 1
        val gr = gear

        // engine speed: the wheels through the gear train; clutch slip pulling away
        val wheel = gearRpm(gr, kmh)
        var target = max(wheel, idle)
        if (gr == 1 && !clutchLocked && pedal > 0.02f) {
            val launch = lerp(idle * 1.3f, lerp(0.45f, 0.60f, sport) * r, pedal.pow(1.2f))
            if (kmh > 2f) slipT += dt
            val closing = min(1f, slipT / CLUTCH_CLOSE_SEC)
            // gently the revs settle toward a little over idle as it bites; under power it holds
            // the launch rpm and the car catches up (it never stalls the engine)
            val bite = max(wheel, idle * 1.3f)
            val held = launch - (launch - bite) * smoothstep(closing) * (1f - pedal)
            target = max(target, held)
            if (wheel >= 0.98f * held) clutchLocked = true
        }
        if (kmh < 2f) { clutchLocked = false; slipT = 0f }

        var limiter = false
        var cut = false
        if (target >= r) {
            target = r
            limiter = pedal > 0.5f
        }
        if (limiter) {
            limiterT += dt
            cut = (limiterT * LIMITER_HZ * 2).toInt() % 2 == 1
            if (cut) target = r * 0.96f
        } else {
            limiterT = 0f
        }

        var shifting = 0f
        var blip = 0f
        if (shiftT > 0f) {
            val frac = 1f - shiftT / shiftLen
            val k = smoothstep(frac)
            if (shiftUp) {
                // the torque handover: a dip, deep and sharp at full load, barely there when light
                val depth = lerp(0.25f, 0.9f, pedalT)
                shifting = depth * if (frac < 0.35f) frac / 0.35f else 1f - (frac - 0.35f) / 0.65f
                rpm = shiftFromRpm + (target - shiftFromRpm) * k
            } else {
                // rev-matching blip, overshooting a touch (more when sporty, none under power)
                blip = sin(PI.toFloat() * min(1f, frac / 0.7f))
                val over = 1f + lerp(0.03f, 0.08f, sport) * blip * (1f - pedalT)
                rpm = (shiftFromRpm + (target - shiftFromRpm) * k) * over
            }
            shiftT -= dt
        } else {
            rpm += (target - rpm) * min(1f, dt / INERTIA_SEC)
        }

        var load = pedalT * (1f - shifting)
        if (blip > 0f && pedal < 0.3f) load = max(load, 0.45f * blip)   // the blip is a throttle stab
        val engaged = pedalT > 0.04f || kmh < OVERRUN_KMH
        if (engaged) {
            pullPeak = max(pullPeak, load)
        } else {
            if (pullPeak > 0.45f && rpm > 0.5f * r) liftOffs++          // lifting off a hard pull: pops
            pullPeak = 0f
        }
        val rpmNorm = ((rpm - idle) / (r - idle)).coerceIn(0f, 1f)
        val overrun = if (engaged) 0f else min(1f, rpmNorm + 0.2f)
        return EngineState(
            rpm = rpm, load = max(0f, load), gear = gr, engaged = engaged, shifts = shifts,
            limiter = limiter, liftOffs = liftOffs, speedKmh = kmh, overrun = overrun,
            shifting = shifting, cut = cut, downshifts = downshifts, throttle = pedalT,
        )
    }

    private fun beginShift(to: Int, pedal: Float) {
        val up = to > gear
        if (up) {
            shiftLen = lerp(0.30f, 0.10f, max(pedal, 0.6f * sport))
            shifts++
        } else {
            shiftLen = lerp(0.35f, 0.16f, sport) + 0.05f * (abs(to - gear) - 1)
            downshifts++
        }
        shiftFromRpm = rpm
        shiftUp = up
        gear = to
        shiftT = shiftLen
        sinceShift = 0f
    }
}
