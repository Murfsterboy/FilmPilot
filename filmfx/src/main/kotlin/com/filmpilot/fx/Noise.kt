package com.filmpilot.fx

/**
 * Deterministic, stateless noise built only from integer hashing, so a GPU shader
 * (GLSL ES 3.0 has uint ops) can reproduce exactly the same grain later.
 */
object Noise {
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

    /**
     * Value noise: random values on an integer lattice, smoothly interpolated in between.
     * Sampling at `pixel / grainSize` gives grain clumps about `grainSize` pixels wide.
     */
    fun value(x: Float, y: Float, seed: Int): Float {
        val xf = kotlin.math.floor(x); val yf = kotlin.math.floor(y)
        val xi = xf.toInt(); val yi = yf.toInt()
        var tx = x - xf; var ty = y - yf
        tx = tx * tx * (3f - 2f * tx)
        ty = ty * ty * (3f - 2f * ty)
        val a = signed(xi, yi, seed)
        val b = signed(xi + 1, yi, seed)
        val c = signed(xi, yi + 1, seed)
        val d = signed(xi + 1, yi + 1, seed)
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
    }
}
