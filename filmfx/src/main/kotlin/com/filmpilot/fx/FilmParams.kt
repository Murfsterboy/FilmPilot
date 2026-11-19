package com.filmpilot.fx

/**
 * Everything the effects pipeline needs for one frame. Immutable and cheap to copy, so the
 * UI can hold one and a later live-preview renderer can turn the same values into uniforms.
 *
 * @property lut film look; null means no colour transform.
 * @property exposureEv exposure compensation in stops, -2..+2.
 * @property grain film grain strength, 0 (off) .. 1 (heavy).
 * @property vignette corner darkening, 0 (off) .. 1 (strong).
 * @property grainSeed varies the grain pattern between shots.
 */
data class FilmParams(
    val lut: Lut3D? = null,
    val exposureEv: Float = 0f,
    val grain: Float = 0.35f,
    val vignette: Float = 0.3f,
    val grainSeed: Int = 0,
) {
    init {
        require(exposureEv in MIN_EV..MAX_EV) { "exposureEv must be in $MIN_EV..$MAX_EV" }
        require(grain in 0f..1f) { "grain must be in 0..1" }
        require(vignette in 0f..1f) { "vignette must be in 0..1" }
    }

    companion object {
        const val MIN_EV = -2f
        const val MAX_EV = 2f
    }
}
