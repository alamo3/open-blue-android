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
        // The floor picture exists and isn't all black.
        assertEquals(SurroundBuilder.GROUND_N * SurroundBuilder.GROUND_N, scene.ground.size)
        assertTrue(scene.ground.count { it != 0 } > scene.ground.size / 2)
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
