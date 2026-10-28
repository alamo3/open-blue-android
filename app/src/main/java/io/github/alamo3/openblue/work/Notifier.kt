package io.github.alamo3.openblue.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.alamo3.openblue.MainActivity
import io.github.alamo3.openblue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's own notifications: command results and alerts from background checks. */
object Notifier {
    const val CHANNEL_COMMANDS = "commands"
    const val CHANNEL_ALERTS = "alerts"
    private const val CHANNEL_WORKING = "working"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMMANDS, "Command results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Whether a lock, climate or other command reached the car, when you've left the app"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Car alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Charging finished, car left unlocked or open, low 12V battery, tire pressure"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "Waiting for the car", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown briefly on older Android versions while the app waits for the car"
            },
        )
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True while the app is on screen (then results show in the app instead). */
    suspend fun appInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!allowed(context)) return
        createChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (channel == CHANNEL_ALERTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** For WorkManager on older Android, where expedited work runs as a foreground service. */
    fun working(context: Context, text: String) = NotificationCompat.Builder(context, CHANNEL_WORKING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .build()
}
