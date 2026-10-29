package io.github.alamo3.openblue.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.IBLPrefilterContext
import com.google.android.filament.utils.Utils
import io.github.alamo3.openblue.MainViewModel
import io.github.alamo3.openblue.svm3d.Vehicle
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The 3D surroundings built from the surround-view photos ([envGlb]) with our car in the middle.
 * Two looks: "painted" (everything as the cameras saw it, put back at its estimated depth) or
 * "simple" (traced walls and a grey stand-in car for each neighbouring vehicle). Drag to orbit,
 * pinch to zoom, double-tap to reset.
 */
private class SurroundRenderer(
    context: Context,
    private val textureView: TextureView,
    envGlb: ByteArray,
    private val vehicles: List<Vehicle>,
    body: CarBody,
    private val paint: CarPaint,
) : Choreographer.FrameCallback {
    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine, true)
    private val cars: FilamentAsset
    private val instances: Array<FilamentInstance?>
    private val env: FilamentAsset
    private val sun = EntityManager.get().create()
    private var indirectLight: IndirectLight? = null
    private var reflections: Texture? = null
    private val ready = IntArray(256)
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var destroyed = false
    private var lastFrame = 0L
    private var placed = false
    private var painted = false

    // Orbit camera, in the car frame (X right, Y forward, Z up); azimuth 0 = in front looking back.
    private var azimuth = -35.0
    private var elevation = 32.0
    private var distance = 11.0
    private var spin = 0.0
    private var touching = false
    private var aspect = 1.0

    init {
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(textureView)
        view.scene = scene
        view.camera = camera
        camera.setExposure(16f, 1f / 125f, 100f)
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = floatArrayOf(0.035f, 0.04f, 0.05f, 1f)
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = true }

        indirectLight = studioLight(context) ?: IndirectLight.Builder().irradiance(1, floatArrayOf(0.95f, 0.96f, 1f)).intensity(32_000f).build(engine)
        scene.indirectLight = indirectLight
        val (r, g, b) = Colors.cct(6_500f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(r, g, b).intensity(55_000f)
            .direction(0.45f, -1f, -0.55f).castShadows(true)
            // A finer map and a little more bias: the scene is far bigger than on the home screen,
            // and a coarse shadow map draws the car's own shadow as rings over its roof and bonnet.
            .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 2048; normalBias = 1.5f; constantBias = 0.002f })
            .build(engine, sun)
        scene.addEntity(sun)

        val carBytes = context.assets.open(body.glb).use { it.readBytes() }
        instances = arrayOfNulls(1 + vehicles.size)
        cars = assetLoader.createInstancedAsset(ByteBuffer.allocateDirect(carBytes.size).put(carBytes).also { it.rewind() }, instances)
            ?: error("Couldn't read the car model")
        resourceLoader.asyncBeginLoad(cars)
        cars.releaseSourceData()

        env = assetLoader.createAsset(ByteBuffer.allocateDirect(envGlb.size).put(envGlb).also { it.rewind() })
            ?: error("Couldn't read the surroundings")
        resourceLoader.loadResources(env)
        env.releaseSourceData()
        scene.addEntities(env.entities)
        // The surroundings are photos with their own light and shade: they neither cast nor take
        // shadows (the car brings its own soft shadow), which also keeps the shadow map on the car.
        for (e in env.entities) {
            val ri = engine.renderableManager.getInstance(e)
            if (ri != 0) { engine.renderableManager.setCastShadows(ri, false); engine.renderableManager.setReceiveShadows(ri, false) }
        }
        applyLook()

        setUpTouch(context)
        textureView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = start()
            override fun onViewDetachedFromWindow(v: android.view.View) = destroy()
        })
        if (textureView.isAttachedToWindow) start()
    }

    private val maxDistance = 22.0

    val hasPainted: Boolean get() = env.getEntitiesByName("painted").isNotEmpty()

    /** Painted surroundings, or traced walls with stand-in cars. */
    fun showPainted(on: Boolean) {
        if (destroyed) return
        painted = on && hasPainted
        applyLook()
    }

    private fun applyLook() {
        val rm = engine.renderableManager
        fun show(entities: IntArray, on: Boolean) {
            for (e in entities) {
                val ri = rm.getInstance(e)
                if (ri != 0) rm.setLayerMask(ri, 0xFF, if (on) 0x01 else 0x00)
            }
        }
        show(env.getEntitiesByName("painted"), painted)
        show(env.getEntitiesByName("backdrop"), painted)
        show(env.getEntitiesByName("walls"), !painted)
        if (placed) for (k in 1 until instances.size) instances[k]?.let { show(it.entities, !painted) }
    }

    /** Puts the stand-in cars where the vehicles were found, in grey, without charging cables. */
    private fun placeCars() {
        if (placed) return
        placed = true
        val tm = engine.transformManager
        val rm = engine.renderableManager
        for (e in cars.getEntitiesByName("charge_cable")) {
            val ri = rm.getInstance(e)
            if (ri != 0) rm.setLayerMask(ri, 0xFF, 0x00)
        }
        for (e in cars.getEntitiesByName("ground_shadow")) {
            val ri = rm.getInstance(e)
            if (ri != 0) { rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false) }
        }
        // Our car in its colour; the copies stay grey.
        instances[0]?.materialInstances?.forEach { mi ->
            if (mi.name == "paint") runCatching { mi.setParameter("baseColorFactor", Colors.RgbaType.LINEAR, paint.rgb[0], paint.rgb[1], paint.rgb[2], 1f) }
        }
        val m = FloatArray(16)
        vehicles.forEachIndexed { k, v ->
            val inst = instances[k + 1] ?: return@forEachIndexed
            // Car frame -> glTF: (x, y) -> (-x, y as Z); heading h turns the nose from +Z by -h about Y.
            Matrix.setRotateM(m, 0, (-v.heading).toFloat(), 0f, 1f, 0f)
            m[12] = (-v.x).toFloat(); m[13] = 0f; m[14] = v.y.toFloat()
            tm.setTransform(tm.getInstance(inst.root), m)
            // Grey every part of this copy (each copy has its own material instances).
            for (e in inst.entities) {
                val ri = rm.getInstance(e)
                if (ri == 0) continue
                for (p in 0 until rm.getPrimitiveCount(ri)) {
                    val mi = rm.getMaterialInstanceAt(ri, p)
                    runCatching { mi.setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 0.42f, 0.44f, 0.48f, 1f) }
                    runCatching { mi.setParameter("metallicFactor", 0f) }
                    runCatching { mi.setParameter("roughnessFactor", 0.55f) }
                }
            }
        }
        applyLook()
    }

    private fun setUpTouch(context: Context) {
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true.also { spin = 0.0 }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val degPerPx = 220.0 / textureView.width.coerceAtLeast(1)
                azimuth += dx * degPerPx
                elevation = (elevation - dy * degPerPx * 0.6).coerceIn(8.0, 85.0)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                spin = -vx * 220.0 / textureView.width.coerceAtLeast(1) * 0.35
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                azimuth = -35.0; elevation = 32.0; distance = 11.0
                return true
            }
        })
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                distance = (distance / d.scaleFactor).coerceIn(5.0, maxDistance)
                return true
            }
        })
        @SuppressLint("ClickableViewAccessibility")
        textureView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; v.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            pinch.onTouchEvent(e)
            gestures.onTouchEvent(e)
            true
        }
    }

    private fun start() {
        if (running || destroyed) return
        running = true
        lastFrame = 0L
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 0.0 else ((frameTimeNanos - lastFrame) / 1e9).coerceAtMost(0.1)
        lastFrame = frameTimeNanos
        resourceLoader.asyncUpdateLoad()
        while (true) {
            val n = cars.popRenderables(ready)
            if (n == 0) break
            scene.addEntities(ready.copyOf(n))
        }
        if (resourceLoader.asyncGetLoadProgress() >= 1f) placeCars()
        if (!touching && abs(spin) > 0.5) {
            azimuth += spin * dt
            spin *= exp(-dt * 3.0)
        }
        val a = Math.toRadians(azimuth); val e = Math.toRadians(elevation)
        val cx = distance * cos(e) * sin(a); val cy = distance * cos(e) * cos(a); val cz = distance * sin(e)
        // car frame -> glTF (-x, z, y); look at a point just above the car's middle
        camera.lookAt(-cx, cz + 0.4, cy, 0.0, 0.5, 0.0, 0.0, 1.0, 0.0)
        camera.setProjection(42.0, aspect, 0.1, 200.0, Camera.Fov.VERTICAL)
        val chain = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun destroy() {
        if (destroyed) return
        running = false
        destroyed = true
        choreographer.removeFrameCallback(this)
        uiHelper.detach()
        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()
        scene.removeEntities(cars.entities)
        scene.removeEntities(env.entities)
        assetLoader.destroyAsset(cars)
        assetLoader.destroyAsset(env)
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        resourceLoader.destroy()
        indirectLight?.let { engine.destroyIndirectLight(it) }
        reflections?.let { engine.destroyTexture(it) }
        engine.destroyEntity(sun)
        EntityManager.get().destroy(sun)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            textureView.display?.let { displayHelper.attach(renderer, it) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            view.viewport = Viewport(0, 0, width, height)
            aspect = width.toDouble() / height.coerceAtLeast(1)
        }
    }

    private fun studioLight(context: Context): IndirectLight? = runCatching {
        val hdr = context.assets.open("envs/studio.hdr").use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(hdr.size).put(hdr).also { it.rewind() }
        val equirect = HDRLoader.createTexture(engine, buf) ?: return@runCatching null
        val ibl = IBLPrefilterContext(engine)
        val toCube = IBLPrefilterContext.EquirectangularToCubemap(ibl)
        val filter = IBLPrefilterContext.SpecularFilter(ibl)
        val cube = toCube.run(equirect)
        val filtered = filter.run(cube)
        engine.destroyTexture(equirect); engine.destroyTexture(cube)
        toCube.destroy(); filter.destroy(); ibl.destroy()
        reflections = filtered
        IndirectLight.Builder().reflections(filtered).intensity(24_000f).build(engine)
    }.getOrNull()

    companion object {
        init { Utils.init() }
    }
}

/** The orbitable 3D surroundings, or a note if 3D can't run here. */
@Composable
fun Surround3DView(
    envGlb: ByteArray,
    vehicles: List<Vehicle>,
    painted: Boolean,
    modifier: Modifier = Modifier,
    body: CarBody = CarBody.IONIQ5,
    paint: CarPaint = CarPaint.WHITE,
    onHasPainted: (Boolean) -> Unit = {},
) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Box(modifier.background(Color(0xFF0A0B0D)), contentAlignment = Alignment.Center) {
            Text("3D isn't available on this device.", color = Color.White)
        }
        return
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                val r = runCatching { SurroundRenderer(ctx, tv, envGlb, vehicles, body, paint) }
                    .onFailure { android.util.Log.e("OpenBlue", "3D surroundings unavailable", it) }
                    .getOrNull()
                if (r == null) failed = true
                tv.tag = r
                onHasPainted(r?.hasPainted == true)
            }
        },
        update = { tv -> (tv.tag as? SurroundRenderer)?.showPainted(painted) },
        modifier = modifier,
    )
}

/** Builds (or loads) the 3D view of the selected surround-view photos and shows it. */
@Composable
fun Surround3DScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = vm.surround
    LaunchedEffect(Unit) { vm.prepareSurround3d() }
    // Follow a World Labs generation while this screen is open (it runs in the background).
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshWorldJob()
            kotlinx.coroutines.delay(3_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("3D view", onBack)
        val scene = state.scene
        when {
            scene != null -> Box(Modifier.fillMaxSize()) {
                var painted by remember { mutableStateOf(true) }
                var canPaint by remember { mutableStateOf(false) }
                Surround3DView(scene.glb, scene.vehicles, painted, Modifier.fillMaxSize(), body = vm.carBody, paint = vm.carPaint) { canPaint = it }
                var worldDialog by remember { mutableStateOf(false) }
                if (vm.canMakeWorld) {
                    val job = vm.worldJob
                    Text(
                        when {
                            job?.status == "done" -> "AI world ✓"
                            job?.running == true -> "AI world…"
                            else -> "AI world"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)).pressable { worldDialog = true }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (worldDialog) WorldDialog(vm) { worldDialog = false }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    if (canPaint) {
                        Row(
                            Modifier.padding(bottom = 10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(3.dp),
                        ) {
                            for ((label, on) in listOf("Painted" to true, "Simple" to false)) {
                                val sel = painted == on
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (sel) Color.Black else Color.White,
                                    modifier = Modifier.clip(CircleShape).background(if (sel) Color.White else Color.Transparent)
                                        .pressable { painted = on }.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Text(
                        if (canPaint && painted) {
                            "Built on your phone from the car's photos: each pixel nearby put back at its estimated depth, the far surroundings on a backdrop. What the cameras couldn't see is painted in by AI. Drag to look around."
                        } else {
                            "Built from the car's photos: grey cars are stand-ins for vehicles it spotted, walls are traced from estimated depth. Drag to look around."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            state.needsDownload -> Column(Modifier.padding(20.dp)) {
                Text("One-time download", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The 3D view uses AI models that run on your phone: one estimates depth, one spots cars, one paints in the floor the cameras couldn't see. " +
                        "That's about ${(state.downloadBytes.takeIf { it > 0 } ?: io.github.alamo3.openblue.svm3d.ModelStore.TOTAL_BYTES) / 1_000_000} MB to download once, from GitHub. Best on Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp).clip(CircleShape).background(scheme.primary)
                        .pressable { vm.downloadSurroundModels() }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { Text("Download and build", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary) }
            }
            else -> Column(Modifier.padding(20.dp)) {
                Text(state.stage ?: "Getting ready…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                state.error?.let {
                    Text(it, color = AppTheme.colors.alert, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                Text(
                    "This runs on your phone and takes about half a minute. The result is kept with the photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * Experimental: send this place's 360° panorama to World Labs, whose model generates a whole 3D
 * world from it (inventing what the cameras never saw), and open it in their viewer.
 */
@Composable
private fun WorldDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val job = vm.worldJob
    fun open(url: String) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI-generated 3D world") },
        text = {
            Column {
                when {
                    !vm.worldLabsKeySet -> Text(
                        "This sends the surroundings as one 360° picture to World Labs, whose model builds a full 3D world " +
                            "from it. It needs your own World Labs API key: add it in Settings > Experiments.",
                    )
                    job?.status == "done" -> Text("Ready. It opens in World Labs' viewer, where you can look and move around.")
                    job?.running == true -> {
                        Text(
                            if (job.status == "sending") "Sending the panorama…"
                            else "Generating. This usually takes about 5 minutes; you'll get a notification when it's ready, " +
                                "so you can leave this screen." + (job.progress?.let { "\n\n$it" } ?: ""),
                        )
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                    else -> {
                        Text(
                            "Sends the surroundings from these photos, as one 360° picture, to World Labs. Their model builds " +
                                "a full 3D world from it, making up what the cameras never saw, so it's a plausible version of " +
                                "the place rather than an exact one.\n\nUses the draft model: about 150 credits (about US\$0.12) " +
                                "from your World Labs account. The picture is stored in your World Labs account.",
                        )
                        job?.error?.let {
                            Text("Last try: $it", color = AppTheme.colors.alert, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                !vm.worldLabsKeySet -> androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") }
                job?.status == "done" && job.viewerUrl != null ->
                    androidx.compose.material3.TextButton(onClick = { open(job.viewerUrl); onDismiss() }) { Text("Open world") }
                job?.running == true -> androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Hide") }
                else -> androidx.compose.material3.TextButton(onClick = { vm.makeWorld() }) { Text(if (job == null) "Generate" else "Try again") }
            }
        },
        dismissButton = {
            if (vm.worldLabsKeySet && job?.status == "done") {
                androidx.compose.material3.TextButton(onClick = { vm.makeWorld() }) { Text("Generate again") }
            } else if (job?.running != true && vm.worldLabsKeySet) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
