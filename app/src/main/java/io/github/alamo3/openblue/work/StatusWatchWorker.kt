package io.github.alamo3.openblue.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.alamo3.openblue.api.ApiException
import io.github.alamo3.openblue.api.BluelinkClient
import io.github.alamo3.openblue.api.OtpRequiredException
import io.github.alamo3.openblue.data.SecureStore
import io.github.alamo3.openblue.ui.CarStatus
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Every so often, reads the status Bluelink already has for the car (the same request as
 * pull-to-refresh, so the car itself isn't woken) and raises alerts for what changed.
 */
class StatusWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SecureStore(applicationContext)
        if (!store.watchEnabled) return Result.success()
        val v = store.vehicle ?: return Result.success()
        if (store.username == null) return Result.success()
        val api = BluelinkClient(store)

        val raw = try {
            api.cachedStatus(v.id)
        } catch (e: OtpRequiredException) {
            return Result.success() // needs the user; the app will ask next time it's opened
        } catch (e: ApiException) {
            return Result.success()
        } catch (e: IOException) {
            return Result.retry()
        }
        store.cachedStatus = raw.toString()
        val st = CarStatus.from(raw, emptyList())
        val (memory, alerts) = Alerts.evaluate(
            store.watchMemory,
            st,
            System.currentTimeMillis(),
            v.nickname.ifBlank { "Your car" },
        ) { store.alertOn(it.key) }
        store.watchMemory = memory
        alerts.forEach { a ->
            Notifier.post(applicationContext, Notifier.CHANNEL_ALERTS, 5000 + a.kind.ordinal, a.title, a.text)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "status-watch"

        /** Starts, reschedules or stops the background checks to match Settings. */
        fun sync(context: Context, store: SecureStore, replace: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            if (!store.watchEnabled || store.username == null) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<StatusWatchWorker>(store.watchMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun stop(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
