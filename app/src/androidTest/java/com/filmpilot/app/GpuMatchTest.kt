package com.filmpilot.app

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filmpilot.app.camera.BitmapFilm
import com.filmpilot.app.gl.GpuDeveloper
import com.filmpilot.fx.CubeParser
import com.filmpilot.fx.FilmParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GPU shader must produce the same photo as the Kotlin reference pipeline,
 * on a real Android GL stack, including when the image is developed in tiles.
 */
@RunWith(AndroidJUnit4::class)
class GpuMatchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lut = context.assets.open("luts/fp_warm_400.cube").use { CubeParser.parse(it) }

    private fun chart(w: Int = 701, h: Int = 503): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or ((x xor y) and 0xFF)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun Bitmap.mutableCopy() = copy(Bitmap.Config.ARGB_8888, true)

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun gpu(src: Bitmap, p: FilmParams, tile: Int): IntArray {
        val dev = GpuDeveloper(maxTile = tile)
        try {
            val b = src.mutableCopy()
            runBlocking { dev.develop(b, p) }
            return pixels(b)
        } finally {
            dev.release()
        }
    }

    private fun cpu(src: Bitmap, p: FilmParams): IntArray {
        val b = src.mutableCopy()
        runBlocking { BitmapFilm.apply(b, p) }
        return pixels(b)
    }

    private fun compare(name: String, a: IntArray, b: IntArray) {
        var max = 0; var sum = 0L; var within2 = 0; val n = a.size * 3
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = kotlin.math.abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF))
            if (d > max) max = d
            sum += d
            if (d <= 2) within2++
        }
        val stats = "$name: mean=%.3f max=%d within2=%.3f%%".format(sum.toDouble() / n, max, within2 * 100.0 / n)
        Log.i("FilmPilotTest", stats)
        assertTrue(stats, within2.toDouble() / n >= 0.999)
        assertTrue(stats, max <= 12)
    }

    @Test fun gpuMatchesCpuReference() {
        val src = chart()
        val cases = mapOf(
            "lut-only" to FilmParams(lut, grain = 0f, vignette = 0f),
            "everything" to FilmParams(lut, exposureEv = 2f / 3f, grain = 0.6f, vignette = 0.5f, grainSeed = 42),
            "dark-heavy" to FilmParams(lut, exposureEv = -2f, grain = 1f, vignette = 1f, grainSeed = -7),
            "neutral" to FilmParams(null, exposureEv = 1f, grain = 0.3f, vignette = 0.2f, grainSeed = 123456789),
        )
        for ((name, p) in cases) compare(name, gpu(src, p, tile = 256), cpu(src, p))
    }

    @Test fun tilingIsInvisible() {
        val src = chart()
        val p = FilmParams(lut, exposureEv = 0.33f, grain = 0.8f, vignette = 0.7f, grainSeed = 5)
        val whole = gpu(src, p, tile = 4096)
        val tiled = gpu(src, p, tile = 128)
        assertEquals(0, whole.indices.count { whole[it] != tiled[it] })
    }
}
