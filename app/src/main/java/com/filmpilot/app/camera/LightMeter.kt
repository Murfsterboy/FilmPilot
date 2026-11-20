package com.filmpilot.app.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.filmpilot.fx.ColorMath
import kotlin.math.ln
import kotlin.math.max

/**
 * A centre-weighted reflected-light meter, like the one in a film SLR, reading the camera's
 * analysis stream (Y plane). Reports how the frame will be exposed relative to mid-grey (18%),
 * in stops: 0 = correctly exposed, −1 = a stop under, +1 = a stop over. Exposure compensation
 * is added by the UI. About 8 readings a second.
 */
class LightMeter(private val onReading: (Float) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - last < INTERVAL_MS) return
            last = now
            onReading(measure(image))
        } finally {
            image.close()
        }
    }

    private fun measure(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val step = max(1, minOf(w, h) / 48)
        var sum = 0.0
        var weights = 0.0
        val cx = w / 2f; val cy = h / 2f
        val norm = 1f / (cx * cx + cy * cy)
        var y = step / 2
        while (y < h) {
            var x = step / 2
            val row = y * rowStride
            while (x < w) {
                val luma = buf.get(row + x * pixelStride).toInt() and 0xFF
                val dx = x - cx; val dy = y - cy
                val weight = 1.0 - 0.75 * (dx * dx + dy * dy) * norm // centre-weighted
                sum += LINEAR[luma] * weight
                weights += weight
                x += step
            }
            y += step
        }
        val mean = (sum / weights).coerceAtLeast(1e-4)
        return (ln(mean / MID_GREY) / LN2).toFloat().coerceIn(-5f, 5f)
    }

    private companion object {
        const val INTERVAL_MS = 120L
        const val MID_GREY = 0.18
        val LN2 = ln(2.0)
        val LINEAR = DoubleArray(256) { ColorMath.srgbToLinear(it / 255f).toDouble() }
    }
}
