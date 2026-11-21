package io.github.alamo3.openblue.sound

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A scripted drive for trying a sound while parked: it stands in for the car (speed, pedal,
 * acceleration, motor rpm) until it's done. Idle, a gentle pull away to 40 km/h, then a hard pull
 * to 100 km/h (a kickdown on the way), a lift off the pedal coasting on regen, braking to a stop,
 * and a moment at idle -- about 25 s. Speed comes from a rough IONIQ 5 (traction- then
 * power-limited, drag, one-pedal regen); the pedal moves as a foot would (~0.2 s end to end).
 */
class TestDrive {
    enum class Stage(val label: String) {
        IDLE("idle"), PULL_AWAY("pulling away"), HARD("hard to 100 km/h"), LIFT("off the pedal"),
        BRAKE("braking to a stop"), SETTLE("idle"), DONE("done"),
    }

    companion object {
        const val GENTLE_PEDAL = 0.35f
        const val GENTLE_TO_KMH = 40f
        const val HARD_PEDAL = 0.85f
        const val TOP_KMH = 100f
        const val IDLE_SEC = 1.5f
        const val LIFT_SEC = 2.5f
        const val BRAKE_MS2 = 3f
        const val SETTLE_SEC = 2f
        const val MOTOR_RPM_PER_KMH = 77.5f
        const val PEDAL_PER_SEC = 5f
    }

    var stage = Stage.IDLE
        private set
    /** Road speed, m/s. */
    var speed = 0f
        private set
    /** Accelerator pedal, 0..1. */
    var pedal = 0f
        private set
    /** Forward acceleration, m/s². */
    var accel = 0f
        private set
    val kmh: Float get() = speed * 3.6f
    val motorRpm: Int get() = (kmh * MOTOR_RPM_PER_KMH).toInt()
    val done: Boolean get() = stage == Stage.DONE
    private var inStage = 0f

    /** Advances by [dt] seconds; false once the drive is over. */
    fun step(dt: Float): Boolean {
        if (done) return false
        inStage += dt
        val (want, brake) = when (stage) {
            Stage.IDLE -> 0f to 0f
            Stage.PULL_AWAY -> GENTLE_PEDAL to 0f
            Stage.HARD -> HARD_PEDAL to 0f
            Stage.LIFT -> 0f to 0f
            Stage.BRAKE -> 0f to BRAKE_MS2
            Stage.SETTLE, Stage.DONE -> 0f to 0f
        }
        pedal += (want - pedal).coerceIn(-PEDAL_PER_SEC * dt, PEDAL_PER_SEC * dt)
        val push = pedal.pow(0.8f) * min(3.9f, 160000f / 2100f / max(speed, 1f))
        val regen = if (speed > 0.5f) 1.7f * max(0f, 1f - pedal / 0.06f) else 0f
        val drag = if (speed > 0f || push > 0.15f) 0.15f + 0.0004f * speed * speed else 0f
        val before = speed
        speed = max(0f, speed + (push - drag - regen - brake) * dt)
        accel = if (dt > 0f) (speed - before) / dt else 0f
        val next = when (stage) {
            Stage.IDLE -> if (inStage >= IDLE_SEC) Stage.PULL_AWAY else null
            Stage.PULL_AWAY -> if (kmh >= GENTLE_TO_KMH) Stage.HARD else null
            Stage.HARD -> if (kmh >= TOP_KMH) Stage.LIFT else null
            Stage.LIFT -> if (inStage >= LIFT_SEC) Stage.BRAKE else null
            Stage.BRAKE -> if (speed <= 0f) Stage.SETTLE else null
            Stage.SETTLE -> if (inStage >= SETTLE_SEC) Stage.DONE else null
            Stage.DONE -> null
        }
        if (next != null) { stage = next; inStage = 0f }
        return !done
    }
}
