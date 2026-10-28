package io.github.alamo3.openblue.sound

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.alamo3.openblue.MainActivity
import io.github.alamo3.openblue.R
import io.github.alamo3.openblue.data.SecureStore

/**
 * Keeps the engine sound going with the screen off or another app (such as Android Auto) in
 * front. Android only gives motion sensors and GPS to apps the user can see, or to a
 * foreground service like this one, which shows a notification with a Stop button.
 */
class EngineSoundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        var gps = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun begin(withGps: Boolean) = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    (if (withGps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
                startForeground(NOTIFICATION_ID, notification(withGps), type)
            } else {
                startForeground(NOTIFICATION_ID, notification(withGps))
            }
        }.isSuccess
        // Started in the background (by the car connecting), Android only allows location with
        // "Allow all the time"; without it, carry on with motion sensors alone.
        var started = begin(gps)
        if (!started && gps) {
            gps = false
            started = begin(false)
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (current == null) {
            val store = SecureStore(this)
            current = SoundController(applicationContext, store.soundMount, { store.soundMount = it }).apply {
                sound = store.engineSound?.let { n -> EngineSound.entries.firstOrNull { it.name == n } } ?: EngineSound.STARSHIP
                volume = store.soundVolume
                holdRevs = store.holdRevs
                gearshifts = store.gearshifts
                lookaheadMs = store.soundLookaheadMs.toFloat()
                start(canUseGps = gps)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        current?.stop()
        current = null
        super.onDestroy()
    }

    private fun notification(gps: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Engine sound", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the engine sound is playing"
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EngineSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Engine sound is on")
            .setContentText(if (gps) "Following the car's motion" else "Following the car's motion (no GPS: allow location all the time for speed)")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "engine_sound"
        private const val NOTIFICATION_ID = 4300
        private const val ACTION_STOP = "io.github.alamo3.openblue.STOP_SOUND"

        /** The running sound, while the service is up. Settings changes go straight to it. */
        @Volatile var current: SoundController? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineSoundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineSoundService::class.java))
        }
    }
}
