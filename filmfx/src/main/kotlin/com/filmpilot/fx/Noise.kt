package com.filmpilot.fx

/**
 * Film grain noise, built so the CPU and every GPU produce identical grain.
 *
 * Random values live on a fixed 1024×1024 lattice (generated once with an integer hash).
 * The GPU reads the same lattice as a texture, so shaders only need small integer adds and
 * masks: no 32-bit integer multiplies, which some GPUs (and the Android emulator) evaluate
 * differently. A shot's seed picks an offset into the lattice, so each frame gets new grain.
 */
object Noise {
    const val LATTICE_SIZE = 1024
    private const val MASK = LATTICE_SIZE - 1
    private const val LATTICE_SEED = 0x46504C4D

    /** Integer hash (lowbias32 by Chris Wellons). Returns a well-mixed 32-bit value. */
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 0x27d4eb2d xor (y * 0x165667b1) xor (seed * 0x5bd1e995.toInt())
        h = h xor (h ushr 16); h *= 0x7feb352d
        h = h xor (h ushr 15); h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** Hash to a float in -1..1. */
    fun signed(x: Int, y: Int, seed: Int): Float =
        (hash(x, y, seed) ushr 8) * (2f / 16777215f) - 1f

    /** The lattice, row-major (`y * LATTICE_SIZE + x`), values in -1..1. Upload this as the GPU texture. */
    val lattice: FloatArray by lazy { FloatArray(LATTICE_SIZE * LATTICE_SIZE) { signed(it and MASK, it shr 10, LATTICE_SEED) } }

    /** Lattice offset for a seed: x in the low 10 bits, y in the next 10. */
    fun offsetX(seed: Int) = hash(seed, 1, 0x5EED) and MASK
    fun offsetY(seed: Int) = hash(seed, 2, 0x5EED) and MASK

    private fun at(x: Int, y: Int, lat: FloatArray) = lat[((y and MASK) shl 10) or (x and MASK)]

    /**
     * Value noise: lattice values smoothly interpolated in between. Sampling at
     * `pixel / grainSize` gives grain clumps about `grainSize` pixels wide.
     */
    fun value(x: Float, y: Float, offsetX: Int, offsetY: Int): Float {
        val lat = lattice
        val xf = kotlin.math.floor(x); val yf = kotlin.math.floor(y)
        val xi = xf.toInt() + offsetX; val yi = yf.toInt() + offsetY
        var tx = x - xf; var ty = y - yf
        tx = tx * tx * (3f - 2f * tx)
        ty = ty * ty * (3f - 2f * ty)
        val a = at(xi, yi, lat)
        val b = at(xi + 1, yi, lat)
        val c = at(xi, yi + 1, lat)
        val d = at(xi + 1, yi + 1, lat)
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
    }
}
