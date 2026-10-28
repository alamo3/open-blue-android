package io.github.alamo3.openblue.svm3d

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The two AI models the 3D view needs, downloaded once from their projects' GitHub releases
 * (too big to ship in the app): Depth Anything V2 Small (Apache-2.0) and YOLO11n (AGPL-3.0).
 */
class ModelStore(private val dir: File) {
    class Model(val file: String, val url: String, val approxBytes: Long)

    companion object {
        val DEPTH = Model(
            "depth_anything_v2_vits.onnx",
            "https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits.onnx",
            99_373_606,
        )
        val DETECTOR = Model(
            "yolo11n.onnx",
            "https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.onnx",
            10_930_182,
        )
        val ALL = listOf(DEPTH, DETECTOR)
        val TOTAL_BYTES = ALL.sumOf { it.approxBytes }
    }

    fun file(m: Model) = File(dir, m.file)

    val ready: Boolean get() = ALL.all { file(it).length() > it.approxBytes / 2 }

    /** Downloads whatever is missing. [progress] gets 0..1 across both. */
    fun download(progress: (Float) -> Unit) {
        dir.mkdirs()
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
        var done = 0L
        for (m in ALL) {
            val dest = file(m)
            if (dest.length() > m.approxBytes / 2) { done += m.approxBytes; continue }
            val tmp = File(dir, m.file + ".part")
            client.newCall(Request.Builder().url(m.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code}) for ${m.file}")
                val body = resp.body ?: throw IOException("Empty download for ${m.file}")
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            got += r
                            progress(((done + got).toFloat() / TOTAL_BYTES).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save ${m.file}")
            done += m.approxBytes
        }
        progress(1f)
    }
}
