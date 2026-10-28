package io.github.alamo3.openblue.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.alamo3.openblue.CommandProgress
import io.github.alamo3.openblue.api.BluelinkClient
import io.github.alamo3.openblue.api.CommandResult
import io.github.alamo3.openblue.data.SecureStore
import java.util.UUID

/**
 * Waits for a remote command's result outside the app's screen, so it isn't lost if the app is
 * closed. The app watches this work while it's open; otherwise the result becomes a notification.
 */
class CommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val vehicleId = inputData.getString(KEY_VEHICLE) ?: return Result.failure()
        val tx = inputData.getString(KEY_TX) ?: return Result.failure()
        val action = runCatching { CommandProgress.Action.valueOf(inputData.getString(KEY_ACTION).orEmpty()) }.getOrNull()
        val car = inputData.getString(KEY_CAR) ?: "Your car"
        val store = SecureStore(applicationContext)
        val api = BluelinkClient(store)

        val result = runCatching { api.awaitResult(vehicleId, tx) }.getOrDefault(CommandResult.TIMEOUT)
        // Keep the stored status fresh for whoever opens the app next.
        runCatching { store.cachedStatus = api.cachedStatus(vehicleId).toString() }

        // Skip the notification only when the app is open and showing this command's progress.
        val shownInApp = tx in watching && Notifier.appInForeground()
        if (store.notifyCommands && !shownInApp && action != null) {
            val (title, text) = when (result) {
                CommandResult.SUCCESS -> "$car: ${action.done}" to "Done."
                CommandResult.FAILED -> "$car: ${action.doing.lowercase()} didn't work" to "The car didn't accept the command."
                CommandResult.TIMEOUT -> "$car: no answer yet" to "${action.doing} hasn't been confirmed. It may still go through."
            }
            Notifier.post(applicationContext, Notifier.CHANNEL_COMMANDS, tx.hashCode(), title, text)
        }
        return Result.success(workDataOf(KEY_RESULT to result.name))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifier.createChannels(applicationContext)
        return ForegroundInfo(4201, Notifier.working(applicationContext, "Waiting for the car"))
    }

    companion object {
        /** Transactions an open screen is following (same process). */
        val watching: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        private const val KEY_VEHICLE = "vehicle"
        private const val KEY_TX = "tx"
        private const val KEY_ACTION = "action"
        private const val KEY_CAR = "car"
        const val KEY_RESULT = "result"

        fun enqueue(context: Context, vehicleId: String, tx: String, action: CommandProgress.Action, car: String): UUID {
            val req = OneTimeWorkRequestBuilder<CommandWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_VEHICLE to vehicleId, KEY_TX to tx, KEY_ACTION to action.name, KEY_CAR to car))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("command-$tx", ExistingWorkPolicy.KEEP, req)
            return req.id
        }
    }
}
