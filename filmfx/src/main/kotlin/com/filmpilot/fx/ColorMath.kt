package com.filmpilot.fx

import kotlin.math.pow

/** sRGB transfer functions. Exposure is applied in linear light so +1 EV really doubles the light. */
object ColorMath {
    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Rec. 709 luma of gamma-encoded RGB; good enough to weight grain by brightness. */
    fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** 8-bit sRGB → linear, precomputed. */
    internal val SRGB8_TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255f) }

    /** Linear 0..1 → sRGB 0..1, sampled finely enough that 8-bit output is exact. */
    private const val ENCODE_STEPS = 4096
    private val LINEAR_TO_SRGB = FloatArray(ENCODE_STEPS + 1) { linearToSrgb(it / ENCODE_STEPS.toFloat()) }

    internal fun linearToSrgbFast(c: Float): Float {
        if (c <= 0f) return 0f
        if (c >= 1f) return 1f
        val f = c * ENCODE_STEPS
        val i = f.toInt()
        return lerp(LINEAR_TO_SRGB[i], LINEAR_TO_SRGB[minOf(i + 1, ENCODE_STEPS)], f - i)
    }
}
