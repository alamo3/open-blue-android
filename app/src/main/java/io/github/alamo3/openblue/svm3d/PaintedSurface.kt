package io.github.alamo3.openblue.svm3d

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The surroundings as the cameras saw them: every pixel's colour put back where its depth says it
 * was, joined to its neighbours so it reads as a surface rather than dots. Car frame (X right,
 * Y forward, Z up), colours linear RGB 0..1.
 */
class PaintedMesh(val positions: FloatArray, val colors: FloatArray, val indices: IntArray) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
}

object PaintedSurface {
    /** Grid step in crop pixels: 2 keeps a point every ~3 cm at 5 m, plenty for a phone screen. */
    private const val STEP = 2
    /** Ignore anything further than this along the ray: depth gets too coarse to be worth drawing. */
    private const val MAX_T = 25f
    /** A pixel whose depth jumps by more than this (in log) to a neighbour sits on an edge: neither side. */
    private const val EDGE = 0.06

    fun build(crops: List<Crop>, vehicles: List<Vehicle>): PaintedMesh {
        class Grid(val crop: Crop, val m: Int, val x: FloatArray, val y: FloatArray, val z: FloatArray, val keep: BooleanArray, val col: FloatArray, val lt: FloatArray)
        val grids = ArrayList<Grid>()
        val ray = DoubleArray(3)
        val toLin = FloatArray(256) { c -> srgbToLinear(c / 255f) }
        for (c in crops) {
            if (!c.depthOk) continue
            val n = c.n
            val m = (n - 1) / STEP
            val x = FloatArray(m * m); val y = FloatArray(m * m); val z = FloatArray(m * m)
            val keep = BooleanArray(m * m); val col = FloatArray(3 * m * m); val lt = FloatArray(m * m)
            val L = SvmCalibration.CAR_LENGTH; val W = SvmCalibration.CAR_WIDTH
            for (gi in 0 until m) for (gj in 0 until m) {
                val i = gi * STEP + 1; val j = gj * STEP + 1
                val k = i * n + j
                val g = gi * m + gj
                if (!c.valid[k]) continue
                val t = c.t[k]
                if (t >= MAX_T) continue
                lt[g] = ln(max(t, 0.05f))
                // Flying pixels: the depth model blends foreground and background across edges.
                var jump = 0.0
                for (kk in intArrayOf(k - 1, k - n, k + 1, k + n)) {
                    jump = max(jump, abs(lt[g] - ln(max(c.t[kk], 0.05f)).toDouble()))
                }
                if (jump > EDGE) continue
                c.ray(i, j, ray)
                // Each direction belongs to the crop that looks most straight at it.
                val az = Math.toDegrees(atan2(ray[0], ray[1]))
                val mine = angle(az, c.view.centerAzimuthDeg)
                if (c.siblingAzimuths.any { angle(az, it) < mine }) continue
                val px = c.camera.x + ray[0] * t; val py = c.camera.y + ray[1] * t; val pz = c.camera.z + ray[2] * t
                // ...and to the camera whose side of the car it's on.
                if (angle(Math.toDegrees(atan2(px, py)), SvmCalibration.SECTOR.getValue(c.camera.name)) > 50) continue
                if (abs(px) < W / 2 + 0.05 && abs(py) < L / 2 + 0.05 && pz < 1.7) continue
                // The floor is drawn separately (stitched, with the gaps painted in); keep what stands up.
                val tg = c.floorT[k]
                if (tg.isFinite() && t > tg * 0.85) continue
                if (pz < 0.06) continue
                x[g] = px.toFloat(); y[g] = py.toFloat(); z[g] = pz.toFloat()
                keep[g] = true
                // Colour: the average of the pixels this grid point stands for.
                var r = 0f; var gg = 0f; var b = 0f; var cnt = 0
                for (di in 0 until STEP) for (dj in 0 until STEP) {
                    val kk = (i + di) * n + (j + dj)
                    if (i + di >= n || j + dj >= n || !c.valid[kk]) continue
                    r += toLin[c.rgb[3 * kk].toInt() and 0xFF]; gg += toLin[c.rgb[3 * kk + 1].toInt() and 0xFF]; b += toLin[c.rgb[3 * kk + 2].toInt() and 0xFF]
                    cnt++
                }
                if (cnt > 0) { col[3 * g] = r / cnt; col[3 * g + 1] = gg / cnt; col[3 * g + 2] = b / cnt }
            }
            grids += Grid(c, m, x, y, z, keep, col, lt)
        }

        // Level patches standing off the floor outside any vehicle aren't real: they're far floor the
        // depth scale bent upwards, or ceiling. Find them as 10 cm columns whose points are all at
        // one height.
        val ext = MAX_T + 3.0; val cell = 0.1; val cn = (2 * ext / cell).toInt()
        val zmin = FloatArray(cn * cn) { Float.MAX_VALUE }; val zmax = FloatArray(cn * cn) { -Float.MAX_VALUE }; val cnt = IntArray(cn * cn)
        fun cellOf(x: Float, y: Float): Int {
            val ci = ((ext - y) / cell).toInt(); val cj = ((x + ext) / cell).toInt()
            return if (ci in 0 until cn && cj in 0 until cn) ci * cn + cj else -1
        }
        for (gd in grids) for (g in gd.keep.indices) if (gd.keep[g]) {
            val q = cellOf(gd.x[g], gd.y[g]); if (q < 0) { gd.keep[g] = false; continue }
            zmin[q] = minOf(zmin[q], gd.z[g]); zmax[q] = maxOf(zmax[q], gd.z[g]); cnt[q]++
        }
        for (gd in grids) for (g in gd.keep.indices) if (gd.keep[g]) {
            val q = cellOf(gd.x[g], gd.y[g])
            val flat = cnt[q] >= 3 && zmax[q] - zmin[q] < 0.12f && zmin[q] > 0.3f
            if (flat && !SurroundBuilder.inAnyVehicle(vehicles, gd.x[g].toDouble(), gd.y[g].toDouble())) gd.keep[g] = false
        }

        // Join neighbours into triangles where nothing jumps between them; drop points left alone.
        val pos = FloatList(); val cols = FloatList(); val idx = IntList()
        for (gd in grids) {
            val m = gd.m
            val vid = IntArray(m * m) { -1 }
            fun vertex(g: Int): Int {
                if (vid[g] < 0) {
                    vid[g] = pos.size / 3
                    pos.add(gd.x[g]); pos.add(gd.y[g]); pos.add(gd.z[g])
                    cols.add(gd.col[3 * g]); cols.add(gd.col[3 * g + 1]); cols.add(gd.col[3 * g + 2])
                }
                return vid[g]
            }
            fun ok(a: Int, b: Int) = abs(gd.lt[a] - gd.lt[b]) < 2.0 * EDGE
            fun tri(a: Int, b: Int, c: Int) {
                if (!gd.keep[a] || !gd.keep[b] || !gd.keep[c]) return
                if (!ok(a, b) || !ok(b, c) || !ok(a, c)) return
                idx.add(vertex(a)); idx.add(vertex(b)); idx.add(vertex(c))
            }
            for (gi in 0 until m - 1) for (gj in 0 until m - 1) {
                val a = gi * m + gj; val b = a + 1; val c = a + m; val d = c + 1
                tri(a, c, b); tri(b, c, d)
            }
        }
        return PaintedMesh(pos.toArray(), cols.toArray(), idx.toArray())
    }

    private fun angle(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private class FloatList {
        private var a = FloatArray(1 shl 16); var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }

    private class IntList {
        private var a = IntArray(1 shl 16); var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
