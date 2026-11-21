package io.github.alamo3.openblue.sound

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The layered voices through the app's own path (gearbox -> SoundVoice -> limiter) over the
 * standard test drive of virtual-car-sound's layered/drive.py: idle, a gentle pull and cruise, a
 * kickdown and hard pull, a lift and braking to a stop, a hard launch from rest, a lift, a stop.
 */
class LayeredVoiceTest {
    companion object {
        const val SR = 48000
        const val FRAMES = 256
        private val assets = File("src/main/assets")

        fun script(t: Float): Pair<Float, Float> {
            val steps = listOf(3f to (0f to 0f), 11f to (0.25f to 0f), 17f to (0.15f to 0f), 25f to (1f to 0f),
                27f to (0f to 0f), 32f to (0f to 3.5f), 34f to (0f to 0f), 41f to (1f to 0f), 44f to (0f to 0f),
                49f to (0f to 3f), 52f to (0f to 0f))
            for ((end, v) in steps) if (t < end) return v
            return 0f to 0f
        }

        /** Renders [sound] over the drive; returns interleaved stereo and the CPU time spent (s). */
        fun render(sound: EngineSound, seconds: Float = 52f, gearshifts: Boolean = true): Pair<FloatArray, Double> {
            val open: ((String) -> java.io.InputStream?)? = if (assets.exists()) { p -> File(assets, p).takeIf { it.exists() }?.inputStream() } else null
            val voice = SoundVoice(SR, open = open).apply { this.sound = sound; volume = 0.8f }
            val model = EngineModel(idleRpm = sound.idleRpm, redline = sound.redline)
            val dct = Dct(sound.idleRpm, sound.redline)
            val dt = FRAMES.toFloat() / SR
            val blocks = (seconds / dt).toInt()
            val out = FloatArray(blocks * FRAMES * 2)
            val buf = FloatArray(FRAMES * 2)
            var v = 0f; var pedal = 0f; var lastRaw = -1f; var smooth = 0f; var acc = 0f
            var cpu = 0L
            for (b in 0 until blocks) {
                val t = b * dt
                val (want, brake) = script(t)
                pedal += (want - pedal).coerceIn(-5f * dt, 5f * dt)
                val push = pedal.pow(0.8f) * min(3.9f, 160000f / 2100f / max(v, 1f))
                val regen = if (v > 0.5f) 1.7f * max(0f, 1f - pedal / 0.06f) else 0f
                v = max(0f, v + (push - 0.15f - 0.0004f * v * v - regen - brake) * dt)
                val raw = (v * 3.6f).roundToInt().toFloat()
                if (lastRaw >= 0f) {
                    acc += ((raw - lastRaw) / 3.6f / dt - acc) * min(1f, dt / 0.3f)
                    smooth += (raw - smooth) * min(1f, dt / 0.15f)
                } else smooth = raw
                lastRaw = raw
                val kmh = if (raw < 0.5f) 0f else max(0f, smooth + acc * 3.6f * 0.15f)
                val motor = (v * 3.6f * 77.5f).toInt()
                val t0 = System.nanoTime()
                var st = model.update(dt, kmh / 3.6f, acc, null, pedal)
                if (gearshifts) st = dct.step(dt, kmh, pedal, motor)
                voice.drive(pedal, false, acc, motor)
                voice.set(st)
                voice.renderStereo(buf)
                cpu += System.nanoTime() - t0
                buf.copyInto(out, b * FRAMES * 2)
            }
            return out to cpu / 1e9
        }

        fun rmsDb(x: FloatArray, from: Float, to: Float): Double {
            val a = (from * SR).toInt() * 2; val b = min(x.size, (to * SR).toInt() * 2)
            var e = 0.0
            for (i in a until b) e += x[i] * x[i]
            return 10 * log10(e / max(1, b - a) + 1e-12)
        }

        // A-weighting (IEC 61672) at 48 kHz: loudness roughly as heard, so a bass-heavy sound and a
        // bright one compare fairly (bilinear transform of the analogue filter)
        private val AW_B = doubleArrayOf(0.2343017922995135, -0.468603584599027, -0.23430179229951376, 0.9372071691980537,
            -0.23430179229951373, -0.46860358459902696, 0.2343017922995135)
        private val AW_A = doubleArrayOf(1.0, -4.113043408775872, 6.553121752655046, -4.990849294163378,
            1.7857373029375716, -0.24619059531948623, 0.011224250033231168)

        /** A-weighted level (dB) of the mid channel between [from] and [to] seconds. */
        fun dbA(x: FloatArray, from: Float, to: Float): Double {
            val n = x.size / 2
            val xs = DoubleArray(7); val ys = DoubleArray(7)
            var e = 0.0; var cnt = 0
            val a = (from * SR).toInt(); val b = min(n, (to * SR).toInt())
            for (i in 0 until b) {
                for (k in 6 downTo 1) { xs[k] = xs[k - 1]; ys[k] = ys[k - 1] }
                xs[0] = 0.5 * (x[2 * i] + x[2 * i + 1])
                var y = 0.0
                for (k in 0..6) y += AW_B[k] * xs[k]
                for (k in 1..6) y -= AW_A[k] * ys[k]
                ys[0] = y
                if (i >= a) { e += y * y; cnt++ }
            }
            return 10 * log10(e / max(1, cnt) + 1e-12)
        }

        /** 16-bit stereo WAV (for listening to what the app plays). */
        fun writeWav(file: File, x: FloatArray) {
            val bytes = java.io.ByteArrayOutputStream()
            fun i32(v: Int) { for (k in 0..3) bytes.write(v shr (8 * k) and 0xFF) }
            fun i16(v: Int) { bytes.write(v and 0xFF); bytes.write(v shr 8 and 0xFF) }
            bytes.write("RIFF".toByteArray()); i32(36 + x.size * 2); bytes.write("WAVEfmt ".toByteArray())
            i32(16); i16(1); i16(2); i32(SR); i32(SR * 4); i16(4); i16(16)
            bytes.write("data".toByteArray()); i32(x.size * 2)
            for (s in x) i16((s.coerceIn(-1f, 1f) * 32767).roundToInt())
            file.writeBytes(bytes.toByteArray())
        }
    }

    private val layered = listOf(EngineSound.FLAT6, EngineSound.HYPERCAR, EngineSound.TURBOFAN, EngineSound.WARP)

    @Test fun layeredVoicesPlayCleanlyAndInRealTime() {
        for (s in layered) {
            val (x, cpu) = render(s)
            assertTrue("$s: NaN", x.none { it.isNaN() })
            val peak = x.maxOf { kotlin.math.abs(it) }
            val idle = rmsDb(x, 0.5f, 2.5f)
            val cruise = rmsDb(x, 12f, 16f)
            val pull = rmsDb(x, 19f, 24f)
            val realtime = 52.0 / cpu
            println("$s: peak %.2f | idle %.1f, cruise %.1f, hard pull %.1f dBFS | %.0fx real time"
                .format(peak, idle, cruise, pull, realtime))
            assertTrue("$s peak $peak", peak in 0.05f..0.95f)
            assertTrue("$s plays at idle ($idle dB)", idle > -60)
            assertTrue("$s louder pulling hard than cruising ($pull vs $cruise)", pull > cruise + 2)
            // a phone is several times slower than a desktop JVM: leave plenty of room
            assertTrue("$s too slow: ${realtime.roundToInt()}x real time", realtime > 15)
        }
    }

    /**
     * As loud as the app's other sounds, as heard (A-weighted): the geared voices pull as hard as
     * a recorded engine (the BMW, within 3 dB) and cruise no more than 6 dB under it; the warp
     * bubble, which has no "pull", is as loud overall as the cinematic sounds (Future EV, within 3 dB).
     */
    @Test fun layeredVoicesMatchTheOthersInLoudness() {
        if (!File(assets, "loopsets/future_ev/set.json").exists()) return
        val bmw = render(EngineSound.BMW_GTR).first
        val bmwPull = dbA(bmw, 19f, 24f)
        val bmwCruise = dbA(bmw, 12f, 16f)
        val evWhole = dbA(render(EngineSound.FUTURE_EV).first, 3f, 48f)
        println("references, dB(A): BMW cruise %.1f, hard pull %.1f; Future EV whole drive %.1f".format(bmwCruise, bmwPull, evWhole))
        val problems = ArrayList<String>()
        for (s in layered) {
            val x = render(s).first
            val cruise = dbA(x, 12f, 16f)
            if (s == EngineSound.WARP) {
                val whole = dbA(x, 3f, 48f)
                println("$s: whole drive %+.1f dB(A) from Future EV".format(whole - evWhole))
                if (kotlin.math.abs(whole - evWhole) > 3) problems += "$s whole drive %+.1f dB".format(whole - evWhole)
            } else {
                val pull = dbA(x, 19f, 24f)
                println("$s: hard pull %+.1f, cruise %+.1f dB(A) from the BMW".format(pull - bmwPull, cruise - bmwCruise))
                if (kotlin.math.abs(pull - bmwPull) > 3) problems += "$s hard pull %+.1f dB".format(pull - bmwPull)
                if (cruise < bmwCruise - 6) problems += "$s cruise %+.1f dB".format(cruise - bmwCruise)
            }
        }
        assertTrue(problems.joinToString(), problems.isEmpty())
    }

    /** Writes each layered voice's drive to build/layered-*.wav when -Dlayered.wav=dir is set. */
    @Test fun writeWavsWhenAsked() {
        val dir = System.getProperty("layered.wav") ?: return
        for (s in layered) writeWav(File(dir, "app_${s.name.lowercase()}_test_drive.wav"), render(s).first)
    }
}
