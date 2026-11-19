package com.filmpilot.fx

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The per-frame values derived from [FilmParams] and the image size. Both the CPU
 * reference ([FilmPipeline]) and the GPU shader ([FilmShaderSource]) use exactly these
 * numbers, so the two implementations can't drift apart.
 */
class FilmUniforms(params: FilmParams, val width: Int, val height: Int) {
    val useExposure = params.exposureEv != 0f
    val exposureGain = 2f.pow(params.exposureEv)

    val lut: Lut3D? = params.lut

    val centerX = width / 2f
    val centerY = height / 2f
    val invHalfDiagonal = 1f / sqrt(centerX * centerX + centerY * centerY)
    val vignetteAmount = params.vignette * FilmPipeline.VIGNETTE_MAX_DARKEN

    val grainAmount = params.grain * FilmPipeline.GRAIN_MAX_AMPLITUDE
    val grainSize = max(1f, min(width, height) / FilmPipeline.GRAIN_CLUMPS_ACROSS_SHORT_SIDE)
    val invGrainSize = 1f / grainSize
    val grainSeed = params.grainSeed
}
