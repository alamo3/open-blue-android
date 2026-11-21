package io.github.alamo3.openblue.sound

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How hard the car is working, beyond the pedal: uphill (or loaded) the same pedal pulls the car
 * less, downhill more. The car's own torque isn't available fast enough over OBD, so this
 * compares the acceleration the pedal should give (a rough IONIQ 5: traction- then power-limited,
 * minus drag) with what the car actually does. How this car usually responds is learned slowly
 * ([LEARN_SEC]), so the model needn't be exact; a sustained shortfall against that is strain, and
 * the pedal the gearbox and the sound see is made that much heavier (lighter going downhill) --
 * the engine sounds more worked and the gearbox shifts later on a hill, as real ones do.
 *
 * Only judged above [MIN_KMH] with the pedal past [MIN_PEDAL]: off the pedal (regen), crawling
 * and pulling away say nothing reliable about the slope, and there it passes the pedal through.
 */
class Effort {
    companion object {
        const val MIN_KMH = 18f
        const val MIN_PEDAL = 0.15f
        const val LEARN_SEC = 90f          // how this car usually responds
        const val NOW_SEC = 3f             // how it's responding now
        const val STRAIN_GAIN = 0.5f       // pedal x (1 + this x strain)
        const val MAX_STRAIN = 0.8f

        /** The acceleration (m/s²) [pedal] should give at [speedMs], on the flat (the TestDrive car). */
        fun expectedPush(pedal: Float, speedMs: Float) = pedal.pow(0.8f) * min(3.9f, 160000f / 2100f / max(speedMs, 1f))
        fun drag(speedMs: Float) = 0.15f + 0.0004f * speedMs * speedMs
    }

    /** -1..[MAX_STRAIN]: positive = working harder than usual for the pedal (uphill). */
    var strain = 0f
        private set
    private var usual = 1f
    private var now = 1f
    private var learned = 0f                // seconds of usable driving seen

    /** One step: returns the effective pedal (the [pedal] made heavier or lighter by the strain). */
    fun step(dt: Float, pedal: Float, speedMs: Float, accel: Float): Float {
        val p = pedal.coerceIn(0f, 1f)
        val usable = p >= MIN_PEDAL && speedMs * 3.6f >= MIN_KMH
        if (usable) {
            // the share of the expected pull the car actually achieves
            val achieved = ((accel + drag(speedMs)) / expectedPush(p, speedMs)).coerceIn(0f, 2f)
            now += (achieved - now) * (1f - exp(-dt / NOW_SEC))
            usual += (achieved - usual) * (1f - exp(-dt / LEARN_SEC))
            learned += dt
        }
        // trust it once there's been some driving to learn from; fade it out when it can't judge
        val confidence = min(1f, learned / 20f)
        val target = if (usable) ((usual - now) / max(usual, 0.2f)).coerceIn(-1f, MAX_STRAIN) * confidence else 0f
        strain += (target - strain) * (1f - exp(-dt / 1f))
        return (p * (1f + STRAIN_GAIN * strain)).coerceIn(0f, 1f)
    }
}
