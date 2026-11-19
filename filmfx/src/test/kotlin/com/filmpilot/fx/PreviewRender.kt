package com.filmpilot.fx

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders before/after images for eyeballing the look. Off by default; run with:
 *   FX_PREVIEW_DIR=/some/dir ./gradlew -PfxOnly :filmfx:test --tests '*PreviewRender*'
 * Optional FX_PREVIEW_INPUT=photo.jpg uses your own photo instead of the test chart.
 */
class PreviewRender {
    @Test fun render() {
        val dir = System.getenv("FX_PREVIEW_DIR") ?: return
        val input = System.getenv("FX_PREVIEW_INPUT")?.let { ImageIO.read(File(it)) } ?: testChart(1200, 800)
        val w = input.width; val h = input.height
        val src = input.getRGB(0, 0, w, h, null, 0, w)
        val lut = File("../app/src/main/assets/luts/fp_warm_400.cube").inputStream().use { CubeParser.parse(it) }
        val variants = mapOf(
            "0-original" to null,
            "1-lut" to FilmParams(lut, grain = 0f, vignette = 0f),
            "2-default" to FilmParams(lut, grain = 0.35f, vignette = 0.3f, grainSeed = 1),
            "3-heavy-minus1ev" to FilmParams(lut, exposureEv = -1f, grain = 1f, vignette = 1f, grainSeed = 2),
            "4-plus1ev" to FilmParams(lut, exposureEv = 1f, grain = 0.35f, vignette = 0.3f, grainSeed = 3),
        )
        File(dir).mkdirs()
        for ((name, p) in variants) {
            val px = src.copyOf()
            if (p != null) FilmPipeline(p).process(px, w, h)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(out, "png", File(dir, "$name.png"))
        }
    }

    /** Hue sweep on top, grey ramp in the middle, skin-ish and sky-ish patches at the bottom. */
    private fun testChart(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val t = x / (w - 1f)
            val rgb = when {
                y < h * 0.4 -> java.awt.Color.HSBtoRGB(t, 0.75f, 1f - (y / (h * 0.4f)) * 0.6f)
                y < h * 0.6 -> { val v = (t * 255).toInt(); (v shl 16) or (v shl 8) or v }
                else -> {
                    val patches = intArrayOf(0xE0AC90, 0xC68863, 0x8D5524, 0x6FA8DC, 0x3D7A3D, 0xD9C27A, 0x404048, 0xF2F2F2)
                    patches[(t * patches.size).toInt().coerceAtMost(patches.size - 1)]
                }
            }
            img.setRGB(x, y, rgb)
        }
        return img
    }
}
