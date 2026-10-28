package io.github.alamo3.openblue.svm3d

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The far surroundings as a 360° panorama seen from just above the middle of the car, drawn on a
 * dome of radius [RADIUS] round the scene. Whatever no camera saw (mostly sky and tree tops: the
 * cameras look down) is painted in by the inpainting model. Rows run from [TOP_DEG] elevation down
 * to [BOTTOM_DEG]; columns from straight ahead clockwise (to the right) round to straight ahead.
 */
class Backdrop(val width: Int, val height: Int, val rgb: IntArray) {
    companion object {
        const val WIDTH = 2048
        const val HEIGHT = 512
        const val TOP_DEG = 80.0
        const val BOTTOM_DEG = -10.0
        /** Dome radius, metres, round [EYE_Z] above the middle of the car. */
        const val RADIUS = 9.0
        const val EYE_Z = 1.0
        /** Where the dome meets the floor (horizontal distance from the middle of the car). */
        val FLOOR_REACH = sqrt(RADIUS * RADIUS - EYE_Z * EYE_Z)
        /** The lowest elevation the dome needs (where it meets the floor). */
        val DOME_BOTTOM_DEG = Math.toDegrees(-asin(EYE_Z / RADIUS))

        private val COS_LIMIT = cos(Math.toRadians(82.0))

        fun elevationOfRow(y: Double) = TOP_DEG - (y + 0.5) / HEIGHT * (TOP_DEG - BOTTOM_DEG)
        fun rowOfElevation(e: Double) = (TOP_DEG - e) / (TOP_DEG - BOTTOM_DEG) * HEIGHT - 0.5

        /** Stitches the panorama from the four cameras; [seen] (WIDTH*HEIGHT) gets what a camera saw. */
        fun stitch(frames: List<Fisheye>, seen: BooleanArray): IntArray {
            val w = WIDTH; val h = HEIGHT
            val out = IntArray(w * h)
            val uv = DoubleArray(2)
            val boxes = frames.map { SurroundBuilder.ownCarBox(it.camera) }
            val d = DoubleArray(3)
            for (y in 0 until h) {
                val e = Math.toRadians(elevationOfRow(y.toDouble()))
                for (x in 0 until w) {
                    val az = Math.toRadians((x + 0.5) / w * 360.0)
                    val dx = sin(az) * cos(e); val dy = cos(az) * cos(e); val dz = sin(e)
                    // The point this direction shows: on the dome, or on the floor if that comes first.
                    var t = RADIUS
                    if (dz < -1e-6) t = min(t, EYE_Z / -dz)
                    val px = dx * t; val py = dy * t; val pz = EYE_Z + dz * t
                    var sw = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                    for ((fi, fe) in frames.withIndex()) {
                        val cam = fe.camera
                        if (!cam.project(px, py, pz, uv)) continue
                        if (uv[0] < 0 || uv[0] >= fe.width - 1 || uv[1] < 0 || uv[1] >= fe.height - 1) continue
                        if (!fe.mask[uv[1].toInt() * fe.width + uv[0].toInt()]) continue
                        d[0] = px - cam.x; d[1] = py - cam.y; d[2] = pz - cam.z
                        val (lo, hi) = boxes[fi]
                        if (SurroundBuilder.hitsBox(cam.position, d, lo, hi)) continue
                        // Favour the camera looking most straight at it (sharpest, least parallax);
                        // the high power keeps it close to one camera per spot, without hard seams.
                        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                        val cosTh = (cam.r[2] * d[0] + cam.r[5] * d[1] + cam.r[8] * d[2]) / len
                        // The lens's outer rim shows the camera housing, not the world: stop short of it.
                        if (cosTh < COS_LIMIT) continue
                        val wgt = ((cosTh - COS_LIMIT) / (1 - COS_LIMIT)).pow(6)
                        if (wgt <= 1e-9) continue
                        val p = fe.sample(uv[0], uv[1])
                        r += wgt * ((p shr 16) and 0xFF); g += wgt * ((p shr 8) and 0xFF); b += wgt * (p and 0xFF); sw += wgt
                    }
                    val k = y * w + x
                    if (sw > 0) {
                        out[k] = ((r / sw).toInt() shl 16) or ((g / sw).toInt() shl 8) or (b / sw).toInt()
                        seen[k] = true
                    }
                }
            }
            return out
        }

        /**
         * Stitches the panorama and fills what no camera saw. That's almost all sky above the
         * picture (the cameras look down), which the inpainting model paints as murky texture, so
         * the sky is continued straight up from its colour along the top of what was seen, smoothed
         * sideways. Small gaps lower down (behind a mirror, say) get the same treatment.
         */
        fun build(frames: List<Fisheye>): Backdrop {
            val w = WIDTH; val h = HEIGHT
            val seen = BooleanArray(w * h)
            val pano = stitch(frames, seen)
            val have = Fisheye.erode(seen, w, h, 1)
            // Each column's sky colour: the median of the first few seen pixels from the top.
            val top = Array(3) { DoubleArray(w) }; val found = BooleanArray(w)
            for (x in 0 until w) {
                val vals = ArrayList<Int>()
                for (y in 0 until h) { if (have[y * w + x]) { vals += pano[y * w + x]; if (vals.size >= 9) break } }
                if (vals.isEmpty()) continue
                found[x] = true
                for (ch in 0..2) top[ch][x] = vals.map { (it shr (16 - 8 * ch)) and 0xFF }.sorted()[vals.size / 2].toDouble()
            }
            if (found.none { it }) return Backdrop(w, h, pano)
            // Smooth round the circle (wrapping), ignoring columns with nothing seen: lightly for the
            // colour right where the picture ends, heavily for the open sky further up (so a roof
            // edge doesn't streak all the way up).
            fun smooth(sigma: Double): Array<DoubleArray> {
                val rad = (3 * sigma).toInt()
                val kern = DoubleArray(2 * rad + 1) { kotlin.math.exp(-((it - rad) * (it - rad)) / (2 * sigma * sigma)) }
                val out = Array(3) { DoubleArray(w) }
                for (x in 0 until w) {
                    var sw = 0.0; val acc = DoubleArray(3)
                    for (o in -rad..rad) {
                        val xx = Math.floorMod(x + o, w)
                        if (!found[xx]) continue
                        val k = kern[o + rad]; sw += k
                        for (ch in 0..2) acc[ch] += k * top[ch][xx]
                    }
                    for (ch in 0..2) out[ch][x] = if (sw > 0) acc[ch] / sw else 0.0
                }
                return out
            }
            val edgeSky = smooth(12.0); val openSky = smooth(220.0)
            val dist = FloorFill.distanceTo(have, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val k = y * w + x
                if (have[k]) continue
                val d = dist[k].toDouble()
                val up = (d / 80.0).coerceIn(0.0, 1.0)   // edge colour -> open sky over ~14 degrees
                val a = (d / 6.0).coerceIn(0.0, 1.0)     // feather into what was seen
                var p = 0
                for (ch in 0..2) {
                    val sky = edgeSky[ch][x] * (1 - up) + openSky[ch][x] * up
                    val near = if (seen[k]) ((pano[k] shr (16 - 8 * ch)) and 0xFF).toDouble() else sky
                    p = p or (((near * (1 - a) + sky * a) + 0.5).toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                }
                pano[k] = p
            }
            return Backdrop(w, h, pano)
        }
    }
}
