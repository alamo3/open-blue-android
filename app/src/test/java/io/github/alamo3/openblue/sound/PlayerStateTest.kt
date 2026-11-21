package io.github.alamo3.openblue.sound

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private fun d(state: String) =
        "AudioPlaybackConfiguration piid:63 deviceId:2 type:android.media.AudioTrack u/pid:-1/-1 state:$state " +
            "attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0x800 tags= bundle=null sessionId:1065"

    @Test fun pausedAndStoppedPlayersArentPlaying() {
        for (s in listOf("paused", "stopped", "idle", "released")) assertFalse(s, PlayerState.playing(d(s)))
    }

    @Test fun startedPlayersArePlaying() {
        assertTrue(PlayerState.playing(d("started")))
    }

    /** Anything unreadable counts as playing, so live music is never interrupted. */
    @Test fun unknownCountsAsPlaying() {
        assertTrue(PlayerState.playing(d("somethingnew")))
        assertTrue(PlayerState.playing("AudioPlaybackConfiguration piid:63 (format changed)"))
        assertTrue(PlayerState.playing(""))
    }
}
