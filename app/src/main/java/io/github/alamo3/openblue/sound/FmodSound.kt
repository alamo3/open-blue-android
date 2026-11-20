package io.github.alamo3.openblue.sound

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * An FMOD sound set imported onto the phone: a folder made by `fmod_bank.py export` /
 * `export-jet` in the virtual-car-sound project, with its banks and sound.json saying which
 * events to play, what drives them and how loud.
 *
 *  - kind "car" (format 1): a game car's bank (e.g. an Assetto Corsa mod) plus a master bank
 *    made for it; its engine event follows the virtual drivetrain (rpms, throttle), with gear,
 *    limiter and backfire one-shots
 *  - kind "jet" (format 2): an aircraft's own bank (e.g. X-Plane); looping layers whose
 *    parameters follow a turbofan spool model, an afterburner near full pedal, and a launch
 *    boom sample
 *
 * These are never bundled with the app: game mods, and FMOD's example media that a car's
 * master bank is made from, may not be redistributed. You copy them to the phone and import.
 */
data class FmodSound(
    val id: String,
    val name: String,
    val dir: File,
    val kind: String,
    /** Banks to load, in order (master first). */
    val banks: List<String>,
    val masterBus: String?,
    val idleRpm: Float,
    val redline: Float,
    val gainDb: Float,
    /** Cars: "engine" (looping, rpms + throttle), "gear", "limiter", "backfire": event GUIDs. */
    val events: Map<String, String>,
    /** Cars: parameter names each event has. */
    val params: Map<String, List<String>>,
    /** Jets: the looping layers. */
    val loops: List<Loop> = emptyList(),
    val jet: Jet? = null,
    /** Jets: a launch sound file and its level. */
    val boomFile: String? = null,
    val boomGainDb: Float = 0f,
) {
    /** A looping layer: its event, level, and each parameter's source (see [FmodVoice]). */
    data class Loop(val name: String, val guid: String, val gainDb: Float, val params: Map<String, String>)

    data class Jet(val idleFrac: Float, val maxRad: Float, val upPerSec: Float, val downPerSec: Float, val afterburnerPedal: Float)

    val isJet: Boolean get() = kind == "jet"

    /** Every file the set needs. */
    val files: List<String> get() = banks + listOfNotNull(boomFile)

    companion object {
        fun read(dir: File): FmodSound? = runCatching {
            val o = org.json.JSONObject(File(dir, "sound.json").readText())
            val kind = o.optString("kind", "car")
            val events = HashMap<String, String>()
            val params = HashMap<String, List<String>>()
            o.optJSONObject("events")?.let { ev ->
                for (k in ev.keys()) {
                    val e = ev.getJSONObject(k)
                    events[k] = e.getString("guid")
                    params[k] = e.optJSONArray("params")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                }
            }
            val loops = o.optJSONArray("loops")?.let { a ->
                (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    val ps = l.optJSONObject("params")
                    Loop(
                        l.optString("name"), l.getString("guid"), l.optDouble("gain_db", 0.0).toFloat(),
                        ps?.keys()?.asSequence()?.associateWith { ps.getString(it) } ?: emptyMap(),
                    )
                }
            } ?: emptyList()
            val banks = o.optJSONArray("banks")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: listOf(o.optString("master_bank", "master.bank"), o.optString("car_bank", "car.bank"))
            if (kind == "jet" && loops.isEmpty()) return null
            if (kind != "jet" && "engine" !in events) return null
            FmodSound(
                id = dir.name,
                name = o.optString("name", dir.name),
                dir = dir,
                kind = kind,
                banks = banks,
                masterBus = o.optString("master_bus").takeIf { it.isNotEmpty() && it != "null" },
                idleRpm = o.optDouble("idle_rpm", 1100.0).toFloat(),
                redline = o.optDouble("redline_rpm", 8500.0).toFloat(),
                gainDb = o.optDouble("gain_db", 0.0).toFloat(),
                events = events,
                params = params,
                loops = loops,
                jet = o.optJSONObject("jet")?.let { j ->
                    Jet(
                        j.optDouble("idle_frac", 0.6).toFloat(), j.optDouble("max_rad", 1.0).toFloat(),
                        j.optDouble("up_per_sec", 0.15).toFloat(), j.optDouble("down_per_sec", 0.2).toFloat(),
                        j.optDouble("afterburner_pedal", 2.0).toFloat(),
                    )
                },
                boomFile = o.optJSONObject("boom")?.optString("file")?.takeIf { it.isNotEmpty() },
                boomGainDb = o.optJSONObject("boom")?.optDouble("gain_db", 0.0)?.toFloat() ?: 0f,
            )
        }.getOrNull()
    }
}

/** The imported FMOD sound sets, kept in the app's private storage. */
object FmodLibrary {
    private fun root(context: Context) = File(context.filesDir, "fmod")

    /** The imported sets; empty where there's no storage (e.g. a stub context in tests). */
    fun list(context: Context): List<FmodSound> = runCatching {
        root(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { FmodSound.read(it) }?.sortedBy { it.name }
    }.getOrNull() ?: emptyList()

    fun get(context: Context, id: String?): FmodSound? =
        id?.let { FmodSound.read(File(root(context), it)) }

    fun remove(context: Context, id: String) {
        File(root(context), id).deleteRecursively()
    }

    /**
     * Imports from a folder picked with ACTION_OPEN_DOCUMENT_TREE: the folder itself if it holds
     * a sound.json, else every subfolder that does. Returns the names imported.
     */
    fun import(context: Context, tree: Uri): List<String> {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)

        data class Doc(val id: String, val name: String, val dir: Boolean)

        fun children(docId: String): List<Doc> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val out = ArrayList<Doc>()
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Doc(c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
            return out
        }

        fun importFolder(docId: String, name: String): String? {
            val files = children(docId).filter { !it.dir }.associateBy { it.name }
            val json = files["sound.json"] ?: return null
            val tmp = File(root(context), ".$name.tmp").apply { deleteRecursively(); mkdirs() }
            fun copy(d: Doc) {
                resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))?.use { input ->
                    File(tmp, d.name).outputStream().use { input.copyTo(it) }
                }
            }
            copy(json)
            val info = FmodSound.read(tmp)
            if (info == null) { tmp.deleteRecursively(); return null }
            val sid = runCatching { org.json.JSONObject(File(tmp, "sound.json").readText()).optString("id") }
                .getOrNull()?.takeIf { it.isNotBlank() && !it.contains('/') } ?: name
            for (f in info.files) {
                val d = files[f]
                if (d == null) { tmp.deleteRecursively(); return null }
                copy(d)
            }
            val dest = File(root(context), sid)
            dest.deleteRecursively()
            tmp.renameTo(dest)
            return FmodSound.read(dest)?.name
        }

        importFolder(rootDoc, rootDoc.substringAfterLast('/').substringAfterLast(':').ifEmpty { "sound" })?.let { return listOf(it) }
        return children(rootDoc).filter { it.dir }.mapNotNull { importFolder(it.id, it.name) }
    }
}
