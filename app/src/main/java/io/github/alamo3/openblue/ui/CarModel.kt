package io.github.alamo3.openblue.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/** What the 3D car should show. Doors/trunk/frunk open, plus cabin climate and charging. */
data class CarPose(
    val frontLeft: Boolean = false,
    val frontRight: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
    val frunk: Boolean = false,
    val trunk: Boolean = false,
)

fun CarStatus?.toPose(): CarPose {
    if (this == null) return CarPose()
    return CarPose(
        frontLeft = "front left" in openDoors,
        frontRight = "front right" in openDoors,
        rearLeft = "rear left" in openDoors,
        rearRight = "rear right" in openDoors,
        frunk = frunkOpen,
        trunk = trunkOpen,
    )
}

/**
 * Hinged parts of the rigged model (tools/model/rig.py). Each node's origin sits on its hinge,
 * so opening is a rotation about the node's own axis. glTF axes: +X = car's left (driver side),
 * +Y = up, +Z = front.
 */
private enum class Part(val node: String, val axis: FloatArray, val openDegrees: Float) {
    DOOR_FL("door_fl", floatArrayOf(0f, 1f, 0f), -45f),
    DOOR_RL("door_rl", floatArrayOf(0f, 1f, 0f), -45f),
    DOOR_FR("door_fr", floatArrayOf(0f, 1f, 0f), 45f),
    DOOR_RR("door_rr", floatArrayOf(0f, 1f, 0f), 45f),
    HOOD("hood", floatArrayOf(1f, 0f, 0f), -38f),
    TAILGATE("tailgate", floatArrayOf(1f, 0f, 0f), 60f);

    fun isOpen(p: CarPose) = when (this) {
        DOOR_FL -> p.frontLeft
        DOOR_RL -> p.rearLeft
        DOOR_FR -> p.frontRight
        DOOR_RR -> p.rearRight
        HOOD -> p.frunk
        TAILGATE -> p.trunk
    }
}

/** Owns the Filament viewer for one TextureView and animates the hinged parts toward the pose. */
private class CarRenderer(context: Context, val view: TextureView) : Choreographer.FrameCallback {
    private val viewer: ModelViewer
    private val choreographer = Choreographer.getInstance()
    private val transforms = mutableMapOf<Part, Pair<Int, FloatArray>>() // transform instance, rest pose
    private val current = FloatArray(Part.entries.size) // 0 = closed, 1 = open
    private var lastFrame = 0L
    private var running = false
    var pose = CarPose()

    init {
        val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
        // Front three-quarter view from the driver's side, slightly above.
        val manipulator = Manipulator.Builder()
            .targetPosition(0f, -0.12f, -4f)
            .orbitHomePosition(2.35f, 0.75f, -1.55f)
            .upVector(0f, 1f, 0f)
            .zoomSpeed(0.02f)
            .orbitSpeed(0.006f, 0.006f)
            .build(Manipulator.Mode.ORBIT)
        viewer = ModelViewer(view, uiHelper = uiHelper, manipulator = manipulator)
        view.isOpaque = false

        // Transparent background so the app's own background and glow show through.
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }
        viewer.scene.skybox = null

        // Soft even ambient light plus an angled key light so the sides aren't black.
        viewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.95f, 0.96f, 1.0f))
            .intensity(32_000f)
            .build(viewer.engine)
        val lm = viewer.engine.lightManager
        lm.getInstance(viewer.light).let { li ->
            lm.setDirection(li, 0.45f, -1f, -0.55f)
            lm.setIntensity(li, 85_000f)
        }

        val bytes = context.assets.open("models/ioniq5.glb").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.rewind() }
        viewer.loadModelGlb(buffer)
        viewer.transformToUnitCube()

        val asset = viewer.asset
        if (asset != null) {
            val tm = viewer.engine.transformManager
            for (part in Part.entries) {
                val entity = asset.getFirstEntityByName(part.node)
                if (entity == 0) continue
                val inst = tm.getInstance(entity)
                val rest = FloatArray(16)
                tm.getTransform(inst, rest)
                transforms[part] = inst to rest
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        view.setOnTouchListener { v, e ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            viewer.onTouchEvent(e)
            true
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = start()
            // ModelViewer tears its engine down on detach; stop rendering first.
            override fun onViewDetachedFromWindow(v: android.view.View) = stop()
        })
        if (view.isAttachedToWindow) start()
    }

    private fun start() {
        if (running) return
        running = true
        choreographer.postFrameCallback(this)
    }

    private fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = frameTimeNanos
        animateParts(dt)
        viewer.render(frameTimeNanos)
    }

    private val rotation = FloatArray(16)
    private val out = FloatArray(16)

    private fun animateParts(dt: Float) {
        val tm = viewer.engine.transformManager
        for (part in Part.entries) {
            val (inst, rest) = transforms[part] ?: continue
            val target = if (part.isOpen(pose)) 1f else 0f
            val i = part.ordinal
            val prev = current[i]
            // Ease toward the target (~0.4 s), like a door swinging.
            current[i] = if (dt == 0f) target else prev + (target - prev) * (1f - kotlin.math.exp(-dt * 7f))
            if (kotlin.math.abs(current[i] - prev) < 1e-4f && dt != 0f) continue
            val t = current[i]
            val eased = t * t * (3 - 2 * t)
            Matrix.setRotateM(rotation, 0, part.openDegrees * eased, part.axis[0], part.axis[1], part.axis[2])
            Matrix.multiplyMM(out, 0, rest, 0, rotation, 0)
            tm.setTransform(inst, out)
        }
    }

    companion object {
        init {
            Utils.init()
        }
    }
}

/**
 * The car in 3D, with doors, frunk and tailgate following [pose], a warm or cool glow while
 * the cabin is being conditioned, and a green pulse while charging. Drag to look around.
 * Falls back to [fallback] where 3D can't run (previews, screenshot tests).
 */
@Composable
fun Car3D(
    pose: CarPose,
    climateTint: Color?,
    charging: Boolean,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val app = AppTheme.colors
    val glow by animateColorAsState(climateTint ?: Color.Transparent, tween(700), label = "glow")
    val pulse = rememberInfiniteTransition(label = "charge")
    val chargeAlpha by pulse.animateFloat(0.12f, 0.45f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "chargeAlpha")
    val shadow = Color.Black.copy(alpha = if (androidx.compose.foundation.isSystemInDarkTheme()) 0.55f else 0.16f)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.56f)
            if (glow.alpha > 0f) {
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.35f), Color.Transparent), c, size.width * 0.5f), size.width * 0.5f, c)
            }
            if (charging) {
                drawCircle(
                    Brush.radialGradient(listOf(app.charge.copy(alpha = chargeAlpha), Color.Transparent), c, size.width * 0.42f),
                    size.width * 0.42f, c,
                )
            }
            // Ground shadow
            val sw = size.width * 0.78f
            val sh = size.height * 0.16f
            drawOval(
                Brush.radialGradient(listOf(shadow, Color.Transparent), Offset(size.width / 2, size.height * 0.74f), sw / 2),
                topLeft = Offset((size.width - sw) / 2, size.height * 0.74f - sh / 2),
                size = Size(sw, sh),
            )
        }
        var failed by remember { mutableStateOf(false) }
        if (LocalInspectionMode.current || failed) {
            fallback()
        } else {
            val currentPose by rememberUpdatedState(pose)
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        val r = runCatching { CarRenderer(ctx, tv) }
                            .onFailure { android.util.Log.e("OpenBlue", "3D car unavailable", it) }
                            .getOrNull()
                        if (r == null) failed = true
                        tv.tag = r
                        r?.pose = currentPose
                    }
                },
                update = { tv -> (tv.tag as? CarRenderer)?.pose = currentPose },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
