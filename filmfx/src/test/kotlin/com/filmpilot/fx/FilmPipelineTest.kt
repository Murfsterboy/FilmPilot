package com.filmpilot.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilmPipelineTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun r(p: Int) = (p shr 16) and 0xFF

    private fun image(w: Int, h: Int, v: Int) = IntArray(w * h) { gray(v) }

    @Test fun neutralParamsLeavePixelsUntouched() {
        val px = IntArray(64 * 64) { (0xFF shl 24) or (it * 2654435761L.toInt() and 0xFFFFFF) }
        val copy = px.copyOf()
        FilmPipeline(FilmParams(lut = Lut3D.identity(), grain = 0f, vignette = 0f)).process(px, 64, 64)
        assertTrue(px.contentEquals(copy))
    }

    @Test fun plusOneEvDoublesLinearLight() {
        val px = image(4, 4, 100)
        FilmPipeline(FilmParams(exposureEv = 1f, grain = 0f, vignette = 0f)).process(px, 4, 4)
        val expected = (ColorMath.linearToSrgb(ColorMath.srgbToLinear(100 / 255f) * 2) * 255 + 0.5f).toInt()
        assertEquals(expected, r(px[0]))
        assertTrue(r(px[0]) > 130)
    }

    @Test fun exposureClipsAndKeepsAlpha() {
        val px = intArrayOf((0x80 shl 24) or 0xFFFFFF)
        FilmPipeline(FilmParams(exposureEv = 2f, grain = 0f, vignette = 0f)).process(px, 1, 1)
        assertEquals((0x80 shl 24) or 0xFFFFFF, px[0])
    }

    @Test fun vignetteDarkensCornersNotCentre() {
        val w = 101; val h = 101
        val px = image(w, h, 200)
        FilmPipeline(FilmParams(grain = 0f, vignette = 1f)).process(px, w, h)
        assertEquals(200, r(px[50 * w + 50]))
        assertTrue(r(px[0]) < 80, "corner was ${r(px[0])}")
    }

    @Test fun grainIsZeroMeanAndDeterministic() {
        val w = 256; val h = 256
        val a = image(w, h, 128); val b = image(w, h, 128)
        val p = FilmParams(grain = 1f, vignette = 0f, grainSeed = 7)
        FilmPipeline(p).process(a, w, h)
        FilmPipeline(p).process(b, w, h)
        assertTrue(a.contentEquals(b))
        val mean = a.map { r(it) }.average()
        assertEquals(128.0, mean, 2.0)
        assertTrue(a.map { r(it) }.distinct().size > 10, "grain should vary pixel values")
    }

    @Test fun stripsMatchWholeImage() {
        val w = 50; val h = 40
        val p = FilmParams(exposureEv = 0.5f, grain = 0.8f, vignette = 0.6f, grainSeed = 3)
        val whole = IntArray(w * h) { (0xFF shl 24) or (it * 7919 and 0xFFFFFF) }
        val strips = whole.copyOf()
        FilmPipeline(p).process(whole, w, h)
        val pipe = FilmPipeline(p)
        for (start in 0 until h step 7) {
            val rows = minOf(7, h - start)
            val buf = strips.copyOfRange(start * w, (start + rows) * w)
            pipe.processRows(buf, w, h, start, rows)
            buf.copyInto(strips, start * w)
        }
        assertTrue(whole.contentEquals(strips))
    }
}
