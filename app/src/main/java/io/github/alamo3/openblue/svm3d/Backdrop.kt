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
        const val RADIUS = 12.5
        const val EYE_Z = 1.0
        /** Where the dome meets the floor (horizontal distance from the middle of the car). */
        val FLOOR_REACH = sqrt(RADIUS * RADIUS - EYE_Z * EYE_Z)
        /** The lowest elevation the dome needs (where it meets the floor). */
        val DOME_BOTTOM_DEG = Math.toDegrees(-asin(EYE_Z / RADIUS))

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
                        val wgt = ((cosTh - cos(SvmCamera.MAX_THETA)) / (1 - cos(SvmCamera.MAX_THETA))).coerceAtLeast(0.0).pow(10)
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
         * Stitches and paints in the rest with [model] (null: leave it dark). Three overlapping
         * passes, each over half the panorama and keeping the middle third of what it paints, so each
         * part is painted with what's on both sides of it in view.
         */
        fun build(frames: List<Fisheye>, model: Inpainter?, progress: (Float) -> Unit = {}): Backdrop {
            val w = WIDTH; val h = HEIGHT
            val seen = BooleanArray(w * h)
            val pano = stitch(frames, seen)
            if (model == null) return Backdrop(w, h, pano)
            val have = Fisheye.erode(seen, w, h, 1)
            val s = Inpainter.SIZE
            for (pass in 0 until 3) {
                progress(pass / 3f)
                val x0 = pass * w / 3 - w / 4  // panorama column at the tile's left edge (wraps)
                val rgb = ByteArray(s * s * 3); val hole = BooleanArray(s * s)
                // Tile: half the panorama (w/2 columns) by its full height, squeezed to s x s.
                var mr = 0L; var mg = 0L; var mb = 0L; var mc = 0
                for (k in pano.indices) if (have[k]) { mr += (pano[k] shr 16) and 0xFF; mg += (pano[k] shr 8) and 0xFF; mb += pano[k] and 0xFF; mc++ }
                val mean = if (mc == 0) 0x202020 else (((mr / mc).toInt()) shl 16) or (((mg / mc).toInt()) shl 8) or (mb / mc).toInt()
                val sx = (w / 2).toDouble() / s; val sy = h.toDouble() / s
                for (ty in 0 until s) for (tx in 0 until s) {
                    var cr = 0; var cg = 0; var cb = 0; var cnt = 0; var miss = false
                    val ya = (ty * sy).toInt(); val yb = max(ya + 1, ((ty + 1) * sy).toInt())
                    val xa = (tx * sx).toInt(); val xb = max(xa + 1, ((tx + 1) * sx).toInt())
                    for (yy in ya until min(h, yb)) for (xx in xa until xb) {
                        val k = yy * w + Math.floorMod(x0 + xx, w)
                        if (!have[k]) { miss = true; continue }
                        val p = pano[k]; cr += (p shr 16) and 0xFF; cg += (p shr 8) and 0xFF; cb += p and 0xFF; cnt++
                    }
                    val o = ty * s + tx
                    val p = if (miss || cnt == 0) mean else ((cr / cnt) shl 16) or ((cg / cnt) shl 8) or (cb / cnt)
                    hole[o] = miss
                    rgb[3 * o] = (p shr 16).toByte(); rgb[3 * o + 1] = (p shr 8).toByte(); rgb[3 * o + 2] = p.toByte()
                }
                if (hole.none { it }) continue
                val painted = model.inpaint(rgb, hole)
                // Write back the middle third of the tile (one third of the panorama), holes only.
                val keep = Math.floorDiv((2 * pass - 1) * w, 6) until Math.floorDiv((2 * pass + 1) * w, 6)
                for (y in 0 until h) for (col in keep) {
                    val xx = col - x0
                    val k = y * w + Math.floorMod(col, w)
                    if (have[k]) continue
                    val fx = ((xx + 0.5) / sx - 0.5).coerceIn(0.0, s - 1.0); val fy = ((y + 0.5) / sy - 0.5).coerceIn(0.0, s - 1.0)
                    val ia = fx.toInt(); val ja = fy.toInt(); val ib = min(s - 1, ia + 1); val jb = min(s - 1, ja + 1)
                    val wx = fx - ia; val wy = fy - ja
                    var p = 0
                    for (ch in 0..2) {
                        fun at(j: Int, i: Int) = (painted[3 * (j * s + i) + ch].toInt() and 0xFF).toDouble()
                        val v = (at(ja, ia) * (1 - wx) + at(ja, ib) * wx) * (1 - wy) + (at(jb, ia) * (1 - wx) + at(jb, ib) * wx) * wy
                        p = p or ((v + 0.5).toInt().coerceIn(0, 255) shl (16 - 8 * ch))
                    }
                    pano[k] = p
                }
                for (y in 0 until h) for (col in keep) have[y * w + Math.floorMod(col, w)] = true
            }
            progress(1f)
            return Backdrop(w, h, pano)
        }
    }
}
