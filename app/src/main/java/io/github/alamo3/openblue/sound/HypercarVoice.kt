package io.github.alamo3.openblue.sound

import kotlin.math.max
import kotlin.math.pow

/**
 * Futuristic hypercar: an electric performance voice in layers (virtual-car-sound
 * layered/hypercar.py, made real-time). The aggressive tone follows the gearbox's revs, so it
 * shifts; underneath, the car's REAL motor sings its own orders and never does.
 *
 *  - core: three detuned saw + pulse voices across the stereo field, through a resonant
 *    low-pass that opens with load and revs
 *  - edge: FM snarl under load (an inharmonic modulator, index rising with load and revs)
 *  - sub: a sine an octave under the core, for weight
 *  - motor: the real motor's 8th and 12th orders, louder under torque and under regen
 *  - spool: a high breathy whistle at high revs under load
 *  - air: road and wind rush rising with speed
 *  - regen: a soft pad while slowing off the pedal
 * Shifts (the hard-shift sound) and the limiter's stutter come from [SoundVoice] / [DriveEffects].
 */
internal class HypercarVoice(sr: Int) : LayeredVoice(sr) {
    companion object {
        const val IDLE = 1000f
        const val REDLINE = 9000f
        private val DETUNE = floatArrayOf(2f.pow(-6f / 1200f), 1f, 2f.pow(7f / 1200f))
        private val PAN = floatArrayOf(0.75f, 0.5f, 0.25f)
        private const val TRIM = 2.1f             // hard pulls as loud as the recorded engines'
    }

    private val noise = Noise(21)
    private val pinkL = Pink(22)
    private val pinkR = Pink(23)
    private val sawPh = DoubleArray(3)
    private val pulsePh = DoubleArray(3)
    private val lp = Array(2) { Biq() }
    private var fmMod = 0.0
    private var fmCar = 0.0
    private val edgeHp = Biq()
    private var subPh = 0.0
    private val motorPh = DoubleArray(2)
    private var whistlePh = 0.0
    private val whistleBp = Biq()
    private val airBp = Array(2) { Biq() }
    private val padPh = DoubleArray(3)
    private val hp = Array(2) { Biq().highPass(30f, 0.707f, sr) }
    private val coreLevel = Smooth(0.015f, sr)
    private val edgeLevel = Smooth(0.03f, sr)
    private val subLevel = Smooth(0.05f, sr)
    private val motorLevel = Smooth(0.05f, sr)
    private val whistleLevel = Smooth(0.08f, sr)
    private val padLevel = Smooth(0.3f, sr)
    private var lastF = 0f

    override fun render(out: FloatArray, outR: FloatArray, frames: Int) {
        val s = st
        if (s == null) { out.fill(0f, 0, frames); outR.fill(0f, 0, frames); return }
        val rpm = max(s.rpm, IDLE)
        val rpmN = ((rpm - IDLE) / (REDLINE - IDLE)).coerceIn(0f, 1f)
        val load = s.load.coerceIn(0f, 1f)
        val overrun = (s.overrun ?: 0f).coerceIn(0f, 1f)
        val kmh = s.speedKmh
        val speedN = speedNorm(kmh)
        val fTarget = rpm / 60f * 2f
        val f0 = if (lastF <= 0f) fTarget else lastF                // glide the pitch across the block
        lastF = fTarget

        val cutoff = Dsp.lerp(650f, 4200f, 0.25f * rpmN + 0.75f * load * (0.4f + 0.6f * rpmN))
        for (ch in 0..1) lp[ch].lowPass(cutoff, 2.2f, sr)
        val coreT = (0.38f + 0.62f * load.pow(1.2f)) * (0.6f + 0.4f * rpmN) * (1f - 0.85f * overrun) *
            (1f - 0.7f * s.shifting.coerceIn(0f, 1f))
        val index = 4f * load.pow(1.5f) * (0.3f + 0.7f * rpmN)
        edgeHp.highPass(300f, 0.707f, sr)
        val edgeT = load * load * (0.2f + 0.8f * rpmN)
        val subT = (0.25f + 0.75f * load) * (1f - 0.6f * overrun)
        val motorHz = (if (motorRpm > 0f) motorRpm else kmh * 77f) / 60f
        val torque = (load + 0.8f * overrun * speedN).coerceIn(0f, 1f)
        val motorT = (kmh / 8f).coerceIn(0f, 1f) * (0.25f + 0.75f * torque)
        whistleBp.bandPass(fTarget * 11f, 6f, sr)
        val whistleT = rpmN * rpmN * load
        for (ch in 0..1) airBp[ch].bandPass(Dsp.lerp(500f, 1800f, speedN), 0.6f, sr)
        val air = speedN.pow(1.6f) * 0.12f
        val padF = Dsp.lerp(110f, 220f, speedN)
        val padT = overrun * (kmh / 20f).coerceIn(0f, 1f)

        val vol = volume * TRIM
        for (i in 0 until frames) {
            val f = f0 + (fTarget - f0) * i / frames
            // --- core --------------------------------------------------------------------------
            var cl = 0f
            var cr = 0f
            for (v in 0..2) {
                val fv = f * DETUNE[v]
                val dt = (fv / sr).toDouble()
                sawPh[v] = (sawPh[v] + dt) % 1.0
                val dt2 = dt * 2
                pulsePh[v] = (pulsePh[v] + dt2) % 1.0
                val pulse = 0.5f * (Dsp.saw(pulsePh[v], dt2) - Dsp.saw((pulsePh[v] + 0.35) % 1.0, dt2))
                val tone = 0.6f * Dsp.saw(sawPh[v], dt) + 0.8f * pulse
                cl += tone * PAN[v]; cr += tone * (1f - PAN[v])
            }
            val cg = coreLevel.next(coreT) * 0.45f
            var l = lp[0].process(cl) * cg
            var r = lp[1].process(cr) * cg
            // --- edge: FM under load ------------------------------------------------------------
            fmMod += f * 3.41 / sr
            fmCar += f * 2.0 / sr
            val edge = edgeHp.process(Dsp.sin1(fmCar + index * Dsp.sin1(fmMod) / (2 * Math.PI))) * edgeLevel.next(edgeT) * 0.22f
            l += edge * 0.55f; r += edge * 0.45f
            // --- sub ------------------------------------------------------------------------------
            subPh += f / 2.0 / sr
            val sub = Dsp.sin1(subPh) * subLevel.next(subT) * 0.35f * 0.5f
            l += sub; r += sub
            // --- the real motor --------------------------------------------------------------------
            motorPh[0] += motorHz * 8.0 / sr
            motorPh[1] += motorHz * 12.0 / sr
            val whine = (Dsp.sin1(motorPh[0]) + 0.45f * Dsp.sin1(motorPh[1])) * motorLevel.next(motorT) * 0.06f * 0.5f
            l += whine; r += whine
            // --- spool whistle ---------------------------------------------------------------------
            whistlePh += f * 11.0 / sr
            val wh = (Dsp.sin1(whistlePh) + 0.4f * whistleBp.process(noise.gauss())) * whistleLevel.next(whistleT) * 0.05f
            l += wh * 0.4f; r += wh * 0.6f
            // --- air ---------------------------------------------------------------------------------
            l += airBp[0].process(pinkL.next()) * air
            r += airBp[1].process(pinkR.next()) * air
            // --- regen pad -----------------------------------------------------------------------------
            padPh[0] += padF / sr; padPh[1] += padF * 1.5 / sr; padPh[2] += padF * 2.0 / sr
            val pad = (Dsp.sin1(padPh[0]) + Dsp.sin1(padPh[1]) + Dsp.sin1(padPh[2])) / 3f * padLevel.next(padT) * 0.06f * 0.5f
            l += pad; r += pad

            out[i] = Dsp.softClip(hp[0].process(l), 1.3f) * vol
            outR[i] = Dsp.softClip(hp[1].process(r), 1.3f) * vol
        }
    }
}
