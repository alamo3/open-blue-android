package io.github.alamo3.openblue.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.alamo3.openblue.api.WorldLabs
import io.github.alamo3.openblue.data.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where a World Labs generation for one set of surround-view photos stands. */
@Serializable
data class WorldJob(
    /** "sending", "generating", "done" or "failed". */
    val status: String,
    val operationId: String? = null,
    val worldId: String? = null,
    val viewerUrl: String? = null,
    val thumbnailUrl: String? = null,
    val progress: String? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val running: Boolean get() = status == "sending" || status == "generating"
}

/** Generation state per photo set (keyed like the 3D scene cache), kept on the phone. */
object WorldJobs {
    private const val PREFS = "worlds"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(context: Context, key: String): WorldJob? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { runCatching { json.decodeFromString(WorldJob.serializer(), it) }.getOrNull() }

    fun put(context: Context, key: String, job: WorldJob?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (job == null) remove(key) else putString(key, json.encodeToString(WorldJob.serializer(), job))
        }.apply()
    }
}

/**
 * Sends a panorama to World Labs and follows the generation (a few minutes) in short runs, one
 * every [POLL_SECONDS], so it carries on with the app closed; posts a notification when it's done.
 */
class WorldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString(KEY_SHOT) ?: return Result.failure()
        val panoPath = inputData.getString(KEY_PANO) ?: return Result.failure()
        val apiKey = SecureStore(ctx).worldLabsKey ?: run {
            WorldJobs.put(ctx, key, WorldJob("failed", error = "No World Labs API key (Settings > Experiments)"))
            return Result.failure()
        }
        val api = WorldLabs(apiKey)
        var job = WorldJobs.get(ctx, key) ?: WorldJob("sending")
        try {
            if (job.operationId == null) {
                val pano = File(panoPath).takeIf { it.exists() }?.readBytes()
                    ?: throw IOException("The panorama for these photos is missing; open the 3D view again to rebuild it")
                val op = api.generate(pano, "Open Blue · ${key.substringAfter('_')}")
                job = job.copy(status = "generating", operationId = op.id, worldId = op.worldId)
                WorldJobs.put(ctx, key, job)
            }
            val op = api.poll(job.operationId!!)
            when {
                op.error != null -> {
                    job = job.copy(status = "failed", error = op.error)
                    WorldJobs.put(ctx, key, job)
                    Notifier.post(ctx, Notifier.CHANNEL_WORLDS, key.hashCode(), "3D world didn't generate", op.error)
                    return Result.success()
                }
                op.done -> {
                    val world = api.world(op.worldId ?: job.worldId ?: throw IOException("World Labs finished without a world"))
                    job = job.copy(status = "done", worldId = world.id, viewerUrl = world.viewerUrl, thumbnailUrl = world.thumbnailUrl, progress = null)
                    WorldJobs.put(ctx, key, job)
                    val tap = world.viewerUrl?.let { url ->
                        PendingIntent.getActivity(
                            ctx, key.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    }
                    if (!Notifier.appInForeground()) {
                        Notifier.post(ctx, Notifier.CHANNEL_WORLDS, key.hashCode(), "Your 3D world is ready", "Tap to look round it in World Labs' viewer.", tap)
                    }
                    return Result.success()
                }
                else -> {
                    job = job.copy(status = "generating", worldId = op.worldId ?: job.worldId, progress = op.progress)
                    WorldJobs.put(ctx, key, job)
                    if (System.currentTimeMillis() - job.startedAt > GIVE_UP_MS) {
                        WorldJobs.put(ctx, key, job.copy(status = "failed", error = "Still not done after 30 minutes"))
                        return Result.success()
                    }
                    enqueue(ctx, key, panoPath, POLL_SECONDS)
                    return Result.success()
                }
            }
        } catch (e: IOException) {
            // Couldn't reach World Labs, or it said no. Keep trying a dropped connection for a while.
            if (job.operationId != null && System.currentTimeMillis() - job.startedAt < GIVE_UP_MS && e.message?.startsWith("World Labs") != true) {
                enqueue(ctx, key, panoPath, POLL_SECONDS * 2)
                return Result.success()
            }
            WorldJobs.put(ctx, key, job.copy(status = "failed", error = e.message ?: "Couldn't reach World Labs"))
            return Result.success()
        }
    }

    companion object {
        const val KEY_SHOT = "shot"
        const val KEY_PANO = "pano"
        private const val POLL_SECONDS = 15L
        private const val GIVE_UP_MS = 30 * 60_000L

        /** Starts (or, with [delaySeconds], continues) the generation for photo set [key]. */
        fun enqueue(context: Context, key: String, panoPath: String, delaySeconds: Long = 0) {
            val req = OneTimeWorkRequestBuilder<WorldWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_SHOT to key, KEY_PANO to panoPath))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("world-$key", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
