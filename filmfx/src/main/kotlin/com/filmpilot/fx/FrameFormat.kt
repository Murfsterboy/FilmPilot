package com.filmpilot.fx

/** A rectangle in pixels: [left, right) × [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * A film frame shape, as long side : short side (3:2 for 35 mm, 1:1 for square, 65:24 for
 * panorama). Orientation follows how the photo is held, so the same crop works for portrait
 * and landscape shots.
 */
data class FrameFormat(val longToShort: Float) {
    init { require(longToShort >= 1f) }

    /** Largest centred crop of a [width]×[height] image with this shape. */
    fun crop(width: Int, height: Int): PixelRect {
        val landscape = width >= height
        val long = if (landscape) width else height
        val short = if (landscape) height else width
        val current = long.toFloat() / short
        val (newLong, newShort) =
            if (longToShort >= current) long to Math.round(long / longToShort).coerceIn(1, short)
            else Math.round(short * longToShort).coerceIn(1, long) to short
        val w = if (landscape) newLong else newShort
        val h = if (landscape) newShort else newLong
        val l = (width - w) / 2
        val t = (height - h) / 2
        return PixelRect(l, t, l + w, t + h)
    }

    companion object {
        val FULL_35 = FrameFormat(3f / 2f)
        val HALF_FRAME = FrameFormat(4f / 3f)
        val SQUARE = FrameFormat(1f)
        val PANORAMA = FrameFormat(65f / 24f)
    }
}
