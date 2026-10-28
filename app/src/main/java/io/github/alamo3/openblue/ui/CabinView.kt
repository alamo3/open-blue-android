package io.github.alamo3.openblue.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.alamo3.openblue.R
import io.github.alamo3.openblue.api.SeatLevel
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Width / height of res/drawable-nodpi/cabin_top.webp (tools/model/cabinview.py). */
private const val CABIN_ASPECT = 700f / 1500f

/**
 * Seats in the cabin image, as fractions of its width and height: driver, passenger,
 * rear left, rear right. The nose is at the top and the driver's side on the left.
 */
private val SEAT_RECTS = listOf(
    Rect(0.211f, 0.398f, 0.45f, 0.56f),
    Rect(0.55f, 0.398f, 0.789f, 0.56f),
    Rect(0.211f, 0.602f, 0.425f, 0.743f),
    Rect(0.575f, 0.602f, 0.789f, 0.743f),
)
private val WHEEL_CENTER = Offset(0.330f, 0.388f)
private const val WHEEL_RADIUS = 0.071f // of the width
private val WINDSHIELD = Rect(0.16f, 0.21f, 0.84f, 0.28f)

/**
 * The real cabin seen from above with the roof cut away (rendered from the same 3D model as the
 * home screen). Heated seats warm up with a glow that spreads through the cushion and backrest;
 * ventilated seats do the same in blue. The steering wheel
 * glows when it's heated and the windshield when defrosting. Tap a seat to cycle it.
 */
@Composable
fun CabinView(
    seats: List<SeatLevel>,
    wheelHeated: Boolean,
    defrost: Boolean,
    onSeat: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    // Each seat's warmth eases in and out: + for heat, - for cooling, magnitude 0..3.
    val levels = seats.mapIndexed { i, s ->
        animateFloatAsState(
            when {
                s.isHeat -> s.bars.toFloat()
                s.isCool -> -s.bars.toFloat()
                else -> 0f
            },
            tween(700),
            label = "seat$i",
        ).value
    }
    val wheel by animateFloatAsState(if (wheelHeated) 1f else 0f, tween(700), label = "wheel")
    val glass by animateFloatAsState(if (defrost) 1f else 0f, tween(700), label = "defrost")

    var time by remember { mutableFloatStateOf(0f) }
    val active = levels.any { it != 0f } || wheel > 0f || glass > 0f
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }

    BoxWithConstraints(modifier.aspectRatio(CABIN_ASPECT)) {
        val w = maxWidth
        val h = maxHeight
        Image(
            painterResource(R.drawable.cabin_top),
            contentDescription = "Cabin seen from above: driver, passenger, rear left and rear right seats",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val t = time
            if (glass > 0f) drawDefrost(WINDSHIELD.scaled(size), t, colors.heat, glass)
            levels.forEachIndexed { i, level ->
                val r = SEAT_RECTS[i].scaled(size)
                when {
                    level > 0f -> drawSeatHeat(r, t, colors.heat, level, i)
                    level < 0f -> drawSeatCool(r, t, colors.cool, -level, i)
                }
            }
            if (wheel > 0f) {
                val c = Offset(WHEEL_CENTER.x * size.width, WHEEL_CENTER.y * size.height)
                drawWheelHeat(c, WHEEL_RADIUS * size.width, t, colors.heat, wheel)
            }
        }
        // Tap targets, each with a small readout of its level.
        seats.forEachIndexed { i, level ->
            val r = SEAT_RECTS[i]
            Box(
                Modifier
                    .offset(w * r.left, h * r.top)
                    .size(w * r.width, h * r.height)
                    .pressable(onClick = { onSeat(i) }),
                contentAlignment = Alignment.Center,
            ) {
                SeatReadout(level)
            }
        }
    }
}

private fun Rect.scaled(s: Size) = Rect(left * s.width, top * s.height, right * s.width, bottom * s.height)

@Composable
private fun SeatReadout(level: SeatLevel) {
    val app = AppTheme.colors
    val tint = when {
        level.isHeat -> app.heat
        level.isCool -> app.cool
        else -> Color.White.copy(alpha = 0.7f)
    }
    // The cabin is dark in both themes, so the readout is always light on a dark chip.
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { b ->
                Box(
                    Modifier
                        .size(width = 10.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (b < level.bars) tint else Color.White.copy(alpha = 0.22f)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                level.isHeat -> "Heat"
                level.isCool -> "Cool"
                else -> "Off"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private fun seatShape(r: Rect) = Path().apply {
    val c = r.width * 0.22f
    addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(c, c)))
}

/**
 * Heat or cooling: the seat takes on the colour from the middle outward, with slow soft
 * currents drifting up through it. [level] is 0..3 (animated); [highlight] tints the currents.
 */
private fun DrawScope.drawSeatAir(r: Rect, t: Float, tint: Color, highlight: Color, level: Float, seed: Int) {
    val k = (level / 3f).coerceIn(0f, 1f)
    val breath = 0.9f + 0.1f * sin(t * 1.6f + seed)
    clipPath(seatShape(r)) {
        drawRect(
            Brush.radialGradient(
                listOf(tint.copy(alpha = (0.2f + 0.45f * k) * breath), tint.copy(alpha = 0.12f * k), Color.Transparent),
                center = r.center,
                radius = max(r.width, r.height) * (0.45f + 0.25f * k),
            ),
            r.topLeft, r.size,
        )
        // Soft wavy bands moving slowly up the seat.
        val bands = 3
        val bright = lerp(tint, highlight, 0.5f)
        for (b in 0 until bands) {
            val phase = ((t * 0.12f + b.toFloat() / bands + seed * 0.21f) % 1f)
            val y = r.bottom - phase * r.height
            val path = Path()
            val steps = 16
            for (s in 0..steps) {
                val x = r.left + r.width * s / steps
                val yy = y + r.height * 0.035f * sin((s.toFloat() / steps * 2f * PI.toFloat()) + t * 1.3f + b)
                if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            val fade = sin(phase * PI.toFloat())
            drawPath(path, bright.copy(alpha = 0.32f * k * fade), style = Stroke(width = r.width * 0.07f))
        }
    }
}

private fun DrawScope.drawSeatHeat(r: Rect, t: Float, heat: Color, level: Float, seed: Int) =
    drawSeatAir(r, t, heat, Color(0xFFFFD08A), level, seed)

private fun DrawScope.drawSeatCool(r: Rect, t: Float, cool: Color, level: Float, seed: Int) =
    drawSeatAir(r, t, cool, Color(0xFFCDEBFF), level, seed)

private fun DrawScope.drawWheelHeat(c: Offset, radius: Float, t: Float, heat: Color, k: Float) {
    val breath = 0.85f + 0.15f * sin(t * 1.8f)
    drawCircle(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            0.8f to heat.copy(alpha = 0.55f * k * breath),
            1f to Color.Transparent,
            center = c,
            radius = radius * 1.25f,
        ),
        radius * 1.25f, c,
    )
}

/** Defrost: warm air fanning up the inside of the windshield from the vents. */
private fun DrawScope.drawDefrost(r: Rect, t: Float, heat: Color, k: Float) {
    val lanes = 7
    for (i in 0 until lanes) {
        val x = r.left + r.width * (i + 0.5f) / lanes
        val phase = ((t * 0.5f + i * 0.29f) % 1f)
        val y = r.bottom - phase * r.height
        val fade = sin(phase * PI.toFloat())
        drawLine(
            heat.copy(alpha = 0.5f * k * fade),
            Offset(x, y + r.height * 0.25f),
            Offset(x, y - r.height * 0.15f),
            strokeWidth = r.width * 0.012f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
