package io.github.alamo3.openblue.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.alamo3.openblue.sound.EngineSoundService
import io.github.alamo3.openblue.work.Notifier
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Every few hours on Wi-Fi: if there's a newer build, download it and install it. Never while
 * the app is on screen or the engine sound is playing (installing closes the app); then it
 * waits for the next run.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!UpdateState.autoUpdate(ctx)) return Result.success()
        val release = try { Updater.check() } catch (e: IOException) { return Result.retry() }
        if (!release.isNewer) return Result.success()
        val apk = try { Updater.download(ctx, release) } catch (e: IOException) { return Result.retry() }
        if (Notifier.appInForeground() || EngineSoundService.current != null) return Result.success()
        runCatching { Updater.install(ctx, apk, release) }
            .onFailure { UpdateState.lastError = "Couldn't install the update: ${it.message}" }
        return Result.success()
    }

    companion object {
        private const val NAME = "app-update"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
