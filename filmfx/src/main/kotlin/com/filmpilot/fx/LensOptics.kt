package com.filmpilot.fx

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Optical character of a lens, applied before the film:
 *
 * @property distortion radial distortion k: > 0 barrel (wide/cheap lenses), < 0 pincushion
 *   (telephotos). The frame is rescaled so barrel distortion never shows empty corners.
 * @property chromatic lateral chromatic aberration: red is magnified and blue shrunk by
 *   `chromatic * r²` (fraction of the half-diagonal), giving colour fringes towards the corners.
 * @property softness edge softness, 0..1: a radial smear that grows with `r²`, like the soft
 *   corners of simple lenses.
 */
data class LensParams(
    val distortion: Float = 0f,
    val chromatic: Float = 0f,
    val softness: Float = 0f,
) {
    val isNeutral get() = distortion == 0f && chromatic == 0f && softness == 0f

    /** Blur length at the corners as a fraction of the radius, at softness 1. */
    val blur get() = softness * SOFTNESS_SCALE

    companion object {
        const val SOFTNESS_SCALE = 0.012f
        val NONE = LensParams()
    }
}

/**
 * CPU reference for lens optics; [LensShaderSource] is the GPU port. Unlike the film
 * pipeline, every output pixel samples the source at another position, so this needs the
 * whole source image. Output rows can still be computed in strips on many threads.
 */
object LensOptics {
    private val TAPS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
    private val CENTER_TAP = floatArrayOf(0f)

    /** Returns a new image with [lens] applied to [src] (ARGB_8888, [w]×[h]). */
    fun process(src: IntArray, w: Int, h: Int, lens: LensParams): IntArray {
        val dst = IntArray(w * h)
        processRows(src, w, h, lens, dst, 0, h)
        return dst
    }

    /** Computes output rows [rowStart] until `rowStart + rowCount` into [dst] (rows only, `rowCount * w`). */
    fun processRows(src: IntArray, w: Int, h: Int, lens: LensParams, dst: IntArray, rowStart: Int, rowCount: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val hd = sqrt(cx * cx + cy * cy)
        val k = lens.distortion
        val ca = lens.chromatic
        val blur = lens.blur
        val norm = 1f / (1f + max(k, 0f))
        val taps = if (blur > 0f) TAPS else CENTER_TAP
        val n = taps.size.toFloat()

        for (row in 0 until rowCount) {
            val y = rowStart + row
            val qy = (y + 0.5f - cy) / hd
            for (x in 0 until w) {
                val qx = (x + 0.5f - cx) / hd
                val r2 = qx * qx + qy * qy
                val s = (1f + k * r2) * norm
                var accR = 0f; var accG = 0f; var accB = 0f
                for (t in taps) {
                    val f = s * (1f + blur * r2 * t)
                    val fr = f * (1f + ca * r2)
                    val fb = f * (1f - ca * r2)
                    accR += bilinear(src, w, h, cx + qx * fr * hd, cy + qy * fr * hd, 16)
                    accG += bilinear(src, w, h, cx + qx * f * hd, cy + qy * f * hd, 8)
                    accB += bilinear(src, w, h, cx + qx * fb * hd, cy + qy * fb * hd, 0)
                }
                val alpha = src[y * w + x] and ALPHA_MASK
                dst[row * w + x] = alpha or (to8(accR / n) shl 16) or (to8(accG / n) shl 8) or to8(accB / n)
            }
        }
    }

    /**
     * Bilinear sample of one channel at image position ([px], [py]) where pixel centres sit at
     * `i + 0.5`, clamping at the edges: the same as GL_LINEAR + CLAMP_TO_EDGE.
     */
    private fun bilinear(src: IntArray, w: Int, h: Int, px: Float, py: Float, shift: Int): Float {
        val fx = px - 0.5f
        val fy = py - 0.5f
        val x0f = floor(fx); val y0f = floor(fy)
        val tx = fx - x0f; val ty = fy - y0f
        val x0 = x0f.toInt().coerceIn(0, w - 1); val x1 = (x0f.toInt() + 1).coerceIn(0, w - 1)
        val y0 = y0f.toInt().coerceIn(0, h - 1); val y1 = (y0f.toInt() + 1).coerceIn(0, h - 1)
        val a = (src[y0 * w + x0] shr shift) and 0xFF
        val b = (src[y0 * w + x1] shr shift) and 0xFF
        val c = (src[y1 * w + x0] shr shift) and 0xFF
        val d = (src[y1 * w + x1] shr shift) and 0xFF
        return lerp(lerp(a.toFloat(), b.toFloat(), tx), lerp(c.toFloat(), d.toFloat(), tx), ty)
    }

    private fun to8(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
    private const val ALPHA_MASK = 0xFF000000.toInt()
}
