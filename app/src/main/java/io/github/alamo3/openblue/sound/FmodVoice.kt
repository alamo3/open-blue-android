package io.github.alamo3.openblue.sound

import android.content.Context
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plays an imported FMOD sound set ([FmodSound]) with the FMOD Studio runtime -- the sound
 * designer's own events, as the game plays them:
 *
 *  - a car: its looping engine event driven by rpms and throttle from the gearbox ([Dct]), plus its
 *    limiter and backfire one-shots on fuel cuts and lift-offs (its own gear one-shot stays
 *    silent: every sound shares the hydraulic shift sound)
 *  - a jet: its looping layers driven by a turbofan spool (the pedal sets the target engine
 *    speed, the engine winds up slowly and down a little faster), an afterburner that lights
 *    near full pedal once spooled up, road speed as airspeed, and its boom on a hard launch
 *
 * FMOD mixes and plays through its own audio output; [update] (from the audio thread) feeds it
 * the engine state and the overall level (volume, fades, ducking).
 */
class FmodVoice(private val context: Context) : ExternalVoice {
    companion object {
        private const val BLOCK_FRAMES = 256
        private const val UPDATE_SEC = 0f            // Studio updates every audio block (~5 ms)
        private const val JET_IDLE_RPM = 1000f       // jets with gearshifts: the drivetrain's range
        private const val JET_REDLINE_RPM = 7000f
        private const val GEARED_UP_PER_SEC = 1.5f   // ...and the spool follows it quickly
        private const val GEARED_DOWN_PER_SEC = 2.5f
    }

    private var sys = 0L
    private var loadedId: String? = null
    private var sound: FmodSound? = null
    private var engine = 0L                          // car: the engine event instance
    private val shots = HashMap<String, Long>()       // car: one-shot event descriptions
    private val layers = ArrayList<Pair<FmodSound.Loop, Long>>()   // jet: layer instances
    private var boom = 0L                            // jet: the launch sound
    private var sinceUpdate = 0f
    private var shiftsSeen = -1
    private var downSeen = -1
    private var liftSeen = -1
    private var wasCut = false
    // jet state
    private var spool = 0f
    private var afterburner = 0f
    private var prevPedal = -1f
    private var pedalRate = 0f
    private var launchCooldown = 0f

    @Volatile private var wanted: String? = null
    private var failedId: String? = null
    /** What's happening, for the screen: playing, or why not. */
    @Volatile var status: String? = null
        private set

    /** Which imported set to play (an [FmodSound] id); null for none. */
    override var setId: String?
        get() = wanted
        set(v) { wanted = v }

    /** The playing set is a jet (no gearbox: it follows the pedal directly). */
    val isJet: Boolean get() = sound?.isJet == true

    private fun fail(why: String): Boolean {
        android.util.Log.e(FmodNative.TAG, why)
        status = why
        close()
        return false
    }

    private fun open(id: String): Boolean {
        close()
        val s = FmodLibrary.get(context, id) ?: return fail("FMOD sound '$id' not found (import it again)")
        if (!FmodNative.ensure(context)) return fail(FmodNative.error ?: "FMOD failed to load")
        sys = FmodNative.create(BLOCK_FRAMES)
        if (sys == 0L) return fail("FMOD couldn't start its audio output")
        for (b in s.banks) {
            if (FmodNative.loadBank(sys, File(s.dir, b).path) == 0L) return fail("FMOD couldn't load ${s.name}'s $b")
        }
        s.masterBus?.let { FmodNative.setBusVolume(sys, it, 10f.pow(s.gainDb / 20f)) }
        if (s.isJet) {
            for (l in s.loops) {
                val d = FmodNative.event(sys, l.guid)
                val inst = if (d != 0L) FmodNative.instance(d) else 0L
                if (inst == 0L) return fail("${s.name}: couldn't create its ${l.name} sound")
                FmodNative.setVolume(inst, 10f.pow(l.gainDb / 20f))
                FmodNative.start(inst)
                layers += l to inst
            }
            boom = s.boomFile?.let { FmodNative.loadSound(sys, File(s.dir, it).path) } ?: 0L
            spool = s.jet?.idleFrac ?: 0.6f
            afterburner = 0f
            prevPedal = -1f
            pedalRate = 0f
            launchCooldown = 0f
        } else {
            val engineDesc = FmodNative.event(sys, s.events.getValue("engine"))
            if (engineDesc == 0L) return fail("${s.name}: engine event not found in the bank")
            engine = FmodNative.instance(engineDesc)
            if (engine == 0L) return fail("${s.name}: couldn't create the engine sound (bus missing from the master bank?)")
            FmodNative.start(engine)
            for (k in listOf("gear", "limiter", "backfire")) {
                s.events[k]?.let { g -> FmodNative.event(sys, g).takeIf { it != 0L }?.let { shots[k] = it } }
            }
        }
        android.util.Log.i(FmodNative.TAG, "playing ${s.name}")
        status = "Playing ${s.name} with FMOD"
        sound = s
        loadedId = id
        shiftsSeen = -1; downSeen = -1; liftSeen = -1
        return true
    }

    private fun close() {
        if (sys != 0L) {
            if (engine != 0L) { FmodNative.stop(engine, true); FmodNative.releaseInstance(engine) }
            for ((_, inst) in layers) { FmodNative.stop(inst, true); FmodNative.releaseInstance(inst) }
            FmodNative.release(sys)
        }
        sys = 0L; engine = 0L; boom = 0L; shots.clear(); layers.clear(); sound = null; loadedId = null
    }

    /**
     * The playing set's rev range, for the drivetrain. Jets use a nominal range: with
     * gearshifts on, their engine speed follows the gearbox's revs across it.
     */
    fun revRange(): Pair<Float, Float>? = sound?.let { if (it.isJet) JET_IDLE_RPM to JET_REDLINE_RPM else it.idleRpm to it.redline }

    override fun update(st: EngineState, level: Float, dt: Float) {
        val id = wanted
        if (id != loadedId && id != failedId) {
            if (id == null) close() else if (!open(id)) { failedId = id; return }
        }
        if (id != failedId) failedId = null
        if (sys == 0L) return
        val s = sound ?: return
        if (s.isJet) updateJet(s, st, dt) else updateCar(s, st)

        FmodNative.setMasterVolume(sys, level)
        sinceUpdate += dt
        if (sinceUpdate >= UPDATE_SEC) {
            sinceUpdate = 0f
            FmodNative.update(sys)
        }
    }

    private fun updateCar(s: FmodSound, st: EngineState) {
        FmodNative.setParam(engine, "rpms", st.rpm)
        FmodNative.setParam(engine, "throttle", max(0f, st.load))
        // one-shots: limiter on each fuel cut, backfire on a lift-off. The bank's own "gear"
        // event stays silent: every sound uses the same hydraulic shift sound (DriveEffects)
        if (shiftsSeen < 0) { shiftsSeen = st.shifts; downSeen = st.downshifts; liftSeen = st.liftOffs }
        val cut = st.cut == true
        if (cut && !wasCut) shots["limiter"]?.let { FmodNative.oneShot(it, null, 0f) }
        if (st.liftOffs != liftSeen) shots["backfire"]?.let { FmodNative.oneShot(it, "throttle".takeIf { "throttle" in s.params["backfire"].orEmpty() }, 0f) }
        shiftsSeen = st.shifts; downSeen = st.downshifts; liftSeen = st.liftOffs; wasCut = cut
    }

    private fun updateJet(s: FmodSound, st: EngineState, dt: Float) {
        val j = s.jet ?: return
        val pedal = (if (st.engaged) st.load else 0f).coerceIn(0f, 1f)
        // spool: the target is the pedal -- or, with gearshifts (a drivetrain state, gear >= 1),
        // the gearbox's revs, so it climbs through each gear and drops on the shift -- approached
        // exponentially but rate-limited
        val geared = st.gear >= 1
        val drive = if (geared) ((st.rpm - JET_IDLE_RPM) / (JET_REDLINE_RPM - JET_IDLE_RPM)).coerceIn(0f, 1f) else pedal
        val target = j.idleFrac + (1f - j.idleFrac) * drive
        val rate = if (target > spool) (if (geared) GEARED_UP_PER_SEC else j.upPerSec) else (if (geared) GEARED_DOWN_PER_SEC else j.downPerSec)
        val cap = rate * dt
        spool += ((target - spool) * min(1f, dt * (if (geared) 8f else 2.5f))).coerceIn(-cap, cap)
        // afterburner: lights near full pedal once spooled up, fades in and out
        val lit = pedal >= j.afterburnerPedal && spool > 0.9f
        afterburner += ((if (lit) 1f else 0f) - afterburner) * min(1f, dt / (if (lit) 0.4f else 0.8f))
        val values = mapOf(
            "n1_pct" to spool * 100f, "jet_rad" to spool * j.maxRad, "throttle" to pedal,
            "speed_kts" to st.speedKmh / 1.852f, "afterburner" to afterburner,
        )
        for ((l, inst) in layers) {
            for ((param, source) in l.params) {
                val v = if (source.startsWith("const:")) source.removePrefix("const:").toFloatOrNull() else values[source]
                if (v != null) FmodNative.setParam(inst, param, v)
            }
        }
        // launch: a fast, deep pedal stab from low speed (the Pi's rule) -> the boom
        if (prevPedal >= 0f && dt > 0f) pedalRate += ((pedal - prevPedal) / dt - pedalRate) * min(1f, dt / 0.08f)
        prevPedal = pedal
        launchCooldown = max(0f, launchCooldown - dt)
        if (boom != 0L && pedalRate > 2f && pedal > 0.65f && st.speedKmh < 50f && launchCooldown == 0f) {
            launchCooldown = 4f
            FmodNative.playSound(sys, boom, 10f.pow(s.boomGainDb / 20f))
        }
    }

    override fun stop() {
        wanted = null
        close()
    }
}

/** A sound that plays through its own output (FMOD) rather than into SoundVoice's buffer. */
interface ExternalVoice {
    var setId: String?
    /** Once per audio block: the engine state, the overall level (0..1) and the block length. */
    fun update(st: EngineState, level: Float, dt: Float)
    fun stop()
}
