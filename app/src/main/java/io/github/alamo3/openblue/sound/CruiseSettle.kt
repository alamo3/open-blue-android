package io.github.alamo3.openblue.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Settle, for the sounds that aren't the native engine's (it has the same, in engine.cpp): a
 * steady sound is a drone, so the engine eases down when nothing is happening --
 *  - at a steady cruise (above [MIN_KMH], barely accelerating, the pedal steady, no shift) for
 *    [AFTER_SEC]: [DB] down
 *  - at idle (stopped, off the pedal) for [IDLE_AFTER_SEC]: [IDLE_DB] down
 * Any change brings it straight back. Cars with synthetic engine sound do the same.
 */
class CruiseSettle {
    companion object {
        const val MIN_KMH = 20f
        const val ACCEL = 0.35f       // m/s²
        const val PEDAL = 0.06f       // from its 2 s average
        const val AFTER_SEC = 4f
        const val DB = -8f
        const val IDLE_MAX_KMH = 2f
        const val IDLE_PEDAL = 0.03f
        const val IDLE_AFTER_SEC = 3f
        const val IDLE_DB = -9f
        const val DOWN_SEC = 2.5f
        const val UP_SEC = 0.12f
    }

    private var pedalAvg = 0f
    private var steadyFor = 0f
    private var idleFor = 0f
    /** The level to play at, 1 .. about 0.5. */
    var gain = 1f
        private set
    val settled: Boolean get() = gain < 0.9f

    /** One audio block of [dt] seconds; [on] false (the setting off, a test drive...) never settles. */
    fun step(dt: Float, on: Boolean, kmh: Float, accel: Float, pedal: Float, shifting: Boolean): Float {
        pedalAvg += (pedal - pedalAvg) * (1f - exp(-dt / 2f))
        val steady = on && kmh > MIN_KMH && abs(accel) < ACCEL && abs(pedal - pedalAvg) < PEDAL && !shifting
        steadyFor = if (steady) steadyFor + dt else 0f
        idleFor = if (on && kmh < IDLE_MAX_KMH && pedal < IDLE_PEDAL) idleFor + dt else 0f
        val to = when {
            idleFor > IDLE_AFTER_SEC -> 10f.pow(IDLE_DB / 20f)
            steadyFor > AFTER_SEC -> 10f.pow(DB / 20f)
            else -> 1f
        }
        gain += (to - gain) * (1f - exp(-dt / if (to < gain) DOWN_SEC else UP_SEC))
        return gain
    }
}
