package io.github.alamo3.openblue.sound

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Plays whichever sound is chosen: a synthesized engine ([EngineVoice]), an EV sound
 * ([FutureVoice]) or a recorded engine / baked cinematic sound ([LoopVoice], whose sound files
 * [open] reads), plus the drive effects ([DriveEffects]) and a peak limiter on the way out.
 * The layered sounds (Flat-6, Hypercar, Turbofan, Warp bubble) aren't played here but by the
 * native engine ([SoundCore]), which has its own of all of that.
 */
class SoundVoice(
    private val sampleRate: Int,
    /** Plays FMOD sound sets (its own output); null where FMOD isn't available (tests). */
    private val external: ExternalVoice? = null,
    open: ((String) -> InputStream?)? = null,
) {
    companion object {
        /**
         * Everything (sounds, effects, FMOD) is mixed this much lower than it used to be, so the
         * hot baked sets (normalised to rms 0.55: peaks 2-10 dB over full scale at full volume)
         * no longer lean on the limiter all the time. Turn the car's volume up to make it up.
         */
        // -12 dB, as the native engine (soundcore): headroom so the limiter only catches the odd
        // extreme instead of clamping on every pulse (a distorted crackle at 0.5, the old value)
        private const val OUTPUT_TRIM = 0.25f
        private const val CEILING = 0.89f          // output peaks never go above this (-1 dBFS)
        private const val LOOKAHEAD_SEC = 0.003f   // the limiter sees peaks coming this early
        private const val LIMIT_RELEASE_SEC = 0.25f
        /** Pitch of non-engine sounds across the gearbox's rev range: idle .. redline. */
        private const val GEAR_PITCH_LOW = 0.92f
        private const val GEAR_PITCH_SPAN = 0.25f
        /** Sounds that aren't engines, on each of the rev limiter's cuts: down to this level... */
        private const val LIMITER_CUT_LEVEL = 0.35f
        private const val LIMITER_EASE_SEC = 0.004f   // ...eased over about this long
        private const val CORNER_LEAN = 0.45f          // how far the sound leans in a hard corner...
        private const val CORNER_FULL_MS2 = 3.9f       // ...at this cornering force (0.4 g)
        private val SQRT2 = kotlin.math.sqrt(2f)
        private const val SPEED_FROM_KMH = 30f
        private const val SPEED_FULL_KMH = 120f
        private const val SPEED_GAIN_DB = 3f
        private const val SPEED_BRIGHT_DB = 2f
    }

    private val engine = EngineVoice(sampleRate)
    private val future = FutureVoice(sampleRate)
    private val recorded = LoopVoice(sampleRate, open)
    private val effects = DriveEffects(sampleRate, open)
    private var state: EngineState? = null
    @Volatile private var pedal: Float? = null
    @Volatile private var reversing = false
    @Volatile private var accel = 0f
    @Volatile private var motorRpm = 0f
    @Volatile private var lateralG = 0f
    private var lean = 0f
    private val tiltL = Biq()
    private val tiltR = Biq()
    private var speedMix = 0f                     // 0..1: how far into the speed-aware mix
    // look-ahead limiter state (see limit())
    private val la = max(2, (LOOKAHEAD_SEC * sampleRate).toInt())
    private val delayL = FloatArray(la - 1)
    private val delayR = FloatArray(la - 1)
    private var delayPos = 0
    private val minVal = FloatArray(la)          // monotonic queue of the gains peaks need...
    private val minIdx = LongArray(la)           // ...and which sample asked for each
    private var minHead = 0
    private var minCount = 0
    private val avgBuf = FloatArray(la) { 1f }   // that minimum, averaged over the look-ahead
    private var avgPos = 0
    private var avgSum = la.toDouble()
    private var sampleIdx = 0L
    private var limitGain = 1f
    private var cutGain = 1f
    private var chimed = false
    /** The tires squealing in hard corners at speed: the native engine's tire layer; null without it (tests). */
    var tires: SoundCore.Tires? = null
    /** The start-up chime has already played (the native engine played it): don't play it again. */
    fun skipChime() { chimed = true }
    private var pitchMul = 1f
    private var shiftsSeen = -1
    private var downSeen = -1

    /**
     * The car's own pedal (0..1) and whether it's reversing, from the Raspberry Pi; null pedal
     * when only the phone's sensors are available. [accel] (m/s², forward) and [motorRpm] (the
     * car's own motor, 0 if unknown) feed the layered voices.
     */
    fun drive(pedal: Float?, reversing: Boolean, accel: Float = 0f, motorRpm: Int = 0) {
        this.pedal = pedal
        this.reversing = reversing
        this.accel = accel
        this.motorRpm = kotlin.math.abs(motorRpm).toFloat()
    }

    /**
     * Cornering force (m/s², positive = cornering left, see [Cornering]): the sound leans gently
     * toward the outside of the turn, as your body does.
     */
    fun corner(lateralAccel: Float) {
        lateralG = lateralAccel
    }

    var sound: EngineSound = EngineSound.STARSHIP
        set(v) {
            field = v
            shiftsSeen = -1      // a different sound's gearbox: don't hear its count change as a shift
            downSeen = -1
            when (v.kind) {
                EngineSound.Kind.PISTON -> engine.sound = v
                EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.setId = v.loopSet
                EngineSound.Kind.FMOD -> {}
                EngineSound.Kind.FLAT6, EngineSound.Kind.HYPERCAR, EngineSound.Kind.JET, EngineSound.Kind.WARP -> {}
                else -> future.kind = v.kind
            }
        }

    var volume: Float = 0.8f
        set(v) {
            field = v
            engine.volume = v
            future.volume = v
            recorded.volume = v
        }

    fun set(state: EngineState) {
        this.state = state
        engine.set(state)
        future.set(state)
        recorded.set(state)
    }

    private var left = FloatArray(0)
    private var right = FloatArray(0)

    /** Renders [frames] samples as mono (left and right mixed) into [out]. */
    fun render(out: FloatArray, frames: Int = out.size) {
        renderLR(frames)
        for (n in 0 until frames) out[n] = 0.5f * (left[n] + right[n])
    }

    /** Renders [frames] stereo samples into [out], interleaved left, right. */
    fun renderStereo(out: FloatArray, frames: Int = out.size / 2) {
        renderLR(frames)
        for (n in 0 until frames) {
            out[2 * n] = left[n]
            out[2 * n + 1] = right[n]
        }
    }

    private fun renderLR(frames: Int) {
        if (left.size < frames) { left = FloatArray(frames); right = FloatArray(frames) }
        val out = left
        val outR = right
        val st = state
        val k = sound.kind
        if (!chimed && st != null) { chimed = true; effects.chime() }    // the sound starting up
        if (st != null) {
            effects.step(
                frames / sampleRate.toFloat(), st.speedKmh, pedal ?: st.load, reversing,
                // launches: the jet booms, the EV and cinematic sounds whoosh; engines (and FMOD
                // jets, which boom by themselves) make their own
                launch = when {
                    k == EngineSound.Kind.BAKED && recorded.isJet -> "boom"
                    !sound.isEngine -> "ev"
                    else -> null
                },
            )
            // gearshifts on (gear >= 1): the sounds that aren't engines take their pitch from the
            // gearbox's revs, climbing through each gear and dropping on the upshift. (Engines
            // follow the revs themselves.) The gearbox already moves the revs smoothly through a
            // shift, so this only takes the edge off block steps.
            val bdt = frames / sampleRate.toFloat()
            val pitchTarget = if (st.gear >= 1 && !sound.isEngine) {
                val norm = ((st.rpm - sound.idleRpm) / (sound.redline - sound.idleRpm)).coerceIn(0f, 1f)
                GEAR_PITCH_LOW + GEAR_PITCH_SPAN * norm
            } else 1f
            pitchMul += (pitchTarget - pitchMul) * kotlin.math.min(1f, bdt / 0.03f)
            recorded.pitchMul = pitchMul
            future.pitchMul = pitchMul
            // gearbox shifts made while driving hard get the shift sound (DriveEffects.shift decides
            // by the pedal); sounds without a torque cut of their own (not engines) dip under it
            if (st.gear >= 1 && sound.hasGears) {
                val effort = pedal ?: st.throttle
                if (shiftsSeen >= 0 && st.shifts != shiftsSeen) effects.shift(up = true, effort = effort, duck = !sound.isEngine)
                if (downSeen >= 0 && st.downshifts != downSeen) effects.shift(up = false, effort = effort)
            }
            shiftsSeen = st.shifts
            downSeen = st.downshifts
        }
        when (k) {
            EngineSound.Kind.PISTON -> { engine.render(out, frames); out.copyInto(outR, 0, 0, frames) }
            EngineSound.Kind.RECORDED, EngineSound.Kind.BAKED -> recorded.render(out, outR, frames)
            EngineSound.Kind.FMOD -> { out.fill(0f, 0, frames); outR.fill(0f, 0, frames) }   // its own output
            // the layered sounds play in the native engine (SoundCore), not here
            EngineSound.Kind.FLAT6, EngineSound.Kind.HYPERCAR, EngineSound.Kind.JET, EngineSound.Kind.WARP -> {
                out.fill(0f, 0, frames); outR.fill(0f, 0, frames)
            }
            else -> { future.render(out, frames); out.copyInto(outR, 0, 0, frames) }
        }
        // the gearbox's rev limiter: engines chop on its fuel cuts themselves; the other sounds
        // stutter with it here (a quick dip on each cut, eased so it doesn't click)
        val cutTarget = if (!sound.isEngine && sound.hasGears && st?.cut == true) LIMITER_CUT_LEVEL else 1f
        if (cutTarget < 1f || cutGain < 1f) {
            val k = 1f / (LIMITER_EASE_SEC * sampleRate)
            for (n in 0 until frames) {
                cutGain += (cutTarget - cutGain) * k
                out[n] *= cutGain
                outR[n] *= cutGain
            }
            if (cutTarget == 1f && cutGain > 0.999f) cutGain = 1f
        }
        if (external != null && st != null) {
            // FMOD: same level, fades and ducking as the rest; silent when another sound is chosen
            // (its own output: the speed-aware lift as a level only)
            val level = if (k == EngineSound.Kind.FMOD) volume * effects.duck * OUTPUT_TRIM * 10f.pow(SPEED_GAIN_DB * speedMix / 20f) else 0f
            external.update(st, level, frames / sampleRate.toFloat())
        }
        // the tires in a hard corner (the native engine's: see SoundCore.Tires)
        if (st != null && !reversing) tires?.render(out, outR, frames, lateralG, st.speedKmh / 3.6f, volume)
        effects.mix(out, outR, frames, volume)
        speedMix(out, outR, frames, st?.speedKmh ?: 0f)
        lean(out, outR, frames)
        limit(out, outR, frames)
    }

    /**
     * At speed, road and wind noise rise: the sound comes up a little to stay present, up to
     * [SPEED_GAIN_DB] louder and [SPEED_BRIGHT_DB] brighter (above 3 kHz) by [SPEED_FULL_KMH],
     * from nothing at [SPEED_FROM_KMH]; eased over about a second. Town driving is untouched.
     */
    private fun speedMix(out: FloatArray, outR: FloatArray, frames: Int, kmh: Float) {
        val target = ((kmh - SPEED_FROM_KMH) / (SPEED_FULL_KMH - SPEED_FROM_KMH)).coerceIn(0f, 1f)
        speedMix += (target - speedMix) * kotlin.math.min(1f, frames / (1f * sampleRate))
        if (speedMix < 1e-3f) return
        tiltL.highShelf(3000f, 0.707f, SPEED_BRIGHT_DB * speedMix, sampleRate)
        tiltR.highShelf(3000f, 0.707f, SPEED_BRIGHT_DB * speedMix, sampleRate)
        val g = 10f.pow(SPEED_GAIN_DB * speedMix / 20f)
        for (n in 0 until frames) {
            out[n] = tiltL.process(out[n]) * g
            outR[n] = tiltR.process(outR[n]) * g
        }
    }

    /**
     * Leans the whole sound toward the outside of a corner: up to [CORNER_LEAN] of the way at
     * [CORNER_FULL_MS2] (about 0.6 g), with an equal-power balance so it doesn't get louder or
     * quieter, eased over ~0.2 s.
     */
    private fun lean(out: FloatArray, outR: FloatArray, frames: Int) {
        // + = to the right: cornering left (positive lateral force) leans right, to the outside
        val target = (lateralG / CORNER_FULL_MS2).coerceIn(-1f, 1f) * CORNER_LEAN
        val dt = frames / sampleRate.toFloat()
        val l0 = lean
        lean += (target - lean) * kotlin.math.min(1f, dt / 0.2f)
        if (kotlin.math.abs(l0) < 1e-4f && kotlin.math.abs(lean) < 1e-4f) return
        for (n in 0 until frames) {
            val p = l0 + (lean - l0) * n / frames
            val a = (Math.PI / 4 * (1 + p)).toFloat()
            out[n] *= SQRT2 * kotlin.math.cos(a)
            outR[n] *= SQRT2 * kotlin.math.sin(a)
        }
    }

    /**
     * Look-ahead peak limiter, linked across both channels (so the stereo image doesn't shift).
     * The output runs [LOOKAHEAD_SEC] late, so the gain can glide down over that time *before*
     * a peak instead of snapping down on it: the old instant-attack limiter bent the waveform
     * cycle by cycle, which is what made loud sounds distort. Peaks still never pass [CEILING].
     *
     * The gain each sample needs is min(1, CEILING / peak). Its minimum over the last `la`
     * samples, averaged over `la` samples, is never above what the delayed sample needs (every
     * window in the average contains it), so the glide is guaranteed to have arrived in time.
     */
    private fun limit(out: FloatArray, outR: FloatArray, frames: Int) {
        val rel = 1f / (LIMIT_RELEASE_SEC * sampleRate)
        var g = limitGain
        for (n in 0 until frames) {
            val l = out[n] * OUTPUT_TRIM
            val r = outR[n] * OUTPUT_TRIM
            val peak = max(abs(l), abs(r))
            val need = if (peak > CEILING) CEILING / peak else 1f
            // sliding minimum over the last la samples (monotonic queue; drop the expired first)
            if (minCount > 0 && minIdx[minHead] <= sampleIdx - la) { minHead = (minHead + 1) % la; minCount-- }
            while (minCount > 0 && minVal[(minHead + minCount - 1) % la] >= need) minCount--
            minVal[(minHead + minCount) % la] = need
            minIdx[(minHead + minCount) % la] = sampleIdx
            minCount++
            val m = minVal[minHead]
            // ...averaged: a smooth ramp down that lands exactly on the peak
            avgSum += m - avgBuf[avgPos]
            avgBuf[avgPos] = m
            avgPos = (avgPos + 1) % la
            val s = (avgSum / la).toFloat()
            g = if (s < g) s else g + (s - g) * rel
            // out goes the sample from la - 1 samples ago, at this gain
            out[n] = delayL[delayPos] * g
            outR[n] = delayR[delayPos] * g
            delayL[delayPos] = l
            delayR[delayPos] = r
            delayPos = (delayPos + 1) % delayL.size
            sampleIdx++
        }
        limitGain = g
        // the running sum drifts a little in floating point: rebuild it now and then
        if ((sampleIdx and 0xFFFFL) < frames.toLong()) avgSum = avgBuf.sumOf { it.toDouble() }
    }
}
