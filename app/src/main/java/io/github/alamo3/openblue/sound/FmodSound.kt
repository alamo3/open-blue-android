package io.github.alamo3.openblue.sound

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * An FMOD sound set imported onto the phone: a folder made by `fmod_bank.py export` in the
 * virtual-car-sound project -- the car's bank (e.g. an Assetto Corsa car mod), a master bank
 * for it, and sound.json saying which events to play and how loud.
 *
 * These are never bundled with the app: game mods, and FMOD's example media that the master
 * bank is made from, may not be redistributed. You copy them to the phone and import them.
 */
data class FmodSound(
    val id: String,
    val name: String,
    val dir: File,
    val carBank: String,
    val masterBank: String,
    val masterBus: String?,
    val idleRpm: Float,
    val redline: Float,
    val gainDb: Float,
    /** "engine" (looping, rpms + throttle), "gear", "limiter", "backfire": event GUIDs. */
    val events: Map<String, String>,
    /** Parameter names each event has. */
    val params: Map<String, List<String>>,
) {
    companion object {
        fun read(dir: File): FmodSound? = runCatching {
            val o = org.json.JSONObject(File(dir, "sound.json").readText())
            val ev = o.getJSONObject("events")
            val events = HashMap<String, String>()
            val params = HashMap<String, List<String>>()
            for (k in ev.keys()) {
                val e = ev.getJSONObject(k)
                events[k] = e.getString("guid")
                params[k] = e.optJSONArray("params")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            }
            if ("engine" !in events) return null
            FmodSound(
                id = dir.name,
                name = o.optString("name", dir.name),
                dir = dir,
                carBank = o.optString("car_bank", "car.bank"),
                masterBank = o.optString("master_bank", "master.bank"),
                masterBus = o.optString("master_bus").takeIf { it.isNotEmpty() },
                idleRpm = o.optDouble("idle_rpm", 1100.0).toFloat(),
                redline = o.optDouble("redline_rpm", 8500.0).toFloat(),
                gainDb = o.optDouble("gain_db", 0.0).toFloat(),
                events = events,
                params = params,
            )
        }.getOrNull()
    }
}

/** The imported FMOD sound sets, kept in the app's private storage. */
object FmodLibrary {
    private fun root(context: Context) = File(context.filesDir, "fmod")

    fun list(context: Context): List<FmodSound> =
        root(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { FmodSound.read(it) }?.sortedBy { it.name }
            ?: emptyList()

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
            for (f in listOf(info.carBank, info.masterBank)) {
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
