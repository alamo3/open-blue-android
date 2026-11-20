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
 * So: while no other media is playing, hold a transient "may duck" focus (there is nothing to
 * duck). When music starts, its app takes focus as usual and this lets it go -- the engine
 * keeps playing, and the music is never paused or ducked by us.
 */
class FocusKeeper(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var held = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

    /** Players settle in bursts (pause, release, next track): look once things are quiet. */
    private fun scheduleCheck() {
        main.removeCallbacks(checkNow)
        main.postDelayed(checkNow, 400)
    }

    private fun check() {
        if (!running || held) return
        val am = am ?: return
        if (otherMediaPlaying(am)) return
        held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Media players active right now besides our own engine track. */
    private fun otherMediaPlaying(am: AudioManager): Boolean {
        val media = am.activePlaybackConfigurations.count {
            when (it.audioAttributes.usage) {
                AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN -> true
                else -> false
            }
        }
        return media > 1        // one of them is the engine sound itself
    }
}
