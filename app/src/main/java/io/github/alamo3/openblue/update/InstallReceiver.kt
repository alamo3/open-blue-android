package io.github.alamo3.openblue.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.alamo3.openblue.work.Notifier

/** Android's answer to an install: usually "ask the user first" the very first time. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (visible) {
                    runCatching { context.startActivity(confirm) }
                } else {
                    val tap = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    Notifier.post(context, Notifier.CHANNEL_UPDATES, 7300, "Open Blue update ready", "Tap to install it.", tap)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts; UpdatedReceiver says what changed
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                UpdateState.lastError = "Couldn't install the update: $msg"
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Notifier.post(context, Notifier.CHANNEL_UPDATES, 7301, "Open Blue update didn't install", msg)
                }
            }
        }
    }
}

/** Runs in the new version right after an update: say what it brought. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val (name, msg) = UpdateState.takeInstalledNote(context) ?: return
        Notifier.post(context, Notifier.CHANNEL_UPDATES, 7302, "Open Blue updated to $name", msg.ifBlank { "Tap to open." })
        UpdateWorker.schedule(context)
    }
}
