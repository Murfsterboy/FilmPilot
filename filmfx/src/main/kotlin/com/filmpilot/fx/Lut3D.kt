package com.filmpilot.fx

/**
 * A 3D colour lookup table, as stored in an Adobe/Resolve `.cube` file.
 *
 * [data] holds `size³` RGB triplets with red changing fastest, then green, then blue,
 * exactly the order of the `.cube` data lines. This is also the layout a later phase can
 * upload as a 3D texture (or a 2D strip) for a live preview shader.
 */
class Lut3D(
    val title: String,
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size >= 2) { "LUT size must be at least 2" }
        require(data.size == size * size * size * 3) {
            "Expected ${size * size * size} entries for a size-$size LUT, got ${data.size / 3}"
        }
    }

    /**
     * Trilinear lookup of ([r], [g], [b]) (in the LUT's input domain, usually 0..1).
     * Writes the result into [out] (length ≥ 3) to avoid allocating per pixel.
     */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = (normalize(r, 0) * n).coerceIn(0f, n.toFloat())
        val fg = (normalize(g, 1) * n).coerceIn(0f, n.toFloat())
        val fb = (normalize(b, 2) * n).coerceIn(0f, n.toFloat())

        val r0 = minOf(fr.toInt(), n - 1); val dr = fr - r0
        val g0 = minOf(fg.toInt(), n - 1); val dg = fg - g0
        val b0 = minOf(fb.toInt(), n - 1); val db = fb - b0

        val s = size
        val sg = s * 3          // stride between green steps
        val sb = s * s * 3      // stride between blue steps
        val i000 = (r0 + g0 * s + b0 * s * s) * 3

        for (c in 0 until 3) {
            val i = i000 + c
            val c00 = lerp(data[i], data[i + 3], dr)
            val c10 = lerp(data[i + sg], data[i + sg + 3], dr)
            val c01 = lerp(data[i + sb], data[i + sb + 3], dr)
            val c11 = lerp(data[i + sg + sb], data[i + sg + sb + 3], dr)
            out[c] = lerp(lerp(c00, c10, dg), lerp(c01, c11, dg), db)
        }
    }

    private fun normalize(v: Float, channel: Int): Float {
        val lo = domainMin[channel]
        val hi = domainMax[channel]
        return (v - lo) / (hi - lo)
    }

    companion object {
        /** An identity LUT: output equals input. Handy for tests and as a neutral "film". */
        fun identity(size: Int = 17, title: String = "Neutral"): Lut3D {
            val data = FloatArray(size * size * size * 3)
            var i = 0
            val n = (size - 1).toFloat()
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[i++] = r / n; data[i++] = g / n; data[i++] = b / n
            }
            return Lut3D(title, size, data)
        }
    }
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
