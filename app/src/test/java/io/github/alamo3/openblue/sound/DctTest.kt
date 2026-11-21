package io.github.alamo3.openblue.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The DCT through scripted drives, with a rough IONIQ 5 for the road speed and the app's own
 * handling of it (whole km/h from the car, smoothed as PiLink does), at the Pi's ~25 Hz.
 */
class DctTest {
    private val dt = 0.04f

    private class Sample(val t: Float, val kmh: Float, val pedal: Float, val st: EngineState)

    /** [script] gives (pedal, brake m/s²) at a time; returns what the gearbox did each step. */
    private fun drive(box: Dct, seconds: Float, script: (Float) -> Pair<Float, Float>): List<Sample> {
        val out = ArrayList<Sample>()
        var v = 0f
        var pedal = 0f
        var last = -1f
        var smooth = 0f
        var acc = 0f
        var t = 0f
        while (t < seconds) {
            val (want, brake) = script(t)
            pedal += (want - pedal).coerceIn(-5f * dt, 5f * dt)            // a foot takes ~0.2 s
            val drivePush = pedal.pow(0.8f) * min(3.9f, 160000f / 2100f / max(v, 1f))
            val regen = if (v > 0.5f) 1.7f * max(0f, 1f - pedal / 0.06f) else 0f
            v = max(0f, v + (drivePush - 0.15f - 0.0004f * v * v - regen - brake) * dt)
            val raw = (v * 3.6f).roundToInt().toFloat()
            if (last >= 0f) {
                acc += ((raw - last) / 3.6f / dt - acc) * min(1f, dt / 0.3f)
                smooth += (raw - smooth) * min(1f, dt / 0.15f)
            } else smooth = raw
            last = raw
            val kmh = if (raw < 0.5f) 0f else max(0f, smooth + acc * 3.6f * 0.15f)
            out += Sample(t, kmh, pedal, box.step(dt, kmh, pedal, (v * 3.6f * 77.5f).toInt()))
            t += dt
        }
        return out
    }

    private fun gearSequence(s: List<Sample>): List<Int> {
        val seq = ArrayList<Int>()
        for (x in s) if (seq.isEmpty() || seq.last() != x.st.gear) seq += x.st.gear
        return seq
    }

    /** rpm just before each upshift. */
    private fun upshiftRpms(s: List<Sample>): List<Float> =
        (1 until s.size).filter { s[it].st.shifts > s[it - 1].st.shifts }.map { s[it - 1].st.rpm }

    @Test fun gentleDrivingShiftsEarlyAndNeverHunts() {
        val s = drive(Dct(900f, 7000f), 32f) { t ->
            when {
                t < 12f -> 0.25f to 0f
                t < 20f -> 0.12f to 0f
                t < 24f -> 0f to 0f
                else -> 0f to 2.5f
            }
        }
        val seq = gearSequence(s)
        println("gentle: $seq, upshifts at ${upshiftRpms(s).map { it.toInt() }}")
        assertTrue("went up through the gears ($seq)", seq.max() >= 5)
        assertTrue("early upshifts (${upshiftRpms(s)})", upshiftRpms(s).all { it < 3000f })
        // up all the way, then down all the way: no gear is ever left and re-entered
        val peak = seq.indexOf(seq.max())
        assertTrue("never hunts ($seq)", (1 until peak).all { seq[it] > seq[it - 1] } &&
            (peak + 1 until seq.size).all { seq[it] < seq[it - 1] })
        assertEquals("back in 1st at a stop", 1, s.last().st.gear)
        assertTrue("idling at a stop", s.last().st.rpm < 1000f)
    }

    @Test fun flooredShiftsNearTheRedlineWithACut() {
        val box = Dct(900f, 7000f)
        val s = drive(box, 14f) { t -> (if (t < 1f) 0f else 1f) to 0f }
        val ups = upshiftRpms(s)
        println("floored: ${gearSequence(s)}, upshifts at ${ups.map { it.toInt() }}, sport ${box.sport}")
        assertTrue("shifted ($ups)", ups.size >= 2)
        assertTrue("near the redline ($ups)", ups.all { it > 0.9f * 7000f })
        assertTrue("torque cut through the shift", s.any { it.st.shifting > 0.6f })
        // floored, each gear runs into the rev limiter and bounces off it before the upshift
        val upIdx = (1 until s.size).filter { s[it].st.shifts > s[it - 1].st.shifts }
        for (i in upIdx) {
            val before = s.subList(max(0, i - 12), i)                     // the last ~0.5 s
            assertTrue("bounced off the limiter before the upshift at ${s[i].t} s",
                before.count { it.st.cut == true } >= 2 && before.any { it.st.limiter })
        }
        assertTrue("learned it's being driven hard (${box.sport})", box.sport > 0.8f)
        // launch: the clutch slips, holding the revs well up while the car gets going
        val pullingAway = s.first { it.kmh > 5f }
        assertTrue("launch rpm ${pullingAway.st.rpm}", pullingAway.st.rpm > 2500f)
    }

    @Test fun kickdownSkipsGears() {
        val s = drive(Dct(900f, 7000f), 26f) { t ->
            when {
                t < 9f -> 0.45f to 0f
                t < 18f -> 0.18f to 0f
                else -> 1f to 0f
            }
        }
        val before = s.last { it.t < 18f }.st.gear
        val after = s.first { it.t > 18.5f }.st.gear
        println("kickdown: ${gearSequence(s)} ($before -> $after)")
        assertTrue("cruising in a high gear ($before)", before >= 5)
        assertTrue("kicked down several gears at once ($before -> $after)", before - after >= 3)
        val i = s.indexOfFirst { it.t > 18f }
        assertEquals("in one shift", s[i - 1].st.downshifts + 1, s[i + 15].st.downshifts)
    }

    /** Floored from a gentle cruise: one kickdown, then only upshifts (no undoing them). */
    @Test fun flooredFromCruiseNeverHunts() {
        val s = drive(Dct(900f, 7000f), 26f) { t -> (if (t < 12f) 0.2f else 1f) to 0f }
        val pull = s.filter { it.t > 12f }
        val seq = gearSequence(pull)
        println("floored from cruise: $seq")
        assertTrue("kicked down", seq[1] < seq[0])
        assertTrue("then only up ($seq)", (2 until seq.size).all { seq[it] > seq[it - 1] })
        assertTrue("kickdown lands in the power band", pull.first { it.st.gear == seq[1] }.st.rpm < 0.8f * 7000f)
    }

    @Test fun fastLiftHoldsTheGearAndBrakingShiftsDown() {
        val s = drive(Dct(900f, 7000f), 22f) { t ->
            when {
                t < 9f -> 0.9f to 0f
                t < 10.5f -> 0f to 0f
                t < 15f -> 0f to 4f
                else -> 0.6f to 0f
            }
        }
        val atLift = s.last { it.t < 9f }.st.gear
        val held = s.filter { it.t in 9.2f..10.5f }.map { it.st.gear }
        println("lift: ${gearSequence(s)}")
        assertTrue("held $atLift after the lift ($held)", held.all { it == atLift })
        val braking = s.filter { it.t in 10.5f..15f }
        assertTrue("downshifted on the brakes", braking.last().st.downshifts > braking.first().st.downshifts)
        assertTrue("rev-matching blip on a downshift", braking.any { it.st.load > 0.2f })
        assertTrue("no upshift while braking", braking.last().st.shifts == braking.first().st.shifts)
    }

    @Test fun learnsTheCarsRatioFromMotorRpm() {
        val box = Dct()
        var st = box.step(dt, 60f, 0.3f, 0)
        repeat(100) { st = box.step(dt, 60f, 0.3f, 60 * 80 + it % 2) }   // a new reading each step
        assertEquals(80f, box.motorRpmPerKmh, 1f)
        assertTrue(st.rpm > 0f)
    }
}
