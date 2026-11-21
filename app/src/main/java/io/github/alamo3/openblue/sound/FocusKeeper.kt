package io.github.alamo3.openblue.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Keeps the engine sound flowing through Android Auto when no music is playing.
 *
 * Android Auto streams the phone's media audio to the car only while some app holds media
 * audio focus. The engine sound never needs to take focus from music, so on its own it relies
 * on the music app holding it -- and players that give focus up on pause (or a phone with no
 * music at all) leave the car's media channel closed, silencing the engine with them.
 *
 * So: while no other media is playing, hold ordinary media focus (AUDIOFOCUS_GAIN; with no
 * other media playing there's nobody to pause). When music starts, its app takes focus as
 * usual and this lets it go -- the engine keeps playing, and the music is never paused or
 * ducked by us. Not a transient "may duck" request: Android Auto treats that as a navigation
 * prompt and moves the sound to its narrow, bass-less guidance channel.
 *
 * "Playing" means a player that has started, not one that merely exists: music apps keep their
 * player alive while paused, and counting those kept the engine from taking focus back after a
 * pause -- silent until the music resumed. Android has no public "is it playing" for other
 * apps' players, but each one's description carries its state ([PlayerState]); a player whose
 * state can't be read counts as playing, so the engine never takes focus from live music.
 */
class FocusKeeper(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var held = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            // another app took it (music started, a call...): let it have it; we keep playing
            if (change < 0) held = false
            if (change > 0) held = true
            check()
        }, main)
        .build()

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = scheduleCheck()
    }

    private val checkNow = Runnable { check() }

    fun start() {
        main.post {
            if (running) return@post
            running = true
            am?.registerAudioPlaybackCallback(playback, main)
            scheduleCheck()
        }
    }

    fun stop() {
        main.post {
            running = false
            main.removeCallbacks(checkNow)
            runCatching { am?.unregisterAudioPlaybackCallback(playback) }
            if (held) runCatching { am?.abandonAudioFocusRequest(request) }
            held = false
        }
    }

    /**
     * Players settle in bursts (pause, release, next track): look once things have been quiet
     * for a moment -- long enough that the gap between two tracks isn't taken for a pause.
     */
    private fun scheduleCheck() {
        main.removeCallbacks(checkNow)
        main.postDelayed(checkNow, SETTLE_MS)
    }

    private fun check() {
        if (!running || held) return
        val am = am ?: return
        if (otherMediaPlaying(am)) return
        held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Media players playing right now besides our own engine track (paused ones don't count). */
    private fun otherMediaPlaying(am: AudioManager): Boolean {
        val playing = am.activePlaybackConfigurations.count {
            val media = when (it.audioAttributes.usage) {
                AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN -> true
                else -> false
            }
            media && PlayerState.playing(it.toString())
        }
        return playing > 1      // one of them is the engine sound itself
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}

/**
 * Whether another app's player is playing, from its [AudioPlaybackConfiguration.toString]
 * ("... state:started attr:..."; also paused / stopped / idle / released). Not a documented
 * format, so anything it can't read counts as playing: then the engine leaves focus alone, as
 * it always did.
 */
internal object PlayerState {
    fun playing(description: String): Boolean {
        val i = description.indexOf("state:")
        if (i < 0) return true
        val state = description.substring(i + 6).trimStart().takeWhile { it.isLetter() }.lowercase()
        return when (state) {
            "paused", "stopped", "idle", "released" -> false
            else -> true        // "started", or something unknown: assume it's playing
        }
    }
}
