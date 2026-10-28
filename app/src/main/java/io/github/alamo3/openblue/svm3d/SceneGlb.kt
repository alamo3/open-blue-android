package io.github.alamo3.openblue.svm3d

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.min

/**
 * Writes the surroundings (floor and walls) as a binary glTF, ready for Filament's glTF loader.
 * Vehicles aren't in it: the renderer places copies of the car model for those.
 *
 * glTF axes are Y-up; we map the car frame (X right, Y forward, Z up) to glTF (-X, Z, Y), which
 * matches the car model (+X = car's left, +Z = front).
 */
object SceneGlb {
    fun write(scene: SurroundScene, groundImage: ByteArray, groundMime: String = "image/jpeg"): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder()
        val accessors = StringBuilder()
        var nViews = 0; var nAcc = 0

        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun addView(bytes: ByteArray, target: Int?): Int {
            align()
            val off = bin.size()
            bin.write(bytes)
            if (nViews > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$off,\"byteLength\":${bytes.size}")
            if (target != null) views.append(",\"target\":$target")
            views.append('}')
            return nViews++
        }
        fun floats(v: FloatArray) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()
        fun ints(v: IntArray) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(v) }.array()
        fun addAccessor(view: Int, componentType: Int, count: Int, type: String, minMax: Pair<FloatArray, FloatArray>? = null): Int {
            if (nAcc > 0) accessors.append(',')
            accessors.append("{\"bufferView\":$view,\"componentType\":$componentType,\"count\":$count,\"type\":\"$type\"")
            if (minMax != null) {
                accessors.append(",\"min\":[${minMax.first.joinToString(",")}],\"max\":[${minMax.second.joinToString(",")}]")
            }
            accessors.append('}')
            return nAcc++
        }
        fun bounds(p: FloatArray): Pair<FloatArray, FloatArray> {
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in p.indices) { val a = i % 3; lo[a] = min(lo[a], p[i]); hi[a] = maxOf(hi[a], p[i]) }
            return lo to hi
        }

        // Floor quad, a hair below zero so the car's own shadow sits on top.
        val e = scene.groundExtent.toFloat()
        val y0 = -0.005f
        // car-frame corners (-e,+e), (+e,+e), (+e,-e), (-e,-e) -> glTF (-X, Z, Y)
        val gp = floatArrayOf(e, y0, e, -e, y0, e, -e, y0, -e, e, y0, -e)
        val guv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val gidx = intArrayOf(0, 2, 1, 0, 3, 2)
        val gpA = addAccessor(addView(floats(gp), 34962), 5126, 4, "VEC3", bounds(gp))
        val guvA = addAccessor(addView(floats(guv), 34962), 5126, 4, "VEC2")
        val gnA = addAccessor(addView(floats(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)), 34962), 5126, 4, "VEC3")
        val giA = addAccessor(addView(ints(gidx), 34963), 5125, 6, "SCALAR")
        val imgView = addView(groundImage, null)

        // Walls: one vertical quad per step along each run, coloured lightly by what was seen.
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Float>(); val idx = ArrayList<Int>()
        for (w in scene.walls) {
            val m = w.xs.size
            if (m < 2) continue
            val hs = DoubleArray(m) { i ->
                var s = 0.0
                for (o in -2..2) s += w.heights[(i + o).coerceIn(0, m - 1)]
                s / 5
            }
            val r = ((w.color shr 16) and 0xFF) / 255f; val g = ((w.color shr 8) and 0xFF) / 255f; val b = (w.color and 0xFF) / 255f
            fun tint(c: Float) = 0.78f * 0.65f + min(1f, c * 1.4f) * 0.35f
            for (i in 0 until m - 1) {
                val x0 = w.xs[i]; val yy0 = w.ys[i]; val x1 = w.xs[i + 1]; val yy1 = w.ys[i + 1]
                val len = hypot(x1 - x0, yy1 - yy0)
                if (len < 1e-3 || len > 1.5) continue
                // normal in car frame (perpendicular, horizontal), then to glTF
                val nx = -(yy1 - yy0) / len; val ny = (x1 - x0) / len
                val base = pos.size / 3
                val pts = listOf(
                    Triple(x0, yy0, 0.0), Triple(x0, yy0, hs[i]), Triple(x1, yy1, hs[i + 1]), Triple(x1, yy1, 0.0),
                )
                for ((x, y, z) in pts) {
                    pos += (-x).toFloat(); pos += z.toFloat(); pos += y.toFloat()
                    nrm += (-nx).toFloat(); nrm += 0f; nrm += ny.toFloat()
                    col += tint(r); col += tint(g); col += tint(b)
                }
                idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
            }
        }
        val hasWalls = idx.isNotEmpty()
        var wallPrim = ""
        if (hasWalls) {
            val p = pos.toFloatArray()
            val pA = addAccessor(addView(floats(p), 34962), 5126, p.size / 3, "VEC3", bounds(p))
            val nA = addAccessor(addView(floats(nrm.toFloatArray()), 34962), 5126, p.size / 3, "VEC3")
            val cA = addAccessor(addView(floats(col.toFloatArray()), 34962), 5126, p.size / 3, "VEC3")
            val iA = addAccessor(addView(ints(idx.toIntArray()), 34963), 5125, idx.size, "SCALAR")
            wallPrim = "{\"attributes\":{\"POSITION\":$pA,\"NORMAL\":$nA,\"COLOR_0\":$cA},\"indices\":$iA,\"material\":1}"
        }
        align()

        val meshes = StringBuilder("[{\"name\":\"floor\",\"primitives\":[{\"attributes\":{\"POSITION\":$gpA,\"NORMAL\":$gnA,\"TEXCOORD_0\":$guvA},\"indices\":$giA,\"material\":0}]}")
        if (hasWalls) meshes.append(",{\"name\":\"walls\",\"primitives\":[$wallPrim]}")
        meshes.append(']')
        val nodes = if (hasWalls) "[{\"mesh\":0,\"name\":\"floor\"},{\"mesh\":1,\"name\":\"walls\"}]" else "[{\"mesh\":0,\"name\":\"floor\"}]"
        val sceneNodes = if (hasWalls) "[0,1]" else "[0]"
        val json = """
            {"asset":{"version":"2.0","generator":"Open Blue"},
             "extensionsUsed":["KHR_materials_unlit"],
             "scene":0,"scenes":[{"nodes":$sceneNodes}],
             "nodes":$nodes,
             "meshes":$meshes,
             "materials":[
               {"name":"floor","doubleSided":true,"extensions":{"KHR_materials_unlit":{}},
                "pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}},
               {"name":"wall","doubleSided":true,
                "pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":0.85}}
             ],
             "textures":[{"sampler":0,"source":0}],
             "samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":33071,"wrapT":33071}],
             "images":[{"bufferView":$imgView,"mimeType":"$groundMime"}],
             "accessors":[$accessors],
             "bufferViews":[$views],
             "buffers":[{"byteLength":${bin.size()}}]}
        """.trimIndent().replace("\n", "")
        val jsonBytes = json.toByteArray(Charsets.UTF_8).let { j ->
            val pad = (4 - j.size % 4) % 4
            j + ByteArray(pad) { ' '.code.toByte() }
        }
        val binBytes = bin.toByteArray()
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jsonBytes.size); out.putInt(0x4E4F534A); out.put(jsonBytes)
        out.putInt(binBytes.size); out.putInt(0x004E4942); out.put(binBytes)
        return out.array()
    }
}
