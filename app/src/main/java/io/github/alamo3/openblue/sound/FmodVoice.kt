package io.github.alamo3.openblue.sound

import android.content.Context
import kotlin.math.max
import kotlin.math.pow

/**
 * Plays an imported FMOD sound set ([FmodSound]) with the FMOD Studio runtime -- the sound
 * designer's own events, as the game plays them: the looping engine event driven by rpms and
 * throttle from [Drivetrain], plus the set's gear, limiter and backfire one-shots on shifts,
 * fuel cuts and lift-offs.
 *
 * FMOD mixes and plays through its own audio output; [update] (from the audio thread) feeds it
 * the engine state and the overall level (volume, fades, ducking).
 */
class FmodVoice(private val context: Context) : ExternalVoice {
    companion object {
        private const val BLOCK_FRAMES = 256
        private const val UPDATE_SEC = 0.015f        // Studio updates at most this often
    }

    private var sys = 0L
    private var loadedId: String? = null
    private var sound: FmodSound? = null
    private var engine = 0L
    private val shots = HashMap<String, Long>()
    private var sinceUpdate = 0f
    private var shiftsSeen = -1
    private var downSeen = -1
    private var liftSeen = -1
    private var wasCut = false
    @Volatile private var wanted: String? = null

    /** Which imported set to play (an [FmodSound] id); null for none. */
    override var setId: String?
        get() = wanted
        set(v) { wanted = v }

    private fun open(id: String): Boolean {
        close()
        val s = FmodLibrary.get(context, id) ?: return false
        if (!FmodNative.ensure(context)) return false
        sys = FmodNative.create(BLOCK_FRAMES)
        if (sys == 0L) return false
        val master = FmodNative.loadBank(sys, java.io.File(s.dir, s.masterBank).path)
        val car = FmodNative.loadBank(sys, java.io.File(s.dir, s.carBank).path)
        if (master == 0L || car == 0L) { close(); return false }
        s.masterBus?.let { FmodNative.setBusVolume(sys, it, 10f.pow(s.gainDb / 20f)) }
        val engineDesc = FmodNative.event(sys, s.events.getValue("engine"))
        engine = if (engineDesc != 0L) FmodNative.instance(engineDesc) else 0L
        if (engine == 0L) { close(); return false }
        FmodNative.start(engine)
        for (k in listOf("gear", "limiter", "backfire")) {
            s.events[k]?.let { g -> FmodNative.event(sys, g).takeIf { it != 0L }?.let { shots[k] = it } }
        }
        sound = s
        loadedId = id
        shiftsSeen = -1; downSeen = -1; liftSeen = -1
        return true
    }

    private fun close() {
        if (sys != 0L) {
            if (engine != 0L) { FmodNative.stop(engine, true); FmodNative.releaseInstance(engine) }
            FmodNative.release(sys)
        }
        sys = 0L; engine = 0L; shots.clear(); sound = null; loadedId = null
    }

    /** The playing set's rev range, for the drivetrain. */
    fun revRange(): Pair<Float, Float>? = sound?.let { it.idleRpm to it.redline }

    override fun update(st: EngineState, level: Float, dt: Float) {
        val id = wanted
        if (id != loadedId) {
            if (id == null) close() else if (!open(id)) { wanted = null; return }
        }
        if (sys == 0L) return
        val s = sound ?: return
        FmodNative.setParam(engine, "rpms", st.rpm)
        FmodNative.setParam(engine, "throttle", max(0f, st.load))

        // one-shots: gear on every shift ("state" 0 up / 1 down), limiter on each fuel cut,
        // backfire on a lift-off
        if (shiftsSeen < 0) { shiftsSeen = st.shifts; downSeen = st.downshifts; liftSeen = st.liftOffs }
        shots["gear"]?.let { g ->
            if (st.shifts != shiftsSeen) FmodNative.oneShot(g, "state".takeIf { "state" in s.params["gear"].orEmpty() }, 0f)
            if (st.downshifts != downSeen) FmodNative.oneShot(g, "state".takeIf { "state" in s.params["gear"].orEmpty() }, 1f)
        }
        val cut = st.cut == true
        if (cut && !wasCut) shots["limiter"]?.let { FmodNative.oneShot(it, null, 0f) }
        if (st.liftOffs != liftSeen) shots["backfire"]?.let { FmodNative.oneShot(it, "throttle".takeIf { "throttle" in s.params["backfire"].orEmpty() }, 0f) }
        shiftsSeen = st.shifts; downSeen = st.downshifts; liftSeen = st.liftOffs; wasCut = cut

        FmodNative.setMasterVolume(sys, level)
        sinceUpdate += dt
        if (sinceUpdate >= UPDATE_SEC) {
            sinceUpdate = 0f
            FmodNative.update(sys)
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
