package io.github.alamo3.openblue.svm3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The pipeline on a made-up world: flat floor with a 2 m wall 3 m to the car's right. Fake
 * "depth model" answers with the true inverse depth (squashed and shifted, as real models are
 * only relative), so the floor scaling and the wall sweep can be checked exactly.
 */
class SurroundBuilderTest {
    private val wallX = 3.0

    /** True distance along a ray from the camera to the floor or the wall, whichever is first. */
    private fun trueT(cam: SvmCamera, d: DoubleArray): Double {
        var t = Double.POSITIVE_INFINITY
        if (d[2] < -1e-6) t = -cam.z / d[2]
        if (d[0] > 1e-6) {
            val tw = (wallX - cam.x) / d[0]
            val z = cam.z + d[2] * tw
            if (tw > 0 && z in 0.0..2.0 && tw < t) t = tw
        }
        return t.coerceAtMost(80.0)
    }

    private fun frame(cam: SvmCamera): Fisheye {
        val w = SvmCalibration.FRAME_WIDTH; val h = SvmCalibration.FRAME_HEIGHT
        // Mid-grey picture with a little texture; the lens mask covers it all.
        return Fisheye(cam, w, h, IntArray(w * h) { i -> val v = 90 + (i * 7919 % 37); (v shl 16) or (v shl 8) or v })
    }

    private inner class TrueDepth(private val crops: () -> List<Crop>) : DepthModel {
        var next = 0
        override fun disparity(rgb: ByteArray, n: Int): FloatArray {
            val c = crops()[next++]
            val ray = DoubleArray(3)
            return FloatArray(n * n) { k ->
                c.ray(k / n, k % n, ray)
                val t = trueT(c.camera, ray)
                val z = t * (ray[0] * c.view.axis[0] + ray[1] * c.view.axis[1] + ray[2] * c.view.axis[2])
                (3.0 / z.coerceAtLeast(0.05) + 0.4).toFloat()
            }
        }
    }

    @Test fun floorScalingRecoversTrueDepth() {
        val frames = SvmCalibration.ALL.map { frame(it) }
        val crops = SurroundBuilder.makeCrops(frames)
        assertTrue("crops ${crops.size}", crops.size >= 8)
        val depth = TrueDepth { crops }
        val ray = DoubleArray(3)
        for (c in crops) {
            SurroundBuilder.applyDepth(c, depth.disparity(c.rgb, c.n))
            assertTrue("${c.camera.name} scaled", c.depthOk)
            // Spot-check: depth on valid pixels within 7 % of the truth (where the truth is near).
            var checked = 0
            for (k in 0 until c.n * c.n step 997) {
                if (!c.valid[k]) continue
                c.ray(k / c.n, k % c.n, ray)
                val tt = trueT(c.camera, ray)
                if (tt > 15) continue
                assertEquals("${c.camera.name} pixel $k", tt, c.t[k].toDouble(), tt * 0.07 + 0.05)
                checked++
            }
            assertTrue(checked > 5)
        }
    }

    @Test fun wallIsFoundWhereItIs() {
        val frames = SvmCalibration.ALL.map { frame(it) }
        val crops = SurroundBuilder.makeCrops(frames)
        val depth = TrueDepth { crops }
        crops.forEach { SurroundBuilder.applyDepth(it, depth.disparity(it.rgb, it.n)) }
        val scene = SurroundBuilder.assemble(frames, crops, emptyMap())
        assertTrue("walls ${scene.walls.size}", scene.walls.isNotEmpty())
        // Points on the right-hand side should sit on x = 3 and be about 2 m tall.
        val near = scene.walls.flatMap { w -> w.xs.indices.map { Triple(w.xs[it], w.ys[it], w.heights[it]) } }
            .filter { it.first > 1.5 && abs(it.second) < 3 }
        assertTrue("wall points at the side: ${near.size}", near.size > 10)
        for ((x, _, h) in near) {
            assertEquals(wallX, x, 0.35)
            assertTrue("height $h", h > 1.4)
        }
        // The floor picture exists and isn't black inside the bowl.
        val n = SurroundBuilder.GROUND_N; val res = 2 * SurroundBuilder.GROUND_EXT / n
        assertEquals(n * n, scene.ground.size)
        val inside = (0 until n * n).filter { k -> hypot(-SurroundBuilder.GROUND_EXT + (k % n + 0.5) * res, SurroundBuilder.GROUND_EXT - (k / n + 0.5) * res) < 8.0 }
        assertTrue(inside.count { scene.ground[it] != 0 } > inside.size * 0.8)
    }

    @Test fun paintedSurfaceLiesOnTheWall() {
        val frames = SvmCalibration.ALL.map { frame(it) }
        val crops = SurroundBuilder.makeCrops(frames)
        val depth = TrueDepth { crops }
        crops.forEach { SurroundBuilder.applyDepth(it, depth.disparity(it.rgb, it.n)) }
        val scene = SurroundBuilder.assemble(frames, crops, emptyMap())
        val mesh = scene.painted
        assertTrue("painted surface", mesh != null && mesh.triangleCount > 1000)
        mesh!!
        val p = mesh.positions
        var onWall = 0
        for (v in 0 until mesh.vertexCount) {
            val x = p[3 * v]; val z = p[3 * v + 2]
            // Nothing from the floor (that's drawn separately) and nothing inside our own car.
            assertTrue("vertex $v at z=$z", z > 0.05f)
            assertTrue("vertex $v inside the car", !(abs(x) < 0.9f && abs(p[3 * v + 1]) < 2.3f && z < 1.6f))
            if (x > 1.5f) { assertEquals(wallX, x.toDouble(), 0.25); onWall++ }
        }
        assertTrue("points on the wall: $onWall", onWall > 500)
        for (i in mesh.indices) assertTrue(i in 0 until mesh.vertexCount)
        // Colours are linear 0..1.
        assertTrue(mesh.colors.all { it in 0f..1f })
        val json = glbJson(SceneGlb.write(scene, byteArrayOf(1, 2, 3, 4)))
        assertTrue(json.contains("\"painted\""))
        assertTrue(glbJson(SceneGlb.write(scene, byteArrayOf(1, 2, 3, 4), backdropImage = byteArrayOf(5, 6, 7))).contains("\"backdrop\""))
    }

    @Test fun floorFillPaintsOnlyWhatWasNotSeen() {
        val n = 100; val ext = 12.0; val res = 2 * ext / n
        // Seen: a disc of radius 4 m round the car, painted red. The "model" paints everything blue.
        val seen = BooleanArray(n * n) { k ->
            val gx = -ext + (k % n + 0.5) * res; val gy = ext - (k / n + 0.5) * res
            hypot(gx, gy) < 4.0
        }
        val photo = IntArray(n * n) { if (seen[it]) 0xC00000 else 0 }
        val scene = SurroundScene(n, ext, IntArray(n * n), emptyList(), emptyList(), seen, photo)
        var calls = 0
        val blue = object : Inpainter {
            override fun inpaint(rgb: ByteArray, hole: BooleanArray): ByteArray {
                calls++
                assertEquals(Inpainter.SIZE * Inpainter.SIZE * 3, rgb.size)
                assertTrue(hole.any { it } && hole.any { !it })
                return ByteArray(rgb.size) { if (it % 3 == 2) 200.toByte() else 0 }
            }
        }
        val out = FloorFill.fill(scene, blue)
        assertTrue("passes $calls", calls in 1..5)
        fun at(x: Double, y: Double) = out[((ext - y) / res).toInt() * n + ((x + ext) / res).toInt()]
        // Well inside what was seen: the photo. Out at 7 m (not seen, before the edge fade): the painting.
        assertEquals(0xC0, (at(0.0, 2.5) shr 16) and 0xFF)
        assertEquals(0, at(0.0, 2.5) and 0xFF)
        val far = at(7.0, 0.0)
        assertEquals(0, (far shr 16) and 0xFF)
        assertEquals(200, far and 0xFF)
    }

    @Test fun distanceTransform() {
        val n = 9
        val m = BooleanArray(n * n).also { it[4 * n + 4] = true }
        val d = FloorFill.distanceTo(m, n)
        assertEquals(0f, d[4 * n + 4], 0f)
        assertEquals(4f, d[4 * n + 8], 0.01f)
        assertEquals(4 * kotlin.math.sqrt(2f), d[0], 0.01f)
    }

    @Test fun backdropDomeFacesInwardAndMeetsTheFloor() {
        val (pos, uv, idx) = SceneGlb.dome()
        assertEquals(0, idx.size % 3)
        assertTrue(uv.all { it in 0f..1f })
        // glTF axes: Y up. Every triangle faces the eye point (0, EYE_Z, 0).
        for (t in 0 until idx.size / 3 step 37) {
            fun p(k: Int, c: Int) = pos[3 * idx[3 * t + k] + c].toDouble()
            val ux = p(1, 0) - p(0, 0); val uy = p(1, 1) - p(0, 1); val uz = p(1, 2) - p(0, 2)
            val vx = p(2, 0) - p(0, 0); val vy = p(2, 1) - p(0, 1); val vz = p(2, 2) - p(0, 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz < 1e-12) continue  // degenerate at the top
            assertTrue("triangle $t", nx * -p(0, 0) + ny * (Backdrop.EYE_Z - p(0, 1)) + nz * -p(0, 2) > 0)
        }
        // The lowest ring sits on the floor.
        var lowest = Double.MAX_VALUE
        for (v in 0 until pos.size / 3) lowest = minOf(lowest, pos[3 * v + 1].toDouble())
        assertEquals(0.0, lowest, 1e-3)
    }

    @Test fun backdropSeesTheHorizonButNotTheSky() {
        val frames = SvmCalibration.ALL.map { frame(it) }
        val seen = BooleanArray(Backdrop.WIDTH * Backdrop.HEIGHT)
        Backdrop.stitch(frames, seen)
        fun row(e: Double) = Backdrop.rowOfElevation(e).toInt()
        val w = Backdrop.WIDTH
        val horizon = (0 until w).count { seen[row(2.0) * w + it] }
        val zenith = (0 until w).count { seen[row(78.0) * w + it] }
        assertTrue("horizon seen in $horizon of $w columns", horizon > w * 0.9)
        assertTrue("near the top only $zenith", zenith < w / 2)
        // Filled, nothing is left black: the sky carries on up.
        val bd = Backdrop.build(frames)
        val top = row(78.0)
        assertTrue((0 until w).all { bd.rgb[top * w + it] != 0 })
    }

    private fun glbJson(glb: ByteArray): String {
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        return String(glb, 20, b.getInt(12))
    }

    @Test fun glbIsWellFormed() {
        val scene = SurroundScene(
            4, 12.0, IntArray(16) { 0x808080 },
            listOf(Wall(doubleArrayOf(3.0, 3.0, 3.0), doubleArrayOf(-1.0, 0.0, 1.0), doubleArrayOf(2.0, 2.0, 2.0), 0x777777)),
            listOf(Vehicle(-3.0, 0.0, 0.0, 4.6, 1.6, "car")),
        )
        val glb = SceneGlb.write(scene, byteArrayOf(1, 2, 3, 4, 5))
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(glb.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals(0x4E4F534A, b.getInt(16))
        val json = String(glb, 20, jsonLen)
        assertTrue(json.contains("\"KHR_materials_unlit\""))
        assertTrue(json.contains("\"walls\""))
        val binLen = b.getInt(20 + jsonLen)
        assertEquals(0x004E4942, b.getInt(24 + jsonLen))
        assertEquals(glb.size, 28 + jsonLen + binLen)
    }

    @Test fun lensRoundTrip() {
        val cam = SvmCalibration.LEFT
        val uv = DoubleArray(2)
        assertTrue(cam.project(-2.5, 0.5, 0.0, uv))
        assertTrue("inside the frame: ${uv.toList()}", uv[0] in 0.0..960.0 && uv[1] in 0.0..720.0)
        // A point straight ahead of the front camera lands near the image centre.
        val f = SvmCalibration.FRONT
        assertTrue(f.project(f.x + 10 * kotlin.math.sin(f.yaw), f.y + 10 * kotlin.math.cos(f.yaw), f.z, uv))
        assertTrue(hypot(uv[0] - f.cx, uv[1] - f.cy) < 15)
    }
}
