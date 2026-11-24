package io.github.alamo3.openblue.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Cruise settle, for the sounds that aren't the native engine's (it has the same, in engine.cpp):
 * at a steady cruise -- above [MIN_KMH], barely accelerating, the pedal steady, no shift -- for
 * [AFTER_SEC], the engine eases [DB] down; any change brings it straight back. Cars with
 * synthetic engine sound do the same, so a long motorway run doesn't drone.
 */
class CruiseSettle {
    companion object {
        const val MIN_KMH = 40f
        const val ACCEL = 0.35f       // m/s²
        const val PEDAL = 0.06f       // from its 2 s average
        const val AFTER_SEC = 4f
        const val DB = -6f
        const val DOWN_SEC = 2.5f
        const val UP_SEC = 0.12f
    }

    private var pedalAvg = 0f
    private var steadyFor = 0f
    /** The level to play at, 1 .. about 0.5. */
    var gain = 1f
        private set
    val settled: Boolean get() = gain < 0.9f

    /** One audio block of [dt] seconds; [on] false (the setting off, a test drive...) never settles. */
    fun step(dt: Float, on: Boolean, kmh: Float, accel: Float, pedal: Float, shifting: Boolean): Float {
        pedalAvg += (pedal - pedalAvg) * (1f - exp(-dt / 2f))
        val steady = on && kmh > MIN_KMH && abs(accel) < ACCEL && abs(pedal - pedalAvg) < PEDAL && !shifting
        steadyFor = if (steady) steadyFor + dt else 0f
        val to = if (steadyFor > AFTER_SEC) 10f.pow(DB / 20f) else 1f
        gain += (to - gain) * (1f - exp(-dt / if (to < gain) DOWN_SEC else UP_SEC))
        return gain
    }
}
