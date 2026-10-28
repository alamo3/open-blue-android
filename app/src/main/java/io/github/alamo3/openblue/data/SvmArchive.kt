package io.github.alamo3.openblue.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import android.provider.MediaStore
import io.github.alamo3.openblue.api.SvmCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One set of surround-view photos: the car's JPEG strip (front, rear, left and right frames side
 * by side, then the top view) and the frame sizes needed to cut it up. The bytes are read only
 * when needed, since the archive can hold many of these.
 */
class SvmShot(val utcTime: String?, val imageSize: List<Int>, private val read: () -> ByteArray) {
    val frameWidth: Int? get() = imageSize.getOrNull(2)
    val frameHeight: Int? get() = imageSize.getOrNull(3)
    val topWidth: Int? get() = imageSize.getOrNull(4)
    val topHeight: Int? get() = imageSize.getOrNull(5)

    fun bytes(): ByteArray = read()

    /** When the car took it, or null if Bluelink didn't say. */
    val takenAt: Date?
        get() = utcTime?.let {
            runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it) }.getOrNull()
        }

    /** The views, top first: ("Top", bitmap), ("Front", …), ("Rear", …), ("Left", …), ("Right", …). */
    fun views(): List<Pair<String, Bitmap>> {
        val data = bytes()
        val full = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return emptyList()
        val w = frameWidth
        val h = frameHeight
        if (w == null || h == null || w <= 0) return listOf("Photo" to full)
        fun crop(x: Int, width: Int, height: Int): Bitmap? {
            if (x >= full.width) return null
            val cw = minOf(width, full.width - x)
            val ch = minOf(height, full.height)
            return if (cw > 0 && ch > 0) Bitmap.createBitmap(full, x, 0, cw, ch) else null
        }
        val out = mutableListOf<Pair<String, Bitmap>>()
        val tw = topWidth
        val th = topHeight
        if (tw != null && th != null) crop(4 * w, tw, th)?.let { out += "Top" to it }
        listOf("Front", "Rear", "Left", "Right").forEachIndexed { i, name -> crop(i * w, w, h)?.let { out += name to it } }
        return out
    }

    /** A small picture of the top view (or the whole strip), for lists. */
    fun thumbnail(maxSide: Int = 240): Bitmap? = runCatching {
        val data = bytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val w = frameWidth
        val tw = topWidth
        val th = topHeight
        val region = if (w != null && tw != null && th != null && 4 * w + tw <= bounds.outWidth) {
            android.graphics.Rect(4 * w, 0, 4 * w + tw, minOf(th, bounds.outHeight))
        } else {
            android.graphics.Rect(0, 0, bounds.outWidth, bounds.outHeight)
        }
        var sample = 1
        while (maxOf(region.width(), region.height()) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        @Suppress("DEPRECATION")
        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(data, 0, data.size) else BitmapRegionDecoder.newInstance(data, 0, data.size, false)
        decoder?.decodeRegion(region, opts).also { decoder?.recycle() }
    }.getOrNull()

    companion object {
        fun of(c: SvmCapture) = SvmShot(c.utcTime, c.imageSize) { c.jpeg }
    }
}

/**
 * Keeps every surround-view capture the app has seen, so older ones stay viewable after
 * Bluelink replaces them. Stored in the app's private storage, one folder per vehicle:
 * `<utcTime>.jpg` (the car's JPEG strip exactly as sent) plus `<utcTime>.size` (its frame sizes).
 */
class SvmArchive(private val root: File, private val keep: Int = 200) {

    private fun dir(vehicleId: String) = File(root, vehicleId.filter { it.isLetterOrDigit() || it == '-' }).apply { mkdirs() }

    private fun key(utcTime: String) = utcTime.filter { it.isLetterOrDigit() }

    /** Adds any captures not already stored, and drops the oldest past [keep]. */
    @Synchronized
    fun add(vehicleId: String, captures: List<SvmCapture>) {
        val d = dir(vehicleId)
        for (c in captures) {
            val k = key(c.utcTime ?: continue)
            val jpg = File(d, "$k.jpg")
            if (jpg.exists()) continue
            File(d, "$k.size").writeText(c.imageSize.joinToString(","))
            val tmp = File(d, "$k.jpg.tmp")
            tmp.writeBytes(c.jpeg)
            tmp.renameTo(jpg)
        }
        files(d).drop(keep).forEach { delete(d, it.nameWithoutExtension) }
    }

    /** Everything stored for the vehicle, newest first. */
    @Synchronized
    fun list(vehicleId: String): List<SvmShot> = files(dir(vehicleId)).map { jpg ->
        val size = File(jpg.parentFile, "${jpg.nameWithoutExtension}.size").takeIf { it.exists() }?.readText()
        SvmShot(jpg.nameWithoutExtension, size?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()) { jpg.readBytes() }
    }

    @Synchronized
    fun remove(vehicleId: String, utcTime: String) = delete(dir(vehicleId), key(utcTime))

    @Synchronized
    fun clear(vehicleId: String) {
        dir(vehicleId).deleteRecursively()
    }

    private fun files(d: File) = d.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedByDescending { it.name }

    private fun delete(d: File, name: String) {
        File(d, "$name.jpg").delete()
        File(d, "$name.size").delete()
    }
}

/** Saves pictures to the phone's gallery, in Pictures/Open Blue. */
object Gallery {
    val supported get() = Build.VERSION.SDK_INT >= 29

    /** Returns how many were saved. */
    fun save(context: Context, takenAt: Date?, pictures: List<Pair<String, Bitmap>>): Int {
        if (!supported) return 0
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(takenAt ?: Date())
        var saved = 0
        for ((name, bitmap) in pictures) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "OpenBlue_${stamp}_$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Open Blue")
                takenAt?.let { put(MediaStore.Images.Media.DATE_TAKEN, it.time) }
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true
            }.getOrDefault(false)
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                saved++
            } else {
                resolver.delete(uri, null, null)
            }
        }
        return saved
    }
}
