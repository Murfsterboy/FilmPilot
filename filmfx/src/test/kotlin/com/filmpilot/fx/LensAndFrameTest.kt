package com.filmpilot.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensAndFrameTest {
    private fun chart(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        (0xFF shl 24) or ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or ((x xor y) and 0xFF)
    }
    private fun ch(p: Int, s: Int) = (p shr s) and 0xFF

    @Test fun neutralLensIsIdentity() {
        val src = chart(64, 48)
        assertTrue(LensOptics.process(src, 64, 48, LensParams.NONE).contentEquals(src))
    }

    @Test fun barrelMagnifiesCentreAndKeepsCornersInside() {
        val w = 101; val h = 81
        val src = chart(w, h)
        val out = LensOptics.process(src, w, h, LensParams(distortion = 0.1f))
        val c = 40 * w + 50
        assertTrue(kotlin.math.abs(ch(src[c], 16) - ch(out[c], 16)) <= 2, "centre stays put")
        // Corners map to corners (no empty edges); the middle of each edge is pulled inwards.
        assertTrue(ch(out[0], 16) <= 2, "corner maps to the corner")
        assertTrue(ch(out[40 * w], 16) >= 3, "mid-left edge samples from further inside")
    }

    @Test fun chromaticSeparatesChannelsOnlyAwayFromCentre() {
        val w = 120; val h = 90
        val src = IntArray(w * h) { i -> if ((i % w) / 10 % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val out = LensOptics.process(src, w, h, LensParams(chromatic = 0.02f))
        val centre = out[45 * w + 60]
        assertEquals(ch(centre, 16), ch(centre, 0))
        val fringes = (0 until w).count { x -> val p = out[45 * w + x]; kotlin.math.abs(ch(p, 16) - ch(p, 0)) > 20 }
        assertTrue(fringes > 4, "expected colour fringes towards the edges, got $fringes")
    }

    @Test fun stripsMatchWhole() {
        val w = 60; val h = 50
        val src = chart(w, h)
        val lens = LensParams(0.05f, 0.003f, 0.5f)
        val whole = LensOptics.process(src, w, h, lens)
        val strips = IntArray(w * h)
        for (start in 0 until h step 7) {
            val rows = minOf(7, h - start)
            val buf = IntArray(rows * w)
            LensOptics.processRows(src, w, h, lens, buf, start, rows)
            buf.copyInto(strips, start * w)
        }
        assertTrue(whole.contentEquals(strips))
    }

    @Test fun frameCrops() {
        assertEquals(PixelRect(0, 0, 4000, 3000), FrameFormat.HALF_FRAME.crop(4000, 3000))
        assertEquals(PixelRect(0, 166, 4000, 2833), FrameFormat.FULL_35.crop(4000, 3000))
        assertEquals(PixelRect(500, 0, 3500, 3000), FrameFormat.SQUARE.crop(4000, 3000))
        val p = FrameFormat.PANORAMA.crop(3000, 4000)
        assertEquals(4000, p.height); assertEquals(1477, p.width)
    }
}
