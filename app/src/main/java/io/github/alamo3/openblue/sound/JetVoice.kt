package io.github.alamo3.openblue.sound

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Fast plane: a fighter-style turbofan heard from the cockpit (virtual-car-sound layered/jet.py,
 * made real-time). With gearshifts on, the gearbox's revs set the target spool, so the engine
 * winds up through each gear and spools down a little at each shift, the turbine's lag rounding
 * the steps off; without, the pedal sets the spool (with a floor rising with road speed).
 *
 *  - fan: the blade-pass tone, turning to "buzzsaw" (the shaft-rate harmonics below it) at high spool
 *  - core: the high-pressure compressor's thin whine
 *  - rumble: combustion rumble, growing with spool
 *  - roar: jet mixing noise, rising steeply with spool
 *  - afterburner: floored, it lights with a thump and a crackling, pulsing low roar, and stays
 *    lit through the shifts
 *  - air: airframe rush with road speed
 * Shifts (the hard-shift sound) and the limiter's stutter come from [SoundVoice] / [DriveEffects].
 */
internal class JetVoice(sr: Int) : LayeredVoice(sr) {
    companion object {
        const val IDLE = 1000f                 // the gearbox's rev range for the jet
        const val REDLINE = 7000f
        private const val IDLE_SPOOL = 0.22f
        private const val FAN_MAX_RPM = 10500f
        private const val FAN_BLADES = 18
        private const val CORE_RATIO = 1.6f
        private const val SPOOL_UP_SEC = 1.6f
        private const val SPOOL_DOWN_SEC = 1.0f
        private const val GEARED_UP_SEC = 0.35f
        private const val GEARED_DOWN_SEC = 0.25f
        private const val BURNER_PEDAL = 0.9f
        private const val BURNER_SPOOL = 0.55f
        private const val TRIM = 6.7f              // hard pulls as loud as the recorded engines'
    }

    private val noise = Noise(31)
    private val pinks = Array(6) { Pink(32 + it) }
    private var spool = IDLE_SPOOL
    private var fanPh = 0.0
    private var corePh = 0.0
    private val buzzPh = DoubleArray(FAN_BLADES)
    private val buzzAmp = FloatArray(FAN_BLADES) { if (it >= 6) Noise(40 + it).uniform(0.3f, 1f) else 0f }
    private val fanLp = Biq().lowPass(6000f, 0.707f, sr)
    private val rumbleLp = Biq()
    private val rumbleHp = Biq().highPass(40f, 0.707f, sr)
    private val roarBp = Array(2) { Biq() }
    private val fireBp = Biq().bandPass(160f, 0.6f, sr)
    private val crackleLp = Biq().lowPass(25f, 0.7f, sr)
    private val airBp = Array(2) { Biq() }
    private val hp = Array(2) { Biq().highPass(28f, 0.707f, sr) }
    private val litHold = Smooth(0.6f, sr)
    private val burner = Smooth(0.25f, sr)
    private var wasLit = false
    private val thump = FloatArray((0.5f * sr).toInt()).also { t ->
        val lp = Biq().lowPass(400f, 0.707f, sr)
        val n = Noise(77)
        for (i in t.indices) {
            val x = i.toFloat() / sr
            t[i] = (Dsp.sin1(48.0 * x) * 0.9f + lp.process(n.gauss())) * exp(-x / 0.12f) * 0.6f
        }
    }
    private var thumpPos = -1

    override fun render(out: FloatArray, outR: FloatArray, frames: Int) {
        val s = st
        if (s == null) { out.fill(0f, 0, frames); outR.fill(0f, 0, frames); return }
        val dt = frames.toFloat() / sr
        val kmh = s.speedKmh
        val speedN = speedNorm(kmh)
        val p = pedal.coerceIn(0f, 1f)
        // the spool: a lagged follow of the gearbox's revs (geared) or the pedal
        val geared = s.gear >= 1
        val target = if (geared) IDLE_SPOOL + (1 - IDLE_SPOOL) * ((s.rpm - IDLE) / (REDLINE - IDLE)).coerceIn(0f, 1f)
        else maxOf(IDLE_SPOOL + (1 - IDLE_SPOOL) * p.pow(1.3f), IDLE_SPOOL + 0.35f * (kmh / 150f).coerceIn(0f, 1f))
        val tau = if (target > spool) (if (geared) GEARED_UP_SEC else SPOOL_UP_SEC) else (if (geared) GEARED_DOWN_SEC else SPOOL_DOWN_SEC)
        val spool0 = spool
        spool += (target - spool) * min(1f, dt / tau * (0.6f + 0.8f * spool))    // spools up faster as it winds up
        val spN = ((spool - IDLE_SPOOL) / (1 - IDLE_SPOOL)).coerceIn(0f, 1f)

        val buzzAmt = ((spN - 0.55f) / 0.35f).coerceIn(0f, 1f)
        rumbleLp.lowPass(Dsp.lerp(90f, 220f, spN), 0.9f, sr)
        val rumble = 0.10f + 0.25f * spN * spN
        for (ch in 0..1) roarBp[ch].bandPass(Dsp.lerp(250f, 900f, spN), 0.5f, sr)
        val roar = 0.08f + 0.37f * spN * spN * spN
        val litNow = p > BURNER_PEDAL && spool > BURNER_SPOOL
        val lit = if (geared) litHold.block(if (litNow) 1f else 0f, frames) > 0.5f else litNow
        if (lit && !wasLit) thumpPos = 0
        wasLit = lit
        for (ch in 0..1) airBp[ch].bandPass(Dsp.lerp(600f, 2200f, speedN), 0.5f, sr)
        val air = speedN.pow(1.7f) * 0.10f

        val vol = volume * TRIM
        for (i in 0 until frames) {
            val sp = spool0 + (spool - spool0) * i / frames
            val spn = ((sp - IDLE_SPOOL) / (1 - IDLE_SPOOL)).coerceIn(0f, 1f)
            val shaft = sp * FAN_MAX_RPM / 60f
            // --- fan and buzzsaw ---------------------------------------------------------------
            fanPh += shaft * FAN_BLADES / sr
            var fan = Dsp.sin1(fanPh) + 0.3f * Dsp.sin1(fanPh * 2)
            fan *= 0.09f * (0.5f + 0.5f * spn)
            if (buzzAmt > 0f) {
                var b = 0f
                for (h in 6 until FAN_BLADES) {
                    buzzPh[h] += shaft * h / sr
                    b += buzzAmp[h] * Dsp.sin1(buzzPh[h] + h * 0.37)
                }
                fan += b * 0.012f * buzzAmt
            } else for (h in 6 until FAN_BLADES) buzzPh[h] += shaft * h / sr
            fan = fanLp.process(fan)
            var l = fan * 0.55f
            var r = fan * 0.45f
            // --- core whine -----------------------------------------------------------------------
            corePh += shaft * CORE_RATIO * 29f / sr
            val core = Dsp.sin1(corePh) * 0.02f * spn.pow(1.5f) * 0.5f
            l += core; r += core
            // --- rumble and roar ------------------------------------------------------------------
            val rb = rumbleHp.process(rumbleLp.process(pinks[0].next())) * rumble * 0.5f
            l += rb + roarBp[0].process(pinks[1].next()) * roar
            r += rb + roarBp[1].process(pinks[2].next()) * roar
            // --- afterburner ----------------------------------------------------------------------
            val bn = burner.next(if (lit) 1f else 0f)
            if (bn > 1e-3f) {
                // ragged pulsing, its spikes capped (left alone they set the peaks and the limiter
                // pulled the whole jet down)
                val crackle = min(1.5f, kotlin.math.abs(crackleLp.process(noise.gauss())) * 6f)
                val fire = fireBp.process(pinks[3].next()) * (0.6f + crackle) * bn * 0.25f
                l += fire; r += fire
            }
            if (thumpPos >= 0) {
                l += thump[thumpPos]; r += thump[thumpPos]
                if (++thumpPos >= thump.size) thumpPos = -1
            }
            // --- air ---------------------------------------------------------------------------------
            l += airBp[0].process(pinks[4].next()) * air
            r += airBp[1].process(pinks[5].next()) * air

            out[i] = Dsp.softClip(hp[0].process(l), 1.3f) * vol
            outR[i] = Dsp.softClip(hp[1].process(r), 1.3f) * vol
        }
    }
}
