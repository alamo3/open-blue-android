package io.github.alamo3.openblue.sound

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flat-6: a layered, physically-minded boxer six, driven by the gearbox's revs and load
 * (virtual-car-sound layered/flat6.py, made real-time).
 *
 *  - exhaust: one pulse per firing (order 3), firing order 1-6-2-4-3-5. Each firing strikes its
 *    bank's formant resonators, brighter with load; the right bank rings a touch higher and
 *    weaker -- the boxer's order-1.5 rasp and low-rev burble -- and each firing varies a little.
 *    Pipe resonances and a muffler that opens with load and revs shape it
 *  - intake: induction roar in a band climbing with the revs, pulsed by the firing, with load
 *  - mechanical: a faint timing-chain whine and valvetrain tick
 *  - overrun: off the throttle the exhaust falls back to a soft burble; after lifting off a hard
 *    pull, crackles and pops
 *  - shifts and the limiter: an upshift's torque handover and the limiter's fuel cuts drop the
 *    firing out (the shift sound itself comes from [DriveEffects])
 */
internal class Flat6Voice(sr: Int) : LayeredVoice(sr) {
    companion object {
        const val IDLE = 900f
        const val REDLINE = 8500f
        private val FIRING_ORDER = intArrayOf(0, 5, 1, 3, 2, 4)        // cylinders 1-6-2-4-3-5
        private val CYL_GAIN = floatArrayOf(1.00f, 0.93f, 1.05f, 0.97f, 1.02f, 0.95f)
        // formants of one exhaust pulse: Hz, decay s, level light / full load
        private val F_HZ = floatArrayOf(170f, 480f, 1250f, 3100f)
        private val F_DECAY = floatArrayOf(0.0045f, 0.0018f, 0.0009f, 0.00035f)
        private val F_LO = floatArrayOf(1.00f, 0.45f, 0.10f, 0.02f)
        private val F_HI = floatArrayOf(1.00f, 0.85f, 0.55f, 0.22f)
        private const val RIGHT_TUNE = 1.10f
        private const val WEAK_BANK_LOW_REVS = 0.66f
        private const val WEAK_BANK_HIGH_REVS = 0.86f
        private const val TRIM = 4.9f              // hard pulls as loud as the recorded engines'
        private const val POP_SEC = 0.03f
    }

    private val noise = Noise(11)
    private val pink = Pink(12)
    private val res = Array(2) { Array(4) { Resonator() } }          // per bank
    private val attack = FloatArray(2)                                 // a pulse's soft onset, per bank
    private val attackK = 1f - exp(-1f / (0.00025f * sr))
    private val grit = Biq()
    private val tailpipe = Array(2) { Biq() }
    private val header = Array(2) { Biq() }
    private val muffler = Array(2) { Biq() }
    private val intakeBp = Biq()
    private val tickBp = Biq()
    private val shelf = Array(2) { Biq().lowShelf(140f, 0.7f, 1f, sr) }
    private val hp = Array(2) { Biq().highPass(28f, 0.707f, sr) }
    private val level = Smooth(0.02f, sr)
    private val intakeLevel = Smooth(0.05f, sr)

    private var firePhase = 0.0
    private var firing = 0L
    private var sinceFire = 1e9f
    private var chainPhase = 0.0
    private var liftOffsSeen = -1
    private var crackleLeft = 0f
    private val pops = Array(6) { FloatArray((POP_SEC * sr).toInt()) }
    private val popPos = IntArray(6) { -1 }
    private val popGain = FloatArray(6)
    private var popPan = 0.0

    init {
        // a few pop variants, made once: a noise crack and a low thump
        for ((k, p) in pops.withIndex()) {
            val bp = Biq().bandPass(1800f, 0.7f, sr)
            val n = Noise(100 + k)
            for (i in p.indices) {
                val t = i.toFloat() / sr
                p[i] = bp.process(n.gauss()) * exp(-t / 0.006f) + Dsp.sin1(85.0 * t) * exp(-t / 0.012f) * 0.8f
            }
        }
    }

    override fun render(out: FloatArray, outR: FloatArray, frames: Int) {
        val s = st
        if (s == null) { out.fill(0f, 0, frames); outR.fill(0f, 0, frames); return }
        val rpm = max(s.rpm, IDLE * 0.95f)
        val rpmN = ((rpm - IDLE) / (REDLINE - IDLE)).coerceIn(0f, 1f)
        val load = s.load.coerceIn(0f, 1f)
        val overrun = (s.overrun ?: 0f).coerceIn(0f, 1f)
        val fireHz = rpm / 20f                                    // 6 cylinders, 3 firings a rev
        val bright = (0.15f + 0.85f * load * (0.5f + 0.5f * rpmN)).coerceIn(0f, 1f) * (1f - 0.6f * overrun)
        val weak = Dsp.lerp(WEAK_BANK_LOW_REVS, WEAK_BANK_HIGH_REVS, rpmN)
        var fireAmp = 1f - 0.85f * s.shifting.coerceIn(0f, 1f) * (if (load > 0.3f) 1f else 0f)
        if (s.cut == true) fireAmp = 0.12f
        val formantGain = FloatArray(4) { F_LO[it] + (F_HI[it] - F_LO[it]) * bright }
        for (b in 0..1) for (f in 0..3) res[b][f].set(F_HZ[f] * (if (b == 1) RIGHT_TUNE else 1f), F_DECAY[f], sr)
        grit.bandPass(2200f, 0.8f, sr)
        val mute = Dsp.lerp(2200f, 8000f, load * (0.35f + 0.65f * rpmN)) * (1f - 0.45f * overrun)
        for (ch in 0..1) {
            tailpipe[ch].peak(92f, 2.5f, 3f, sr)
            header[ch].peak(270f, 2f, 3f, sr)
            muffler[ch].lowPass(mute, 0.8f, sr)
        }
        val lvlTarget = (0.45f + 0.55f * load.pow(1.3f)) * (0.55f + 0.45f * rpmN) * (1f - overrun) +
            0.10f * overrun * (0.4f + 0.6f * rpmN)
        intakeBp.bandPass(Dsp.lerp(700f, 2600f, rpmN), 1.1f, sr)
        val intakeTarget = load.pow(1.4f) * (0.25f + 0.75f * rpmN.pow(1.3f)) * 0.55f
        tickBp.bandPass(5200f, 2f, sr)
        val pulseTau = max(0.25f / fireHz, 0.0006f)

        // crackle after lifting off a hard pull
        if (liftOffsSeen >= 0 && s.liftOffs != liftOffsSeen) crackleLeft = 1.6f
        liftOffsSeen = s.liftOffs
        val dtBlock = frames.toFloat() / sr
        val popRate = (crackleLeft / 1.6f) * overrun * (8f + 30f * rpmN)
        crackleLeft = max(0f, crackleLeft - dtBlock)

        val vol = volume * TRIM
        for (i in 0 until frames) {
            // --- firing: strike the bank's resonators -----------------------------------
            firePhase += fireHz / sr
            var hitL = 0f
            var hitR = 0f
            if (firePhase >= 1.0) {
                firePhase -= 1.0
                firing++
                sinceFire = 0f
                val cyl = FIRING_ORDER[(firing % 6).toInt()]
                val right = cyl % 2 == 1
                val g = CYL_GAIN[cyl] * (if (right) weak else 1f) * (1f + 0.10f * noise.gauss()) * fireAmp
                if (right) hitR = g else hitL = g
            } else sinceFire += 1f / sr
            // each firing is a smoothed impulse (~0.25 ms onset, unit area): the pulse's soft attack
            attack[0] += (hitL / attackK - attack[0]) * attackK
            attack[1] += (hitR / attackK - attack[1]) * attackK
            val bankL = exhaust(0, attack[0] * attackK, formantGain)
            val bankR = exhaust(1, attack[1] * attackK, formantGain)
            val g = 1f + 0.35f * load * grit.process(noise.gauss())
            var l = (bankL * 0.62f + bankR * 0.38f) * g
            var r = (bankL * 0.38f + bankR * 0.62f) * g
            l = muffler[0].process(header[0].process(tailpipe[0].process(l)))
            r = muffler[1].process(header[1].process(tailpipe[1].process(r)))
            val lv = level.next(lvlTarget)
            l *= lv; r *= lv

            // --- intake -----------------------------------------------------------------------
            val pulseEnv = exp(-sinceFire / pulseTau)
            val roar = intakeBp.process(pink.next()) * (0.45f + 0.55f * pulseEnv) * intakeLevel.next(intakeTarget)
            l += roar * 0.5f; r += roar * 0.5f

            // --- mechanical --------------------------------------------------------------------
            chainPhase += rpm / 60.0 * 21.0 / sr
            val mech = Dsp.sin1(chainPhase) * 0.012f * (0.3f + rpmN) +
                tickBp.process(noise.gauss()) * pulseEnv * pulseEnv * pulseEnv * 0.05f * (0.2f + rpmN)
            l += mech * 0.5f; r += mech * 0.5f

            // --- overrun crackle --------------------------------------------------------------
            if (popRate > 0f && noise.chance(popRate / sr)) {
                val k = popPos.indexOfFirst { it < 0 }
                if (k >= 0) { popPos[k] = 0; popGain[k] = noise.uniform(0.4f, 1f) * 0.35f }
            }
            popPan += 3.0 / sr / (2 * Math.PI)
            for (k in pops.indices) if (popPos[k] >= 0) {
                val v = pops[k][popPos[k]] * popGain[k]
                val pan = 0.5f + 0.2f * Dsp.sin1(popPan)
                l += v * pan; r += v * (1f - pan)
                if (++popPos[k] >= pops[k].size) popPos[k] = -1
            }

            // --- in the cabin ---------------------------------------------------------------------
            l = hp[0].process(shelf[0].process(l))
            r = hp[1].process(shelf[1].process(r))
            out[i] = Dsp.softClip(l, 1.6f) * vol
            outR[i] = Dsp.softClip(r, 1.6f) * vol
        }
    }

    private fun exhaust(bank: Int, hit: Float, gain: FloatArray): Float {
        var y = 0f
        for (f in 0..3) y += res[bank][f].process(hit) * gain[f]
        return y
    }
}
