package io.github.alamo3.openblue.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Warp bubble: a spaceship's field drive heard from inside the ship (virtual-car-sound
 * layered/warp.py, made real-time). No gears: its motion comes from acceleration, its size from
 * speed, and the space around it is alive -- events arrive at random, more of them the faster
 * and harder you go.
 *
 * Continuous: a Shepard-Risset drone gliding with acceleration (an endless climb pressing on, an
 * endless descent braking), an unstable field (pitch and level wander, now and then a flicker),
 * a throbbing pressure bubble, a turbulent tunnel rush, a comb shimmer tuned to the drone, and
 * a stereo field that opens with speed.
 * Random events: debris streaking past, hull groans under stress, subspace pressure booms,
 * energy arcing under heavy load.
 * Driving events: a launch from rest (inflate, then punch through -- or deflate if the pedal
 * comes off), a jump on a hard press when moving, a collapse on a quick lift, a drop out of
 * warp braking down through 20 km/h.
 */
internal class WarpVoice(sr: Int) : LayeredVoice(sr) {
    companion object {
        private const val BASE_HZ = 55f
        private const val OCTAVES = 7
        private const val GLIDE_OCT_PER_MS2 = 0.06f
        private const val LAUNCH_PEDAL = 0.75f
        private const val LAUNCH_MAX_KMH = 15f
        private const val INFLATE_SEC = 1.2f
        private const val TRIM = 5.2f              // as loud overall as the other sounds
        private const val MAX_EVENTS = 24
    }

    // --- one-shot events, each a small real-time generator --------------------------------------
    private enum class Kind { STREAK, GROAN, BOOM, ARC, SWELL, JUMP, INFLATE, PUNCH, DEFLATE, COLLAPSE, DROP }

    private inner class Ev(seed: Int) {
        var kind = Kind.STREAK
        var active = false
        var t = 0                      // samples since start
        var len = 0
        var gain = 0f
        var pan = 0.5f
        var p0 = 0f                    // a start pan / frequency, per kind
        var f = FloatArray(3)
        var ph = DoubleArray(3)
        val bp = Array(3) { Biq() }
        val lp = Biq()
        val n = Noise(1000 + seed)
        val pink = Pink(2000 + seed)
        var delay = 0                  // samples before it starts (staggered bursts)
        var clickP = 0f
        var punch = false              // INFLATE / SWELL: still on the pedal at the end?
    }

    private val events = Array(MAX_EVENTS) { Ev(it) }
    private val rnd = Noise(41)

    private fun spawn(kind: Kind, len: Float, gain: Float, pan: Float = 0.5f): Ev? {
        val e = events.firstOrNull { !it.active } ?: return null
        e.kind = kind; e.active = true; e.t = 0; e.len = max(1, (len * sr).toInt())
        e.gain = gain; e.pan = pan; e.punch = false; e.delay = 0
        for (k in 0..2) e.ph[k] = 0.0
        return e
    }

    // --- continuous layers ---------------------------------------------------------------------
    private val dronePh = DoubleArray(OCTAVES)
    private val partialInc = DoubleArray(OCTAVES)
    private val partialEnv = FloatArray(OCTAVES)
    private val partialPan = FloatArray(OCTAVES)
    private var glide = 0.0
    private var lfoT = 0.0
    private val wanderPitch = Wander(0.6f, sr, 42)
    private val wanderLevel = Wander(0.4f, sr, 43)
    private val wanderThrob = Wander(0.3f, sr, 44)
    private val wanderTurb = Wander(1.2f, sr, 45)
    private val wanderTunnel = Wander(0.5f, sr, 46)
    private var flickerT = -1; private var flickerLen = 1; private var flickerDepth = 0f
    private var bubblePh = 0.0
    private var throbPh = 0.0
    private val tunnelBp = Array(2) { Biq() }
    private val pinkL = Pink(47)
    private val pinkR = Pink(48)
    private val shimmerHp = Biq().highPass(2500f, 0.707f, sr)
    private val shimmerBp = Array(4) { Biq() }
    private val sparkleLp = Biq().lowPass(12f, 0.707f, sr)
    private val noise = Noise(49)
    private val droneLevel = Smooth(0.05f, sr)
    private val bubbleLevel = Smooth(0.2f, sr)
    private val shimmerLevel = Smooth(0.3f, sr)
    private val accelS = Smooth(0.3f, sr)
    private val hp = Array(2) { Biq().highPass(25f, 0.707f, sr) }

    // --- triggers ----------------------------------------------------------------------------------
    private var lastPedal = 0f
    private var pedalRate = 0f
    private var lastKmh = 0f
    private var sinceLaunch = 1e9f
    private var sinceJump = 1e9f
    private var sinceCollapse = 1e9f

    override fun render(out: FloatArray, outR: FloatArray, frames: Int) {
        val s = st
        if (s == null) { out.fill(0f, 0, frames); outR.fill(0f, 0, frames); return }
        val dt = frames.toFloat() / sr
        val kmh = s.speedKmh
        val speedN = speedNorm(kmh)
        val p = pedal.coerceIn(0f, 1f)
        val load = s.load.coerceIn(0f, 1f)
        val acc = accelS.block(accel, frames)
        pedalRate += ((p - lastPedal) / dt - pedalRate) * min(1f, dt / 0.04f)

        // --- driving events ---------------------------------------------------------------------
        sinceLaunch += dt; sinceJump += dt; sinceCollapse += dt
        if (lastPedal < LAUNCH_PEDAL && p >= LAUNCH_PEDAL && kmh < LAUNCH_MAX_KMH && sinceLaunch > 4f) {
            sinceLaunch = 0f
            spawn(Kind.INFLATE, INFLATE_SEC, 1f)
        } else if (lastPedal < 0.6f && p >= 0.6f && pedalRate > 2f && sinceJump > 3f && sinceLaunch > 3f) {
            sinceJump = 0f
            spawn(Kind.SWELL, 1.3f, 0.22f)
        }
        if (pedalRate < -2.5f && sinceCollapse > 1f) {
            sinceCollapse = 0f
            spawn(Kind.COLLAPSE, 1.3f, 0.5f)
        }
        if (lastKmh >= 20f && kmh < 20f && acc < -1.5f) spawn(Kind.DROP, 1.6f, 0.5f)
        lastPedal = p; lastKmh = kmh
        // an inflating field or a swell keeps checking: still pressing at its end?
        for (e in events) if (e.active && (e.kind == Kind.INFLATE || e.kind == Kind.SWELL)) {
            e.punch = p > (if (e.kind == Kind.INFLATE) 0.7f else 0.8f)
        }

        // --- random events (rates per second) -----------------------------------------------
        val stress = (kotlin.math.abs(acc) / 3f).coerceIn(0f, 1f)
        val heavy = ((load - 0.5f) / 0.5f).coerceIn(0f, 1f) * (0.3f + 0.7f * speedN)
        if (rnd.chance((0.15f + 2.2f * speedN.pow(1.2f)) * dt)) streak(rnd.uniform(0.25f, 0.9f) * (1.25f - 0.6f * speedN), null)
        if (rnd.chance((0.02f + 0.5f * stress.pow(1.5f)) * dt)) groan(stress)
        if (rnd.chance((0.03f + 0.10f * speedN) * dt)) boom()
        if (rnd.chance(1.8f * heavy * dt)) arc()
        if (flickerT < 0 && rnd.chance((0.05f + 0.25f * speedN * (0.4f + 0.6f * load)) * dt)) {
            flickerT = 0; flickerLen = (rnd.uniform(0.06f, 0.18f) * sr).toInt(); flickerDepth = rnd.uniform(0.4f, 0.8f)
        }

        // --- per-block settings ----------------------------------------------------------------
        val centre = Dsp.lerp(3f, 4.2f, speedN)
        val droneT = 0.10f + 0.08f * speedN + 0.10f * load
        val bubbleT = 0.25f + 0.35f * load + 0.2f * speedN
        val bubbleHz = Dsp.lerp(38f, 52f, speedN)
        val throbHz = Dsp.lerp(0.8f, 7f, speedN)
        val tunnelHz = Dsp.lerp(300f, 1400f, speedN) * (1f + 0.2f * wanderTunnel.next(frames))
        for (ch in 0..1) tunnelBp[ch].bandPass(tunnelHz, 0.5f, sr)
        val turb = (1f + 0.45f * wanderTurb.next(frames)).coerceIn(0.2f, 2f) * (0.01f + 0.10f * speedN.pow(1.3f))
        val combHz = BASE_HZ * 2f.pow(3f + (glide - kotlin.math.floor(glide)).toFloat())
        val harm = floatArrayOf(8f, 12f, 16f, 24f)
        for (k in 0..3) shimmerBp[k].bandPass(combHz * harm[k], 18f, sr)
        val shimmerT = 0.02f + 0.10f * speedN + 0.08f * load
        val width = Dsp.lerp(0.8f, 1.8f, speedN)
        for (e in events) if (e.active) retune(e)
        // the drone's partials change slowly: their pitch, envelope and pan are set per block
        val wobble = 2.0.pow(18.0 * wanderPitch.next(frames) / 1200.0)
        glide += acc * GLIDE_OCT_PER_MS2 * dt
        lfoT += dt
        for (k in 0 until OCTAVES) {
            val pos = ((k + glide) % OCTAVES + OCTAVES) % OCTAVES
            partialInc[k] = BASE_HZ * 2.0.pow(pos) * wobble / sr
            partialEnv[k] = exp(-0.5 * ((pos - centre) / 1.25).pow(2)).toFloat()
            partialPan[k] = 0.5f + 0.25f * Dsp.sin1((0.05 + 0.02 * k) * lfoT + k / (2 * PI))
        }

        val vol = volume * TRIM
        for (i in 0 until frames) {
            // --- the field: wander and flicker ------------------------------------------------
            var field = 1f + 0.22f * wanderLevel.next()
            if (flickerT >= 0) {
                val sh = sin(PI * flickerT / flickerLen).toFloat()
                field *= 1f - flickerDepth * sh * sh
                if (++flickerT >= flickerLen) flickerT = -1
            }
            // --- drone --------------------------------------------------------------------------
            var dl = 0f
            var dr = 0f
            for (k in 0 until OCTAVES) {
                dronePh[k] += partialInc[k]
                val x = (Dsp.sin1(dronePh[k]) + 0.15f * Dsp.sin1(dronePh[k] * 3)) * partialEnv[k]
                dl += x * partialPan[k]; dr += x * (1f - partialPan[k])
            }
            val dg = droneLevel.next(droneT) * field * 0.5f
            var l = dl * dg
            var r = dr * dg
            // --- bubble ------------------------------------------------------------------------------
            throbPh += throbHz * (1f + 0.15f * wanderThrob.next()) / sr
            val th = Dsp.sin1(throbPh)
            bubblePh += bubbleHz / sr
            val bub = Dsp.sin1(bubblePh) * (0.55f + 0.45f * th * th) * bubbleLevel.next(bubbleT) * 0.32f * field * 0.5f
            l += bub; r += bub
            // --- tunnel --------------------------------------------------------------------------------
            l += tunnelBp[0].process(pinkL.next()) * turb
            r += tunnelBp[1].process(pinkR.next()) * turb
            // --- shimmer ---------------------------------------------------------------------------------
            val nz = shimmerHp.process(noise.gauss())
            var sh = 0f
            for (k in 0..3) sh += shimmerBp[k].process(nz)
            sh *= kotlin.math.abs(sparkleLp.process(noise.gauss())) * 4f * shimmerLevel.next(shimmerT) * 0.45f
            l += sh * 0.6f; r += sh * 0.4f
            // --- events ------------------------------------------------------------------------------
            for (e in events) if (e.active) {
                if (e.delay > 0) { e.delay--; continue }
                val v = eventSample(e)
                l += v * e.pan; r += v * (1f - e.pan)
                if (++e.t >= e.len) finish(e)
            }
            // --- width ----------------------------------------------------------------------------
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            out[i] = Dsp.softClip(hp[0].process(mid + side), 1.4f) * vol
            outR[i] = Dsp.softClip(hp[1].process(mid - side), 1.4f) * vol
        }
    }

    // --- events ------------------------------------------------------------------------------------
    private fun streak(dur: Float, panBias: Float?) {
        val p0 = panBias ?: if (rnd.chance(0.5f)) rnd.uniform(0f, 0.3f) else rnd.uniform(0.7f, 1f)
        val e = spawn(Kind.STREAK, dur, rnd.uniform(0.03f, 0.08f)) ?: return
        e.p0 = p0; e.f[0] = rnd.uniform(700f, 3800f)
        e.gain *= 0.4f + 0.6f * speedNorm(st?.speedKmh ?: 0f)
    }

    private fun groan(stress: Float) {
        val e = spawn(Kind.GROAN, rnd.uniform(0.6f, 1.6f), rnd.uniform(0.15f, 0.3f) * (0.4f + 0.6f * stress), rnd.uniform(0.3f, 0.7f)) ?: return
        for (k in 0..2) e.f[k] = rnd.uniform(70f, 380f)
        e.lp.lowPass(600f, 0.707f, sr)
    }

    private fun boom() {
        val e = spawn(Kind.BOOM, 2.4f, 0.5f) ?: return
        e.f[0] = rnd.uniform(28f, 44f)
        e.lp.lowPass(140f, 0.707f, sr)
    }

    private fun arc() {
        val e = spawn(Kind.ARC, rnd.uniform(0.08f, 0.35f), 0.25f, rnd.uniform(0.2f, 0.8f)) ?: return
        e.clickP = rnd.uniform(0.002f, 0.01f)
        e.bp[0].bandPass(rnd.uniform(3500f, 7000f), 3f, sr)
        e.lp.highPass(2500f, 0.707f, sr)
    }

    /** What follows an event that ends: the punch after an inflate or a swell, if still pressing. */
    private fun finish(e: Ev) {
        e.active = false
        when (e.kind) {
            Kind.INFLATE -> if (e.punch) {
                spawn(Kind.PUNCH, 2.2f, 1f)
                for (k in 0 until 10) {                       // a burst of star streaks, outward both ways
                    val s = spawn(Kind.STREAK, rnd.uniform(0.25f, 0.6f), rnd.uniform(0.04f, 0.09f)) ?: break
                    s.p0 = if (k % 2 == 0) 0.85f else 0.15f
                    s.f[0] = rnd.uniform(900f, 4200f)
                    s.delay = (rnd.uniform(0f, 1.2f) * sr).toInt()   // staggered over 1.2 s
                }
            } else spawn(Kind.DEFLATE, 1.0f, 1f)
            Kind.SWELL -> if (e.punch) spawn(Kind.JUMP, 2.5f, 0.6f)
            else -> {}
        }
    }

    /** Per-block filter settings for the events whose filters move. */
    private fun retune(e: Ev) {
        val u = (e.t.toFloat() / e.len).coerceIn(0f, 1f)
        when (e.kind) {
            Kind.STREAK -> e.bp[0].bandPass(e.f[0] * Dsp.lerp(1.35f, 0.65f, u), 7f, sr)
            Kind.GROAN -> for (k in 0..2) e.bp[k].bandPass(e.f[k] * Dsp.lerp(1f, 0.9f, u), 25f, sr)
            Kind.SWELL -> e.bp[0].bandPass(Dsp.lerp(400f, 3000f, u), 1f, sr)
            Kind.JUMP -> { e.bp[0].bandPass(2500f, 0.5f, sr); e.lp.lowPass(Dsp.lerp(1800f, 200f, u), 0.707f, sr) }
            Kind.INFLATE -> { e.bp[0].bandPass(Dsp.lerp(200f, 2600f, u), 1.2f, sr); e.lp.highPass(5000f, 0.707f, sr) }
            Kind.PUNCH -> { e.bp[0].bandPass(3000f, 0.6f, sr); e.bp[1].bandPass(Dsp.lerp(200f, 4000f, u * 2.2f / 1.6f), 1f, sr) }
            Kind.DEFLATE -> e.bp[0].bandPass(Dsp.lerp(2000f, 200f, u), 1f, sr)
            Kind.COLLAPSE -> { e.bp[0].bandPass(1400f, 0.9f, sr); e.lp.lowPass(Dsp.lerp(6000f, 300f, u), 0.7f, sr) }
            else -> {}
        }
    }

    /** One mono sample of an event (panned by the caller; streaks and collapses sweep their pan). */
    private fun eventSample(e: Ev): Float {
        val ts = e.t.toFloat() / sr
        val u = e.t.toFloat() / e.len
        return when (e.kind) {
            Kind.STREAK -> {
                val fc = e.f[0] * Dsp.lerp(1.35f, 0.65f, u)
                e.ph[0] += fc * 0.5 / sr
                val env = sin(PI * u).toFloat().pow(3)
                e.pan = Dsp.lerp(e.p0, 1f - e.p0, u)
                (e.bp[0].process(e.n.gauss()) + Dsp.sin1(e.ph[0]) * 0.25f) * env * e.gain
            }
            Kind.GROAN -> {
                val dur = e.len.toFloat() / sr
                val exc = e.lp.process(e.n.gauss()) * (1f - exp(-ts / 0.05f)) * exp(-ts / (dur * 0.4f))
                (e.bp[0].process(exc) + e.bp[1].process(exc) + e.bp[2].process(exc)) * e.gain
            }
            Kind.BOOM -> {
                val env = (1f - exp(-ts / 0.12f)) * exp(-ts / 0.7f)
                e.ph[0] += e.f[0].toDouble() / sr
                (Dsp.sin1(e.ph[0]) * 0.8f + e.lp.process(e.n.gauss()) * 0.6f) * env * e.gain
            }
            Kind.ARC -> {
                val click = if (e.n.chance(e.clickP)) 1f else 0f
                e.lp.process(e.bp[0].process(click) + 0.15f * e.n.gauss()) * sin(PI * u).toFloat() * e.gain
            }
            Kind.SWELL -> {
                e.ph[0] += Dsp.lerp(70f, 700f, u * u) / sr
                (Dsp.sin1(e.ph[0]) * 0.5f + e.bp[0].process(e.n.gauss()) * 0.6f) * u * u * e.gain
            }
            Kind.JUMP -> {
                e.ph[0] += 36.0 / sr
                val flash = e.bp[0].process(e.n.gauss()) * exp(-ts / 0.15f) * 0.35f
                val tail = e.lp.process(e.pink.next()) * exp(-ts / 0.8f) * 0.35f
                val thud = Dsp.sin1(e.ph[0]) * (1f - exp(-ts / 0.01f)) * exp(-ts / 0.45f) * 0.9f
                (flash + tail + thud) * e.gain
            }
            Kind.INFLATE -> {
                e.ph[0] += Dsp.lerp(30f, 56f, u) / sr
                val g = u * u
                (Dsp.sin1(e.ph[0]) * g * 0.9f + e.bp[0].process(e.n.gauss()) * g * 0.35f +
                    e.lp.process(e.n.gauss()) * u * u * u * 0.12f) * e.gain
            }
            Kind.PUNCH -> {
                e.ph[0] += Dsp.lerp(90f, 28f, ts / 0.8f) / sr
                val sub = Dsp.sin1(e.ph[0]) * (1f - exp(-ts / 0.008f)) * exp(-ts / 0.6f)
                val flash = e.bp[0].process(e.n.gauss()) * exp(-ts / 0.12f) * 0.35f
                val tunnel = e.bp[1].process(e.pink.next()) * sin(PI * min(1f, ts / 2.2f)).toFloat().pow(1.5f) * 0.35f
                (sub + flash + tunnel) * e.gain
            }
            Kind.DEFLATE -> {
                e.ph[0] += Dsp.lerp(56f, 24f, u) / sr
                (Dsp.sin1(e.ph[0]) * 0.8f + e.bp[0].process(e.n.gauss()) * 0.3f) * exp(-ts / 0.35f) * e.gain
            }
            Kind.COLLAPSE -> {
                e.pan = Dsp.lerp(0.77f, 0.23f, u)                    // sweeps across, as the prototype's
                e.lp.process(e.bp[0].process(e.n.gauss())) * exp(-ts / 0.35f) * e.gain
            }
            Kind.DROP -> {
                e.ph[0] += Dsp.lerp(600f, 50f, u) / sr
                e.ph[1] += 40.0 / sr
                val d = (ts - 0.9f) / 0.12f
                (Dsp.sin1(e.ph[0]) * exp(-ts / 0.5f) * 0.4f + Dsp.sin1(e.ph[1]) * exp(-d * d) * 0.8f) * e.gain
            }
        }
    }
}
