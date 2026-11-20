package com.filmpilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmpilot.app.film.Casing
import com.filmpilot.fx.FrameFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

fun Long.color() = Color(this)

/**
 * A subtle pebbled leatherette texture in the casing's covering colour, rendered once per
 * size into a bitmap so it costs nothing per frame.
 */
fun Modifier.leatherette(base: Color): Modifier = drawWithCache {
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(this, LayoutDirection.Ltr, GfxCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        drawRect(base)
        val rnd = Random(7)
        val count = w * h / 90
        repeat(count) {
            val light = rnd.nextBoolean()
            drawCircle(
                color = (if (light) Color.White else Color.Black).copy(alpha = 0.035f + rnd.nextFloat() * 0.05f),
                radius = 1f + rnd.nextFloat() * 2.2f,
                center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h),
            )
        }
    }
    onDrawBehind { drawImage(bitmap) }
}

/**
 * Darkens everything outside the body's frame and draws frame-line corners, so you compose
 * for the shape the photo will have (3:2, square, panorama…).
 */
@Composable
fun FrameLinesOverlay(format: FrameFormat, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val crop = format.crop(size.width.roundToInt(), size.height.roundToInt())
        val l = crop.left.toFloat(); val t = crop.top.toFloat()
        val r = crop.right.toFloat(); val b = crop.bottom.toFloat()
        val shade = Color.Black.copy(alpha = 0.62f)
        if (t > 0) drawRect(shade, Offset.Zero, Size(size.width, t))
        if (b < size.height) drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        if (l > 0) drawRect(shade, Offset(0f, t), Size(l, b - t))
        if (r < size.width) drawRect(shade, Offset(r, t), Size(size.width - r, b - t))

        val inset = 10.dp.toPx()
        val len = 22.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val c = Color.White.copy(alpha = 0.8f)
        val x0 = l + inset; val y0 = t + inset; val x1 = r - inset; val y1 = b - inset
        for ((x, dx) in listOf(x0 to 1f, x1 to -1f)) for ((y, dy) in listOf(y0 to 1f, y1 to -1f)) {
            drawLine(c, Offset(x, y), Offset(x + dx * len, y), stroke)
            drawLine(c, Offset(x, y), Offset(x, y + dy * len), stroke)
        }
    }
}

/**
 * The light meter, drawn along the top of the viewfinder: a −2…+2 stop scale in ⅓ stops with an
 * amber needle showing how the photo will be exposed (meter reading + compensation).
 * Drag along it to set exposure compensation; double-tap to reset to 0.
 */
@Composable
fun LightMeterScale(
    reading: Float?,
    compensation: Float,
    onCompensation: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val currentComp by rememberUpdatedState(compensation)
    val currentOnComp by rememberUpdatedState(onCompensation)
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val exposure = reading?.let { it + compensation }
    val description = buildString {
        append("Light meter: ")
        if (exposure == null) append("no reading yet") else {
            val e = (exposure * 3).roundToInt() / 3f
            append(if (abs(e) < 0.17f) "correctly exposed" else "%.1f stops %s".format(abs(e), if (e > 0) "over" else "under"))
        }
        append(". Exposure compensation ${formatEv(compensation)}. Drag to adjust, double-tap to reset.")
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnComp(0f) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = currentComp },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val perStop = size.width * SCALE_FRACTION / 4f
                        dragAccum = (dragAccum + dx / perStop).coerceIn(-2f, 2f)
                        val snapped = (dragAccum * 3).roundToInt() / 3f
                        if (snapped != currentComp) currentOnComp(snapped)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawMeter(measurer, exposure, compensation) }
    }
}

private const val SCALE_FRACTION = 0.72f

private fun DrawScope.drawMeter(measurer: TextMeasurer, exposure: Float?, compensation: Float) {
    val scaleW = size.width * SCALE_FRACTION
    val x0 = (size.width - scaleW) / 2f
    val perStop = scaleW / 4f
    val baseY = size.height * 0.42f
    val white = Color.White.copy(alpha = 0.85f)
    val labelStyle = TextStyle(color = white, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

    for (i in -6..6) {
        val x = x0 + (i + 6) / 3f * perStop
        val major = i % 3 == 0
        val len = if (major) 10.dp.toPx() else 5.dp.toPx()
        drawLine(white, Offset(x, baseY - len), Offset(x, baseY), if (major) 1.5.dp.toPx() else 1.dp.toPx())
        if (major) {
            val stops = i / 3
            val label = if (stops > 0) "+$stops" else "$stops"
            val m = measurer.measure(label, labelStyle)
            drawText(m, topLeft = Offset(x - m.size.width / 2f, baseY + 3.dp.toPx()))
        }
    }

    // Compensation marker: small white caret under the scale.
    val cx = x0 + (compensation.coerceIn(-2f, 2f) + 2f) * perStop
    val caret = Path().apply {
        moveTo(cx, baseY - 13.dp.toPx()); lineTo(cx - 4.dp.toPx(), baseY - 19.dp.toPx()); lineTo(cx + 4.dp.toPx(), baseY - 19.dp.toPx()); close()
    }
    drawPath(caret, Color.White)

    // Needle (amber) with over/under arrows past the end of the scale.
    if (exposure != null) {
        val amber = Color(0xFFFFB13B)
        val clamped = exposure.coerceIn(-2f, 2f)
        val nx = x0 + (clamped + 2f) * perStop
        drawRoundRect(
            amber, Offset(nx - 1.5.dp.toPx(), baseY - 16.dp.toPx()), Size(3.dp.toPx(), 18.dp.toPx()),
            CornerRadius(1.5.dp.toPx()),
        )
        if (abs(exposure) > 2f) {
            val dir = if (exposure > 0) 1f else -1f
            val ax = if (exposure > 0) x0 + scaleW + 10.dp.toPx() else x0 - 10.dp.toPx()
            val arrow = Path().apply {
                moveTo(ax + dir * 6.dp.toPx(), baseY - 7.dp.toPx())
                lineTo(ax - dir * 2.dp.toPx(), baseY - 13.dp.toPx())
                lineTo(ax - dir * 2.dp.toPx(), baseY - 1.dp.toPx()); close()
            }
            drawPath(arrow, amber)
        }
    }

    // Compensation value, top right.
    val evStyle = TextStyle(color = white, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    val ev = measurer.measure(formatEv(compensation).removeSuffix(" EV"), evStyle)
    drawText(ev, topLeft = Offset(size.width - ev.size.width - 12.dp.toPx(), 6.dp.toPx()))
}

/** Small drawn icon of a camera body in its casing colours. */
@Composable
fun BodyIcon(casing: Casing, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bodyTop = h * 0.28f
        drawRoundRect(casing.plate.color(), Offset(w * 0.06f, bodyTop), Size(w * 0.88f, h * 0.6f), CornerRadius(h * 0.08f))
        drawRect(casing.covering.color(), Offset(w * 0.06f, bodyTop + h * 0.16f), Size(w * 0.88f, h * 0.3f))
        drawRoundRect(casing.plate.color(), Offset(w * 0.62f, h * 0.14f), Size(w * 0.22f, h * 0.18f), CornerRadius(h * 0.04f))
        drawCircle(casing.metal.color(), h * 0.24f, Offset(w * 0.42f, bodyTop + h * 0.31f))
        drawCircle(Color(0xFF101010), h * 0.17f, Offset(w * 0.42f, bodyTop + h * 0.31f))
        drawCircle(Color(0xFF3A4A5A), h * 0.08f, Offset(w * 0.40f, bodyTop + h * 0.28f))
        drawCircle(casing.metal.color(), h * 0.06f, Offset(w * 0.2f, h * 0.22f))
    }
}

/** Small drawn icon of a lens barrel, longer for longer focal lengths. */
@Composable
fun LensIcon(focalMm: Float, toy: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val barrel = (0.35f + (focalMm.coerceIn(20f, 90f) - 20f) / 70f * 0.45f) * w
        val ring = if (toy) Color(0xFFD94F3D) else Color(0xFF2A2A2A)
        val x = (w - barrel) / 2f
        drawRoundRect(Color(0xFF1A1A1A), Offset(x, h * 0.2f), Size(barrel, h * 0.6f), CornerRadius(h * 0.06f))
        drawRect(ring, Offset(x + barrel * 0.25f, h * 0.2f), Size(barrel * 0.2f, h * 0.6f))
        drawRect(Color(0xFFBDBDBD), Offset(x + barrel * 0.62f, h * 0.2f), Size(barrel * 0.06f, h * 0.6f))
        drawCircle(Color(0xFF4F6B80).copy(alpha = 0.9f), h * 0.18f, Offset(x + barrel, h * 0.5f), style = Stroke(h * 0.06f))
    }
}

/** Small drawn icon of a 35 mm film canister. */
@Composable
fun FilmIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFFE8A33D)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Color(0xFF9E9E9E), Offset(w * 0.36f, h * 0.08f), Size(w * 0.14f, h * 0.08f))
        drawRoundRect(tint, Offset(w * 0.28f, h * 0.16f), Size(w * 0.3f, h * 0.68f), CornerRadius(w * 0.04f))
        drawRect(Color(0xFF222222), Offset(w * 0.28f, h * 0.16f), Size(w * 0.3f, h * 0.08f))
        drawRect(Color(0xFF222222), Offset(w * 0.28f, h * 0.76f), Size(w * 0.3f, h * 0.08f))
        drawRect(Color(0xFF2E2A26), Offset(w * 0.58f, h * 0.36f), Size(w * 0.18f, h * 0.3f))
    }
}
