package com.filmpilot.app.camera

import android.graphics.Bitmap
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.FilmPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Bridges Android [Bitmap]s to the platform-independent [FilmPipeline].
 * The image is processed in horizontal strips on all cores, so only a few small
 * pixel buffers exist besides the bitmap itself.
 */
object BitmapFilm {
    private const val STRIP_ROWS = 64

    suspend fun apply(bitmap: Bitmap, params: FilmParams) = coroutineScope {
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        val w = bitmap.width
        val h = bitmap.height
        val pipeline = FilmPipeline(params)
        val lock = Any()
        (0 until h step STRIP_ROWS).map { start ->
            async(Dispatchers.Default) {
                val rows = minOf(STRIP_ROWS, h - start)
                val buf = IntArray(w * rows)
                synchronized(lock) { bitmap.getPixels(buf, 0, w, 0, start, w, rows) }
                pipeline.processRows(buf, w, h, start, rows)
                synchronized(lock) { bitmap.setPixels(buf, 0, w, 0, start, w, rows) }
            }
        }.awaitAll()
    }
}
