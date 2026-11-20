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

    fun set(st: EngineState) {
        tRpm = st.rpm
        tLoad = st.load
        tEngaged = st.engaged
        tShifts = st.shifts
        tLimiter = st.limiter
        tLiftOffs = st.liftOffs
        tSpeed = st.speedKmh
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
        private const val TRIM = 0.6f            // sits with the synthesized sounds' level
        private const val BAKED_TRIM = 0.45f     // baked voices are mixed hot: level with the recorded engines
        private const val STANDSTILL_HOLD = 3f   // baked voices: fade out after this long stopped
        private const val STANDSTILL_FADE = 2.5f
    }

    private class Loop(val pcm: FloatArray, val ref: Float) {
        var pos = 0.0
        var gain = 0f
        var rate = 1.0                           // playback speed relative to the file
    }

    private class Shot(val pcm: FloatArray) {
        var pos = -1                              // -1: not playing
    }

    private class LoopSet(
        val id: String, val cylinders: Int, val idleRpm: Float, val redline: Float,
        val on: List<Loop>, val light: List<Loop>, val off: List<Loop>,
        val shiftUp: Shot?, val pops: List<Shot>,
        /** The files' sample rate. */
        val assetRate: Int,
        /** Baked sets: the road speed (km/h) of each on / light loop; null for rpm-keyed sets. */
        val speeds: FloatArray?,
    ) {
        val all = on + light + off
    }

    private val rnd = Random(seed)
    private var loaded: LoopSet? = null
    private var failed: String? = null
    private var rpm = 0f
    private var master = 0f
    private var limiterT = 0f
    private var shiftsSeen = -1
    private var liftOffsSeen = -1
    private val popQueue = ArrayList<Float>()     // seconds until each queued pop
    private var stoppedFor = 0f
    private val lp = Biquad()
    private val bite = Biquad()

    // --- loading ----------------------------------------------------------------------
    private fun load(id: String): LoopSet? {
        val opener = open ?: return null
        val json = opener("loopsets/$id/set.json")?.use { String(it.readBytes()) } ?: return null
        val o = org.json.JSONObject(json)
        fun wav(name: String) = opener("loopsets/$id/$name")?.use { readWav(it.readBytes()) }
        fun loop(j: org.json.JSONObject?) = j?.let { e -> wav(e.getString("file"))?.let { Loop(it, e.getDouble("ref_hz").toFloat()) } }
        fun loops(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { loop(a.optJSONObject(it)) } } ?: emptyList()
        fun shot(key: String) = o.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }?.let { wav(it)?.let(::Shot) }
        val pops = o.optJSONArray("pops")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() }?.let { wav(it)?.let(::Shot) } } } ?: emptyList()
        val on = loops("on")
        if (on.isEmpty()) return null
        // "light" is one loop for an rpm-keyed set, a series (one per speed) for a baked one
        val light = if (o.optJSONArray("light") != null) loops("light") else listOfNotNull(loop(o.optJSONObject("light")))
        val speeds = if (o.optString("key") == "speed") {
            o.getJSONArray("speeds").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                .takeIf { it.size == on.size && (light.isEmpty() || light.size == on.size) } ?: return null
        } else null
        return LoopSet(
            id, o.optInt("cylinders", 2), o.optDouble("idle_rpm", 0.0).toFloat(), o.optDouble("redline_rpm", 1.0).toFloat(),
            on, light, loops("off"), shot("shift_up"), pops, o.optInt("sample_rate", ASSET_RATE), speeds,
        )
    }

    /** 16-bit PCM WAV (mono or stereo, mixed down) as floats. */
    private fun readWav(bytes: ByteArray): FloatArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var p = 12
        var channels = 1
        var bits = 16
        while (p + 8 <= bytes.size) {
            val tag = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (tag == "fmt ") {
                channels = b.getShort(p + 10).toInt()
                bits = b.getShort(p + 22).toInt()
            } else if (tag == "data") {
                if (bits != 16) return null
                val n = min(len, bytes.size - p - 8) / (2 * channels)
                val out = FloatArray(n)
                var q = p + 8
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until channels) { s += b.getShort(q) / 32768f; q += 2 }
                    out[i] = s / channels
                }
                return out
            }
            p += 8 + len + (len and 1)
        }
        return null
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

    fun render(out: FloatArray, frames: Int = out.size) {
        val id = setId
        if (loaded?.id != id && failed != id) {
            loaded = id?.let { runCatching { load(it) }.getOrNull() }
            failed = if (loaded == null) id else null
            shiftsSeen = -1
            liftOffsSeen = -1
            bakedSpeed = -1f
            stoppedFor = 0f
        }
        val s = loaded
        if (s == null) { out.fill(0f, 0, frames); return }
        if (s.speeds != null) { renderBaked(s, s.speeds, out, frames); return }
        val dt = frames / sampleRate.toFloat()

        // control, once per block
        rpm = if (rpm <= 0f) tRpm else rpm + (tRpm - rpm) * min(1f, dt / 0.02f)
        val f = max(1f, rpm / 60f * s.cylinders / 2f)
        val lf = ln(f)
        val rpmNorm = ((rpm - s.idleRpm) / (s.redline - s.idleRpm)).coerceIn(0f, 1f)
        val on = if (tEngaged) tLoad.coerceIn(0f, 1f) else 0f
        val overrun = if (!tEngaged && tSpeed > 6f) min(1f, rpmNorm + 0.2f) else 0f
        val onLevel = ON_MIN + (1f - ON_MIN) * on
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
        lp.lowPass(min(cutoff, sampleRate * 0.45f), sampleRate.toFloat())
        bite.peak(BITE_HZ, sampleRate.toFloat(), BITE_DB * toneOn, 1f)
        val drive = 1f + DRIVE * toneOn

        // limiter: the fuel cut chops the sound ~12 times a second
        limiterT = if (tLimiter) limiterT + dt else 0f
        val chop = if (tLimiter && ((limiterT * LIMITER_HZ * 2).toInt() % 2 == 1)) 1f - LIMITER_CUT else 1f
        val targetMaster = volume * TRIM * (0.6f + 0.4f * rpmNorm) * chop

        // events: shift sound on upshifts, pops just after a lift-off, crackle on the overrun
        if (shiftsSeen < 0) shiftsSeen = tShifts
        if (tShifts != shiftsSeen) { shiftsSeen = tShifts; s.shiftUp?.pos = 0 }
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
            var x = mixLoops(s)
            x = tanh(x * drive) / drive
            x = bite.process(lp.process(x))
            var y = x
            s.shiftUp?.let { sh -> if (sh.pos >= 0) { y += sh.pcm[sh.pos] * SHIFT_LEVEL; if (++sh.pos >= sh.pcm.size) sh.pos = -1 } }
            for (p in s.pops) if (p.pos >= 0) { y += p.pcm[p.pos] * POP_LEVEL; if (++p.pos >= p.pcm.size) p.pos = -1 }
            val g = m0 + (targetMaster - m0) * (n + 1) / frames
            out[n] = y * g
        }
        master = targetMaster
        advance(s, frames)
    }

    /** One output sample of the loops at their current gains (linear interpolation). */
    private fun mixLoops(s: LoopSet): Float {
        var x = 0f
        for (l in s.all) {
            if (l.gain < 1e-4f) continue
            val len = l.pcm.size
            val i = l.pos.toInt()
            val frac = (l.pos - i).toFloat()
            val a = l.pcm[i % len]
            val b2 = l.pcm[(i + 1) % len]
            x += (a + (b2 - a) * frac) * l.gain
        }
        return x
    }

    /** Moves every loop on by one block (silent ones too, so they come in at a natural point). */
    private fun advance(s: LoopSet, frames: Int) {
        val step = s.assetRate / sampleRate.toDouble()
        for (l in s.all) {
            l.pos += l.rate * step * frames
            val len = l.pcm.size.toDouble()
            if (l.pos >= len) l.pos %= len
        }
    }

    private var bakedSpeed = -1f

    /**
     * A baked voice: the two loops either side of the road speed, crossfaded (equal power) and
     * both stretched onto the pitch in between (geometric interpolation of their refs), cruise
     * and full-pedal series blended by the pedal. Fades out after a few seconds at a standstill,
     * like the Pi does.
     */
    private fun renderBaked(s: LoopSet, speeds: FloatArray, out: FloatArray, frames: Int) {
        val dt = frames / sampleRate.toFloat()
        bakedSpeed = if (bakedSpeed < 0f) tSpeed else bakedSpeed + (tSpeed - bakedSpeed) * min(1f, dt / 0.08f)
        val v = bakedSpeed
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
        // coasting counts as cruise; the pedal brings in the full-power series
        val load = if (tEngaged) tLoad.coerceIn(0f, 1f) else 0f
        val gOn = if (s.light.isEmpty()) 1f else sin(load * PI.toFloat() / 2)
        val gLight = cos(load * PI.toFloat() / 2)
        fun place(series: List<Loop>, g: Float) {
            for (l in series) l.gain = 0f
            if (series.size < 2) { series.firstOrNull()?.let { it.gain = g; it.rate = 1.0 }; return }
            val a = series[i]
            val b = series[i + 1]
            val ref = kotlin.math.exp(ln(a.ref) * (1f - u) + ln(b.ref) * u).toDouble()
            a.gain = g * wa; a.rate = ref / a.ref
            b.gain = g * wb; b.rate = ref / b.ref
        }
        place(s.on, gOn)
        place(s.light, gLight)

        // standstill: hold a moment, then fade away; any movement or pedal brings it straight back
        stoppedFor = if (v < 0.5f && load < 0.05f) stoppedFor + dt else 0f
        val still = ((stoppedFor - STANDSTILL_HOLD) / STANDSTILL_FADE).coerceIn(0f, 1f)
        val targetMaster = volume * BAKED_TRIM * (1f - still)
        val m0 = master
        for (n in 0 until frames) {
            out[n] = mixLoops(s) * (m0 + (targetMaster - m0) * (n + 1) / frames)
        }
        master = targetMaster
        advance(s, frames)
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
