package io.github.alamo3.openblue.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.alamo3.openblue.R
import io.github.alamo3.openblue.ui.CarBody
import io.github.alamo3.openblue.ui.CarPaint
import kotlin.math.roundToInt

/** Recolors only painted panels in the widget's still, retaining glass, wheels and lighting. */
internal object WidgetCarImage {
    private val cache by lazy { LruCache<String, Bitmap>(4) }

    @Synchronized
    fun render(context: Context, body: CarBody, paint: CarPaint): Bitmap? {
        if (paint == CarPaint.WHITE) return null // The bundled still is already white.
        val key = "${body.name}:${paint.name}"
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false }
        val source = BitmapFactory.decodeResource(context.resources, body.still, opts) ?: return null
        val mask = BitmapFactory.decodeResource(context.resources, maskFor(body)) ?: return null
        if (source.width != mask.width || source.height != mask.height) return null
        val pixels = IntArray(source.width * source.height)
        val masks = IntArray(pixels.size)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        mask.getPixels(masks, 0, mask.width, 0, 0, mask.width, mask.height)
        for (i in pixels.indices) pixels[i] = recolorPixel(pixels[i], masks[i] and 0xff, paint.swatch)
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
    }

    internal fun recolorPixel(pixel: Int, mask: Int, color: Long): Int {
        if (mask == 0 || pixel ushr 24 == 0) return pixel
        val opacity = mask / 255f
        fun channel(shift: Int): Int {
            val original = (pixel ushr shift) and 0xff
            val target = ((color ushr shift) and 0xff).toFloat()
            // Preserve the still's highlights and shadows while shifting its white paint.
            val multiplier = (target / 220f).coerceAtMost(1f)
            val light = original / 255f
            val reflection = light * light * light * light * light * .35f
            val shaded = multiplier + (1f - multiplier) * reflection
            return (original * (1f - opacity + opacity * shaded)).roundToInt().coerceIn(0, 255)
        }
        return (pixel and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun maskFor(body: CarBody): Int = when (body) {
        CarBody.IONIQ5 -> R.drawable.car_paintmask
        CarBody.IONIQ5_STANDARD -> R.drawable.car_paintmask_ioniq5
        CarBody.IONIQ6 -> R.drawable.car_paintmask_ioniq6
        CarBody.IONIQ9 -> R.drawable.car_paintmask_ioniq9
        CarBody.KONA_ELECTRIC -> R.drawable.car_paintmask_kona_electric
        CarBody.ELANTRA -> R.drawable.car_paintmask_elantra
        CarBody.ELANTRA_N -> R.drawable.car_paintmask_elantra_n
        CarBody.KONA -> R.drawable.car_paintmask_kona
        CarBody.PALISADE -> R.drawable.car_paintmask_palisade
        CarBody.SANTA_FE -> R.drawable.car_paintmask_santa_fe
        CarBody.SONATA -> R.drawable.car_paintmask_sonata
        CarBody.TUCSON -> R.drawable.car_paintmask_tucson
        CarBody.VENUE -> R.drawable.car_paintmask_venue
        CarBody.SEDAN -> R.drawable.car_paintmask_sedan
        CarBody.CROSSOVER -> R.drawable.car_paintmask_crossover
        CarBody.SUV -> R.drawable.car_paintmask_suv
    }
}
