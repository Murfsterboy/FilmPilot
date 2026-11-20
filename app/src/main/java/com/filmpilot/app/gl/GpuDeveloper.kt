package com.filmpilot.app.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.FilmUniforms
import com.filmpilot.fx.LensParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Develops full-resolution photos with the same film shader as the live viewfinder.
 * Large images are processed in tiles (the shader keeps vignette and grain continuous
 * across tiles), so any photo size works within the GPU's texture limits.
 *
 * Owns an offscreen GL context on its own thread. Throws if the GPU path isn't
 * available; callers fall back to the CPU pipeline.
 */
class GpuDeveloper(private val maxTile: Int = 2048) {
    private val glThread = Executors.newSingleThreadExecutor { Thread(it, "FilmPilot-DevelopGL") }
    private val dispatcher = glThread.asCoroutineDispatcher()

    private var egl: EglCore? = null
    private lateinit var pbuffer: EGLSurface
    private lateinit var film: FilmRenderer
    private lateinit var lensRenderer: LensRenderer
    private var lensSourceTexture = 0
    private lateinit var target: GlUtil.RenderTarget
    private var inputTexture = 0
    private var tileLimit = 0

    /** Applies [params] to [bitmap] (mutable ARGB_8888) in place. */
    suspend fun develop(bitmap: Bitmap, params: FilmParams) = withContext(dispatcher) {
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        ensureGl()
        val w = bitmap.width
        val h = bitmap.height
        val u = FilmUniforms(params, w, h)
        val tile = minOf(maxTile, tileLimit)
        val readback = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC); isFilterBitmap = false }

        for (ty in 0 until h step tile) for (tx in 0 until w step tile) {
            val tw = minOf(tile, w - tx)
            val th = minOf(tile, h - ty)
            // Bitmap row 0 → texture row 0 → framebuffer row 0 → readback row 0: no flips needed.
            val sub = Bitmap.createBitmap(bitmap, tx, ty, tw, th)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, sub, 0)
            sub.recycle()

            target.resize(tw, th)
            target.bind()
            film.draw(inputTexture, u, tx, ty)

            readback.clear()
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readback)
            GlUtil.checkError("develop tile $tx,$ty")
            readback.position(0)
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(readback)
            canvas.drawBitmap(out, tx.toFloat(), ty.toFloat(), paint)
            out.recycle()
        }
    }

    /**
     * Applies lens optics to [bitmap] in place. The whole photo is uploaded as one texture
     * (each output pixel samples elsewhere in the frame), then rendered back in tiles.
     * Throws if the photo is larger than the GPU's texture limit; callers fall back to the CPU.
     */
    suspend fun applyLens(bitmap: Bitmap, lens: LensParams) = withContext(dispatcher) {
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        ensureGl()
        val w = bitmap.width
        val h = bitmap.height
        check(w <= tileLimit && h <= tileLimit) { "${w}x$h exceeds the GPU texture limit $tileLimit" }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensSourceTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GlUtil.checkError("lens source upload")

        val tile = minOf(maxTile, tileLimit)
        val readback = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC); isFilterBitmap = false }
        for (ty in 0 until h step tile) for (tx in 0 until w step tile) {
            val tw = minOf(tile, w - tx)
            val th = minOf(tile, h - ty)
            target.resize(tw, th)
            target.bind()
            lensRenderer.draw(lensSourceTexture, w, h, lens, tx, ty)
            readback.clear()
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readback)
            GlUtil.checkError("lens tile $tx,$ty")
            readback.position(0)
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(readback)
            canvas.drawBitmap(out, tx.toFloat(), ty.toFloat(), paint)
            out.recycle()
        }
        // Free the full-size texture memory until the next photo.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensSourceTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
    }

    private fun ensureGl() {
        if (egl != null) return
        val core = EglCore()
        try {
            pbuffer = core.createPbufferSurface()
            core.makeCurrent(pbuffer)
            film = FilmRenderer()
            lensRenderer = LensRenderer()
            lensSourceTexture = GlUtil.texture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            target = GlUtil.RenderTarget()
            inputTexture = GlUtil.texture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
            val max = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
            tileLimit = max[0].coerceAtLeast(256)
            GlUtil.checkError("develop init")
            egl = core
        } catch (t: Throwable) {
            runCatching { core.release() }
            throw t
        }
    }

    fun release() {
        glThread.execute {
            egl?.let { core ->
                runCatching {
                    core.makeCurrent(pbuffer)
                    film.release(); lensRenderer.release(); target.release()
                    GLES30.glDeleteTextures(2, intArrayOf(inputTexture, lensSourceTexture), 0)
                    core.releaseSurface(pbuffer)
                }
                core.release()
            }
            egl = null
        }
        glThread.shutdown()
    }
}
