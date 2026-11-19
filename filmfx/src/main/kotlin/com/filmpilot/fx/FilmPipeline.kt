package com.filmpilot.fx

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Applies the film look to ARGB_8888 pixels (the layout of Android's `Bitmap.getPixels`).
 *
 * Every step is a pure function of (colour, normalized position, params), which is what
 * lets a later phase port it line by line to a fragment shader:
 *
 *  1. Exposure: in linear light, `rgb * 2^EV`.
 *  2. Film LUT: trilinear 3D lookup on the gamma-encoded colour.
 *  3. Vignette: radial falloff from the centre, normalized to the half-diagonal.
 *  4. Grain: monochrome value noise, strongest in the midtones, with clumps scaled
 *     to image size so the look is the same at any resolution.
 *
 * The image can be processed in horizontal strips (any order, any thread) to keep memory low:
 * pass the full image size plus the strip's first row.
 */
class FilmPipeline(private val params: FilmParams) {

    private val exposureGain = 2f.pow(params.exposureEv)
    private val applyExposure = params.exposureEv != 0f

    /** Process a whole image held in [pixels]. */
    fun process(pixels: IntArray, width: Int, height: Int) =
        processRows(pixels, width, height, rowStart = 0, rowCount = height)

    /**
     * Process [rowCount] rows starting at image row [rowStart]. [pixels] holds just those rows
     * (`rowCount * width` entries); [width] and [height] are the full image's size.
     */
    fun processRows(pixels: IntArray, width: Int, height: Int, rowStart: Int, rowCount: Int) {
        require(pixels.size >= width * rowCount) { "pixels too small for $rowCount rows" }
        val lut = params.lut
        val rgb = FloatArray(3)

        // Position helpers (shared with a future shader as uniforms).
        val cx = width / 2f
        val cy = height / 2f
        val invHalfDiag = 1f / sqrt(cx * cx + cy * cy)
        val vignetteAmount = params.vignette * VIGNETTE_MAX_DARKEN
        val grainAmount = params.grain * GRAIN_MAX_AMPLITUDE
        val grainSize = max(1f, min(width, height) / GRAIN_CLUMPS_ACROSS_SHORT_SIDE)
        val invGrainSize = 1f / grainSize

        for (row in 0 until rowCount) {
            val y = rowStart + row
            val dy = (y + 0.5f - cy)
            var idx = row * width
            for (x in 0 until width) {
                val argb = pixels[idx]
                var r: Float; var g: Float; var b: Float

                // 1. Exposure, in linear light.
                if (applyExposure) {
                    r = ColorMath.linearToSrgbFast(ColorMath.SRGB8_TO_LINEAR[(argb shr 16) and 0xFF] * exposureGain)
                    g = ColorMath.linearToSrgbFast(ColorMath.SRGB8_TO_LINEAR[(argb shr 8) and 0xFF] * exposureGain)
                    b = ColorMath.linearToSrgbFast(ColorMath.SRGB8_TO_LINEAR[argb and 0xFF] * exposureGain)
                } else {
                    r = ((argb shr 16) and 0xFF) * INV255
                    g = ((argb shr 8) and 0xFF) * INV255
                    b = (argb and 0xFF) * INV255
                }

                // 2. Film LUT.
                if (lut != null) {
                    lut.sample(r, g, b, rgb)
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }

                // 3. Vignette.
                if (vignetteAmount > 0f) {
                    val dx = x + 0.5f - cx
                    val dist = sqrt(dx * dx + dy * dy) * invHalfDiag
                    val k = 1f - vignetteAmount * ColorMath.smoothstep(VIGNETTE_START, 1f, dist)
                    r *= k; g *= k; b *= k
                }

                // 4. Grain, weighted to the midtones (film grain is least visible in deep
                //    shadows and clipped highlights).
                if (grainAmount > 0f) {
                    val n = Noise.value(x * invGrainSize, y * invGrainSize, params.grainSeed)
                    val l = ColorMath.luma(r, g, b).coerceIn(0f, 1f)
                    val weight = GRAIN_FLOOR + (1f - GRAIN_FLOOR) * 4f * l * (1f - l)
                    val delta = n * grainAmount * weight
                    r += delta; g += delta; b += delta
                }

                pixels[idx] = (argb and ALPHA_MASK) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
                idx++
            }
        }
    }

    private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    companion object {
        private const val INV255 = 1f / 255f
        private const val ALPHA_MASK = 0xFF000000.toInt()

        /** Brightness lost in the extreme corners at vignette = 1. */
        const val VIGNETTE_MAX_DARKEN = 0.75f
        /** Fraction of the half-diagonal where the vignette starts. */
        const val VIGNETTE_START = 0.35f

        /** Peak grain deviation (in 0..1 colour units) at grain = 1. */
        const val GRAIN_MAX_AMPLITUDE = 0.16f
        /** Grain still visible in shadows/highlights, as a fraction of the midtone amount. */
        const val GRAIN_FLOOR = 0.3f
        /** Grain clump size: short side / this. ~1600 gives fine 35mm-like grain. */
        const val GRAIN_CLUMPS_ACROSS_SHORT_SIDE = 1600f
    }
}
