package io.github.alamo3.openblue.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * World Labs' World API (Marble): turns a 360° panorama into an explorable 3D world, generated in
 * their cloud and billed to the user's own API key. Only used when asked, from the 3D view.
 */
class WorldLabs(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build(),
) {
    /** A generation in progress (or finished). */
    data class Operation(val id: String, val done: Boolean, val worldId: String?, val progress: String?, val error: String?)

    data class World(
        val id: String,
        /** Their web viewer for the world. */
        val viewerUrl: String?,
        val panoUrl: String?,
        val thumbnailUrl: String?,
        /** Gaussian splats (SPZ), largest. */
        val splatUrl: String?,
        /** A rough collision mesh (GLB), included at no extra cost. */
        val colliderMeshUrl: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Starts generating a world from an equirectangular [panoJpeg]; returns the operation to poll. */
    fun generate(panoJpeg: ByteArray, name: String, model: String = DRAFT): Operation {
        val body = buildJsonObject {
            put("display_name", name)
            put("model", model)
            putJsonObject("world_prompt") {
                put("type", "image")
                putJsonObject("image_prompt") {
                    put("source", "data_base64")
                    put("data_base64", java.util.Base64.getEncoder().encodeToString(panoJpeg))
                    put("extension", "jpg")
                    put("is_pano", true)
                }
            }
        }
        return operation(call("POST", "worlds:generate", body))
    }

    fun poll(operationId: String): Operation = operation(call("GET", "operations/$operationId"))

    fun world(worldId: String): World {
        val o = call("GET", "worlds/$worldId")
        val w = (o["world"] as? JsonObject) ?: o
        return World(
            id = w.str("id") ?: worldId,
            viewerUrl = w.str("world_marble_url"),
            panoUrl = w.str("assets.imagery.pano_url"),
            thumbnailUrl = w.str("assets.thumbnail_url"),
            splatUrl = w.str("assets.splats.spz_urls.full_res") ?: w.str("assets.splats.spz_urls.500k"),
            colliderMeshUrl = w.str("assets.mesh.collider_mesh_url"),
        )
    }

    private fun operation(o: JsonObject): Operation {
        val err = o.at("error") as? JsonObject
        val progress = o.at("metadata.progress")?.let { p ->
            (p as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (p as? JsonObject)?.let { it.str("description") ?: it.str("status") }
        }
        return Operation(
            id = o.str("operation_id") ?: throw IOException("World Labs didn't return an operation"),
            done = o.str("done") == "true",
            worldId = o.str("metadata.world_id") ?: o.str("response.id") ?: o.str("response.world_id"),
            progress = progress,
            error = err?.let { it.str("message") ?: it.str("code") ?: "Generation failed" },
        )
    }

    private fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
        val req = Request.Builder()
            .url("$BASE/$path")
            .header("WLT-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?.let { it.str("detail") ?: it.str("message") ?: it.str("error.message") ?: it.str("error") }
                throw IOException(
                    when (r.code) {
                        401, 403 -> "World Labs didn't accept the API key"
                        402 -> "Not enough World Labs credits"
                        429 -> "World Labs is busy (rate limit); try again in a bit"
                        else -> "World Labs answered ${r.code}${msg?.let { ": $it" } ?: ""}"
                    },
                )
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        private const val BASE = "https://api.worldlabs.ai/marble/v1"
        private val JSON = "application/json".toMediaType()

        /** The quick, cheap model: 150 credits (about $0.12) for a panorama. */
        const val DRAFT = "marble-1.0-draft"
    }
}
