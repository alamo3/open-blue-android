package io.github.alamo3.openblue.sound

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A recorded engine, played the way racing games do it (and the way the virtual-car-sound
 * Raspberry Pi's loop engine does): loops recorded at several rpm points, each played back at
 * the rate that puts it at the engine's current revs, the two either side crossfaded; separate
 * light / full / off-throttle layers blended by load; the timbre opened up under load and darkened
 * on the overrun; the set's own shift sound on upshifts and its exhaust pops on lift-offs.
 *
 * The sets live in assets/loopsets/<id>/ (set.json + mono 16-bit WAVs), exported by
 * export_loopsets.py in the virtual-car-sound project. Pure Kotlin apart from [open], so it can
 * be rendered off the phone; with no [open] it plays silence.
 *
 * Baked sets (export_baked.py: the Pi's cinematic voices rendered at a grid of speeds) are keyed
 * by road speed instead of revs ("key": "speed"): the loops either side of the current speed are
 * crossfaded, each stretched so both sit at the pitch in between, cruise ("light") and full-pedal
 * ("on") series blended by the pedal, with none of the engine's tone shaping or level law.
 */
class LoopVoice(
    private val sampleRate: Int,
    private val open: ((String) -> InputStream?)? = null,
    seed: Int = 11,
) {
    @Volatile var volume = 0.8f
    /** Which set to play (a folder under assets/loopsets); loaded on first use. */
    @Volatile var setId: String? = null

    @Volatile private var tRpm = 0f
    @Volatile private var tLoad = 0f
    @Volatile private var tEngaged = false
    @Volatile private var tShifts = 0
    @Volatile private var tLimiter = false
    @Volatile private var tLiftOffs = 0
    @Volatile private var tSpeed = 0f
    @Volatile private var tOverrun: Float? = null
    @Volatile private var tShifting = 0f
    @Volatile private var tCut: Boolean? = null
    @Volatile private var tDownshifts = 0
    @Volatile private var tThrottle = 0f
    /** Baked voices: pitch multiplier from the virtual gears (see [DriveEffects]). */
    @Volatile var pitchMul = 1f

    fun set(st: EngineState) {
        tRpm = st.rpm
        tLoad = st.load
        tEngaged = st.engaged
        tShifts = st.shifts
        tLimiter = st.limiter
        tLiftOffs = st.liftOffs
        tSpeed = st.speedKmh
        tOverrun = st.overrun
        tShifting = st.shifting
        tCut = st.cut
        tDownshifts = st.downshifts
        tThrottle = st.throttle
    }

    companion object {
        const val ASSET_RATE = 48000
        private const val ON_MIN = 0.25f         // full-load loops never drop below this
        private const val OFF_LEVEL = 0.8f       // coasting loops on the overrun
        private const val TONE_FULL_HZ = 12000f
        private const val TONE_LIGHT_HZ = 4500f
        private const val TONE_OVERRUN_HZ = 2200f
        private const val DRIVE = 0.8f           // extra saturation at full load
        private const val BITE_HZ = 1800f
        private const val BITE_DB = 3f           // presence lift at full load
        private const val LIMITER_CUT = 0.65f
        private const val LIMITER_HZ = 12f
        private const val SHIFT_LEVEL = 0.7f
        private const val POP_LEVEL = 0.6f
        private const val CRACKLE_RATE = 5f      // overrun pops per second at the redline
        private const val TRIM = 1.0f            // the Pi's level (SoundVoice limits the peaks)
        private const val BODY_HZ = 160f         // recorded engines: low shelf for weight...
        private const val BODY_DB = 4f           // ...this much below BODY_HZ
        private const val SHIFT_DIP = 0.1f       // level dip through a shift's torque cut (the shift sound carries it)
        private const val BAKED_TRIM = 0.9f      // (SoundVoice limits the peaks)
        /**
         * Every set is scaled so its full-load loops sit at this rms: recordings and renders
         * arrive at very different levels (0.15-0.64). Recorded engines aim higher: their peaks
         * stand far above their average, so they need more gain to sound as loud.
         */
        private const val TARGET_RMS_RECORDED = 0.6f
        private const val TARGET_RMS_BAKED = 0.55f
        private const val STANDSTILL_HOLD = 3f   // baked voices: fade out after this long stopped
        private const val STANDSTILL_FADE = 2.5f
    }

    /** A loop's samples: [pcm] left, [pcmR] right (the same array for a mono file). */
    private class Loop(val pcm: FloatArray, val pcmR: FloatArray, val ref: Float) {
        var pos = 0.0
        var gain = 0f
        var rate = 1.0                           // playback speed relative to the file
    }

    private class Shot(val pcm: FloatArray, val pcmR: FloatArray) {
        var pos = -1                              // -1: not playing
    }

    private class LoopSet(
        val id: String, val cylinders: Int, val idleRpm: Float, val redline: Float,
        val on: List<Loop>, val light: List<Loop>, val off: List<Loop>,
        val shiftUp: Shot?, val pops: List<Shot>, val shiftDown: Shot?,
        /** The files' sample rate. */
        val assetRate: Int,
        /** Baked sets: the road speed (km/h) of each on / light loop; null for rpm-keyed sets. */
        val speeds: FloatArray?,
        /** Spool-keyed sets (a jet): the loops are placed by engine spool 0..1, not speed. */
        val spool: Spool? = null,
        /** Spool-keyed sets: an afterburner layer, lit near full pedal once spooled up. */
        val boost: Loop? = null,
        val boostPedal: Float = 2f,
    ) {
        val all = on + light + off + listOfNotNull(boost)
        /** Gain that brings this set to the common loudness. */
        val norm: Float = run {
            val rms = on.ifEmpty { light }.map { l -> sqrt(l.pcm.fold(0.0) { a, x -> a + x * x } / l.pcm.size).toFloat() }.sorted()
            val median = rms[rms.size / 2]
            ((if (speeds != null) TARGET_RMS_BAKED else TARGET_RMS_RECORDED) / max(median, 1e-3f)).coerceIn(0.5f, 4f)
        }
    }

    private val rnd = Random(seed)
    private var loaded: LoopSet? = null
    private var failed: String? = null
    private var rpm = 0f
    private var master = 0f
    private var limiterT = 0f
    private var shiftsSeen = -1
    private var liftOffsSeen = -1
    private var downshiftsSeen = -1
    private val popQueue = ArrayList<Float>()     // seconds until each queued pop
    private var stoppedFor = 0f
    // tone filters, one per channel
    private val lp = Biquad()
    private val bite = Biquad()
    private val body = Biquad()
    private val lpR = Biquad()
    private val biteR = Biquad()
    private val bodyR = Biquad()
    // the current output sample of mixLoops, left and right
    private var mixL = 0f
    private var mixR = 0f

    // --- loading ----------------------------------------------------------------------
    private fun load(id: String): LoopSet? {
        val opener = open ?: return null
        val json = opener("loopsets/$id/set.json")?.use { String(it.readBytes()) } ?: return null
        val o = org.json.JSONObject(json)
        fun wav(name: String) = opener("loopsets/$id/$name")?.use { readWavStereo(it.readBytes()) }
        fun loop(j: org.json.JSONObject?) = j?.let { e -> wav(e.getString("file"))?.let { (l, r) -> Loop(l, r, e.getDouble("ref_hz").toFloat()) } }
        fun loops(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { loop(a.optJSONObject(it)) } } ?: emptyList()
        fun shotFile(name: String?) = name?.let { wav(it)?.let { (l, r) -> Shot(l, r) } }
        fun shot(key: String) = shotFile(o.optString(key, "").takeIf { it.isNotEmpty() && it != "null" })
        val pops = o.optJSONArray("pops")?.let { a -> (0 until a.length()).mapNotNull { shotFile(a.optString(it).takeIf { s -> s.isNotEmpty() }) } } ?: emptyList()
        val on = loops("on")
        // "light" is one loop for an rpm-keyed set, a series (one per speed) for a baked one
        val light = if (o.optJSONArray("light") != null) loops("light") else listOfNotNull(loop(o.optJSONObject("light")))
        if (on.isEmpty() && light.isEmpty()) return null
        val key = o.optString("key")
        val speeds = if (key == "speed" || key == "spool") {
            o.getJSONArray("speeds").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                .takeIf { g -> (on.isEmpty() || on.size == g.size) && (light.isEmpty() || light.size == g.size) } ?: return null
        } else {
            if (on.isEmpty()) return null
            null
        }
        val spool = if (key == "spool") o.optJSONObject("spool").let { j ->
            Spool(j?.optDouble("idle_frac", 0.0)?.toFloat() ?: 0f, j?.optDouble("up_per_sec", 0.9)?.toFloat() ?: 0.9f,
                  j?.optDouble("down_per_sec", 1.1)?.toFloat() ?: 1.1f)
        } else null
        val boostJson = o.optJSONObject("boost")
        return LoopSet(
            id, o.optInt("cylinders", 2), o.optDouble("idle_rpm", 0.0).toFloat(), o.optDouble("redline_rpm", 1.0).toFloat(),
            on, light, loops("off"), shot("shift_up"), pops, shot("shift_down"), o.optInt("sample_rate", ASSET_RATE), speeds,
            spool, loop(boostJson), boostJson?.optDouble("pedal", 2.0)?.toFloat() ?: 2f,
        )
    }

    // --- playing ----------------------------------------------------------------------
    /** Equal-power weights for the two loops either side of log-frequency [lf]. */
    private fun xfade(lf: Float, refs: List<Loop>, w: FloatArray) {
        w.fill(0f, 0, refs.size)
        val lr = refs.map { ln(it.ref) }
        when {
            lf <= lr.first() -> w[0] = 1f
            lf >= lr.last() -> w[refs.size - 1] = 1f
            else -> {
                val i = (0 until refs.size - 1).last { lr[it] <= lf }
                val u = (lf - lr[i]) / (lr[i + 1] - lr[i])
                w[i] = cos(u * PI.toFloat() / 2)
                w[i + 1] = sin(u * PI.toFloat() / 2)
            }
        }
    }

    private val wOn = FloatArray(16)
    private val wOff = FloatArray(16)

    /** Renders [frames] samples into [out] (left) and [outR] (right). */
    fun render(out: FloatArray, outR: FloatArray, frames: Int = out.size) {
        val id = setId
        if (loaded?.id != id && failed != id) {
            loaded = id?.let { runCatching { load(it) }.getOrNull() }
            failed = if (loaded == null) id else null
            shiftsSeen = -1
            liftOffsSeen = -1
            downshiftsSeen = -1
            bakedSpeed = -1f
            stoppedFor = 0f
        }
        val s = loaded
        if (s == null) { out.fill(0f, 0, frames); outR.fill(0f, 0, frames); return }
        if (s.speeds != null) { renderBaked(s, s.speeds, out, outR, frames); return }
        val dt = frames / sampleRate.toFloat()

        // control, once per block
        rpm = if (rpm <= 0f) tRpm else rpm + (tRpm - rpm) * min(1f, dt / 0.02f)
        val f = max(1f, rpm / 60f * s.cylinders / 2f)
        val lf = ln(f)
        val rpmNorm = ((rpm - s.idleRpm) / (s.redline - s.idleRpm)).coerceIn(0f, 1f)
        // with the drivetrain: its overrun, and through a shift the pedal still shows a little
        val shifting = tShifting
        var on = if (tEngaged) tLoad.coerceIn(0f, 1f) else 0f
        if (shifting > 0f) on = max(on, tThrottle * (1f - 0.3f * shifting))
        val overrun = tOverrun ?: if (!tEngaged && tSpeed > 6f) min(1f, rpmNorm + 0.2f) else 0f
        val onLevel = (ON_MIN + (1f - ON_MIN) * on) * (1f - SHIFT_DIP * shifting)
        xfade(lf, s.on, wOn)
        val heavyOn = if (s.light.isNotEmpty()) onLevel * sin(on * PI.toFloat() / 2) else onLevel
        for ((i, l) in s.on.withIndex()) l.gain = wOn[i] * heavyOn
        for (l in s.light) l.gain = if (overrun > 0f) 0f else onLevel * cos(on * PI.toFloat() / 2)
        val offLevel = if (s.off.isEmpty()) 0f else OFF_LEVEL * overrun.pow(0.7f) + 0.3f * OFF_LEVEL * (1f - on) * (if (overrun > 0f) 0f else 1f)
        if (s.off.isNotEmpty()) xfade(lf, s.off, wOff)
        for ((j, l) in s.off.withIndex()) l.gain = wOff[j] * offLevel

        // tone: load drives it harder, opens it up and adds presence
        val toneOn = if (overrun > 0f) 0f else on
        val cutoff = if (overrun > 0f) TONE_OVERRUN_HZ else TONE_LIGHT_HZ * (TONE_FULL_HZ / TONE_LIGHT_HZ).pow(toneOn)
        for (f2 in listOf(lp, lpR)) f2.lowPass(min(cutoff, sampleRate * 0.45f), sampleRate.toFloat())
        for (f2 in listOf(bite, biteR)) f2.peak(BITE_HZ, sampleRate.toFloat(), BITE_DB * toneOn, 1f)
        val drive = 1f + DRIVE * toneOn
        for (f2 in listOf(body, bodyR)) f2.lowShelf(BODY_HZ, sampleRate.toFloat(), BODY_DB)

        // limiter: the fuel cut chops the sound ~12 times a second (the drivetrain says when)
        limiterT = if (tLimiter) limiterT + dt else 0f
        val cutNow = tCut ?: (tLimiter && ((limiterT * LIMITER_HZ * 2).toInt() % 2 == 1))
        val chop = if (cutNow) 1f - LIMITER_CUT else 1f
        val targetMaster = volume * TRIM * s.norm * (0.6f + 0.4f * rpmNorm) * chop

        // events: pops just after a lift-off, crackle on the overrun. The sets' own shift samples
        // stay silent: every sound uses the same hydraulic shift sound (DriveEffects) instead
        if (shiftsSeen < 0) shiftsSeen = tShifts
        if (tShifts != shiftsSeen) shiftsSeen = tShifts
        if (downshiftsSeen < 0) downshiftsSeen = tDownshifts
        if (tDownshifts != downshiftsSeen) downshiftsSeen = tDownshifts
        if (liftOffsSeen < 0) liftOffsSeen = tLiftOffs
        if (tLiftOffs != liftOffsSeen && s.pops.isNotEmpty()) {
            liftOffsSeen = tLiftOffs
            repeat(1 + rnd.nextInt(3)) { popQueue += 0.04f + rnd.nextFloat() * 0.9f }
        }
        if (s.pops.isNotEmpty()) {
            val it = popQueue.listIterator()
            while (it.hasNext()) {
                val left = it.next() - dt
                if (left <= 0f) { it.remove(); s.pops[rnd.nextInt(s.pops.size)].pos = 0 } else it.set(left)
            }
            if (overrun > 0f && rpmNorm > 0.25f && popQueue.isEmpty() && rnd.nextFloat() < CRACKLE_RATE * dt * rpmNorm.pow(1.5f)) {
                s.pops[rnd.nextInt(s.pops.size)].pos = 0
            }
        }

        // audio
        val m0 = master
        for (l in s.all) l.rate = f / l.ref.toDouble()
        for (n in 0 until frames) {
            mixLoops(s)
            var y = body.process(bite.process(lp.process(tanh(mixL * drive) / drive)))
            var yR = bodyR.process(biteR.process(lpR.process(tanh(mixR * drive) / drive)))
            for ((sh, level) in shotsNow(s)) {
                y += sh.pcm[sh.pos] * level
                yR += sh.pcmR[sh.pos] * level
                if (++sh.pos >= sh.pcm.size) sh.pos = -1
            }
            val g = m0 + (targetMaster - m0) * (n + 1) / frames
            out[n] = y * g
            outR[n] = yR * g
        }
        master = targetMaster
    }

    private val playing = ArrayList<Pair<Shot, Float>>()

    /** The one-shots sounding right now, with their levels. */
    private fun shotsNow(s: LoopSet): List<Pair<Shot, Float>> {
        playing.clear()
        s.shiftUp?.takeIf { it.pos >= 0 }?.let { playing += it to SHIFT_LEVEL }
        s.shiftDown?.takeIf { it.pos >= 0 }?.let { playing += it to SHIFT_LEVEL }
        for (p in s.pops) if (p.pos >= 0) playing += p to POP_LEVEL
        return playing
    }

    /**
     * One output sample of the loops at their current gains (linear interpolation) into
     * [mixL] / [mixR], then every loop moves on by one output sample -- silent ones too, so they
     * come in at a natural point.
     */
    private fun mixLoops(s: LoopSet) {
        val step = s.assetRate / sampleRate.toDouble()
        var x = 0f
        var xr = 0f
        for (l in s.all) {
            val len = l.pcm.size
            if (l.gain >= 1e-4f) {
                val i = l.pos.toInt()
                val frac = (l.pos - i).toFloat()
                val i0 = i % len
                val i1 = (i + 1) % len
                x += (l.pcm[i0] + (l.pcm[i1] - l.pcm[i0]) * frac) * l.gain
                xr += (l.pcmR[i0] + (l.pcmR[i1] - l.pcmR[i0]) * frac) * l.gain
            }
            l.pos += l.rate * step
            if (l.pos >= len) l.pos -= len
        }
        mixL = x
        mixR = xr
    }

    private var bakedSpeed = -1f

    /**
     * A baked voice: the two loops either side of the road speed, crossfaded (equal power) and
     * both stretched onto the pitch in between (geometric interpolation of their refs), cruise
     * and full-pedal series blended by the pedal. Fades out after a few seconds at a standstill,
     * like the Pi does.
     */
    /** A jet's spool: the pedal sets the target, approached exponentially but rate-limited. */
    private class Spool(val idle: Float, val up: Float, val down: Float) {
        var n = -1f
        fun step(pedal: Float, dt: Float): Float {
            val target = idle + (1f - idle) * pedal
            if (n < 0f) n = target
            val cap = (if (target > n) up else down) * dt
            n += ((target - n) * min(1f, dt * 4f)).coerceIn(-cap, cap)
            return n
        }
    }

    private var afterburner = 0f
    private var bakedLoad = 0f

    /** True when the playing set is spool-keyed (a jet). */
    val isJet: Boolean get() = loaded?.spool != null

    private fun renderBaked(s: LoopSet, speeds: FloatArray, out: FloatArray, outR: FloatArray, frames: Int) {
        val dt = frames / sampleRate.toFloat()
        bakedSpeed = if (bakedSpeed < 0f) tSpeed else bakedSpeed + (tSpeed - bakedSpeed) * min(1f, dt / 0.08f)
        // the load as it falls (not the on/off "engaged" flag), glided: lifting or pressing
        // crossfades cruise <-> accelerating loops instead of switching
        // (quick going up -- that's the press you feel -- gentler coming down)
        val loadT = tLoad.coerceIn(0f, 1f)
        bakedLoad += (loadT - bakedLoad) * min(1f, dt / if (loadT > bakedLoad) 0.03f else 0.08f)
        val pedalNow = bakedLoad
        // the grid position: road speed, or for a jet its spool
        val v = s.spool?.step(pedalNow, dt) ?: bakedSpeed
        // where the speed sits on the grid
        val i: Int
        val u: Float
        when {
            v <= speeds.first() -> { i = 0; u = 0f }
            v >= speeds.last() -> { i = speeds.size - 2; u = 1f }
            else -> { i = (0 until speeds.size - 1).last { speeds[it] <= v }; u = (v - speeds[i]) / (speeds[i + 1] - speeds[i]) }
        }
        val wa = cos(u * PI.toFloat() / 2)
        val wb = sin(u * PI.toFloat() / 2)
        // keep gliding past the ends of the grid: lower below the first speed point (down to
        // x0.85 at a standstill), higher above the last (up to x1.1 another 40 km/h on)
        val beyond = when {
            s.spool != null -> 1f
            v < speeds.first() -> 0.85f + 0.15f * (v / speeds.first()).coerceIn(0f, 1f)
            v > speeds.last() -> 1f + 0.1f * ((v - speeds.last()) / 40f).coerceIn(0f, 1f)
            else -> 1f
        }
        val pm = pitchMul * beyond
        // coasting counts as cruise; the pedal brings in the full-power series
        val load = pedalNow
        val gOn = if (s.light.isEmpty()) 1f else sin(load * PI.toFloat() / 2)
        // a jet's loops are the engine at that spool, whatever the pedal
        val gLight = if (s.spool != null && s.on.isEmpty()) 1f else cos(load * PI.toFloat() / 2)
        fun place(series: List<Loop>, g: Float) {
            for (l in series) l.gain = 0f
            if (series.size < 2) { series.firstOrNull()?.let { it.gain = g; it.rate = pm.toDouble() }; return }
            val a = series[i]
            val b = series[i + 1]
            val ref = kotlin.math.exp(ln(a.ref) * (1f - u) + ln(b.ref) * u).toDouble()
            a.gain = g * wa; a.rate = ref * pm / a.ref
            b.gain = g * wb; b.rate = ref * pm / b.ref
        }
        place(s.on, gOn)
        place(s.light, gLight)
        s.boost?.let { b ->
            val lit = pedalNow >= s.boostPedal && v > 0.9f
            afterburner += ((if (lit) 1f else 0f) - afterburner) * min(1f, dt / (if (lit) 0.4f else 0.8f))
            b.gain = afterburner
            b.rate = pitchMul.toDouble()
        }

        // standstill: hold a moment, then fade away; any movement or pedal brings it straight back
        stoppedFor = if (bakedSpeed < 0.5f && load < 0.05f) stoppedFor + dt else 0f
        val still = ((stoppedFor - STANDSTILL_HOLD) / STANDSTILL_FADE).coerceIn(0f, 1f)
        val targetMaster = volume * BAKED_TRIM * s.norm * (1f - still)
        val m0 = master
        for (n in 0 until frames) {
            mixLoops(s)
            val g = m0 + (targetMaster - m0) * (n + 1) / frames
            out[n] = mixL * g
            outR[n] = mixR * g
        }
        master = targetMaster
    }
}

/** Minimal RBJ biquad, coefficients set once per block. */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun lowPass(fc: Float, sr: Float, q: Float = 0.7071f) {
        val w = 2 * PI.toFloat() * fc / sr
        val al = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + al
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
        a1 = -2 * c / a0; a2 = (1 - al) / a0
    }

    /** RBJ low shelf, slope 1. */
    fun lowShelf(fc: Float, sr: Float, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w = 2 * PI.toFloat() * fc / sr
        val c = cos(w)
        val al = sin(w) / 2 * sqrt(2f)
        val sa = 2 * sqrt(a) * al
        val a0 = (a + 1) + (a - 1) * c + sa
        b0 = a * ((a + 1) - (a - 1) * c + sa) / a0
        b1 = 2 * a * ((a - 1) - (a + 1) * c) / a0
        b2 = a * ((a + 1) - (a - 1) * c - sa) / a0
        a1 = -2 * ((a - 1) + (a + 1) * c) / a0
        a2 = ((a + 1) + (a - 1) * c - sa) / a0
    }

    fun peak(fc: Float, sr: Float, gainDb: Float, q: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w = 2 * PI.toFloat() * fc / sr
        val al = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + al / a
        b0 = (1 + al * a) / a0; b1 = -2 * c / a0; b2 = (1 - al * a) / a0
        a1 = -2 * c / a0; a2 = (1 - al / a) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}
