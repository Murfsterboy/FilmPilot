package com.filmpilot.app.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.FilmUniforms
import java.util.concurrent.Executor

/**
 * Live film viewfinder. CameraX sends camera frames here (through [FilmPreviewEffect]);
 * each frame is drawn twice on the GPU:
 *  1. camera texture (external OES) → RGBA texture, applying CameraX's crop/rotation transform;
 *  2. film shader → the preview output surface.
 *
 * Everything runs on a dedicated GL thread, which is also the executor CameraX calls us on.
 * Update [params] from any thread; the next frame uses them.
 */
class FilmSurfaceProcessor(private val onError: (Throwable) -> Unit) : SurfaceProcessor {
    private val thread = HandlerThread("FilmPilot-LiveGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    @Volatile var params: FilmParams = FilmParams(grain = 0f, vignette = 0f)

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private lateinit var film: FilmRenderer
    private lateinit var copy: CameraCopyProgram
    private lateinit var target: GlUtil.RenderTarget
    private var cameraTexture = 0
    private var initError: Throwable? = null

    private var input: SurfaceTexture? = null
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var frames = 0
    private var released = false
    private var failedFrames = 0
    private var errorReported = false

    init {
        handler.post {
            try {
                egl = EglCore()
                pbuffer = egl.createPbufferSurface()
                egl.makeCurrent(pbuffer)
                film = FilmRenderer()
                copy = CameraCopyProgram()
                target = GlUtil.RenderTarget()
                cameraTexture = GlUtil.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
                GlUtil.checkError("live init")
            } catch (t: Throwable) {
                Log.e(TAG, "Live film preview: GL init failed", t)
                initError = t
            }
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        initError?.let { request.willNotProvideSurface(); reportError(it); return }
        if (released) { request.willNotProvideSurface(); return }
        val st = SurfaceTexture(cameraTexture)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            if (input === st) input = null
        }
        st.setOnFrameAvailableListener({ onFrame(it) }, handler)
        input = st
        Log.i(TAG, "Live film preview started: camera ${request.resolution}")
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released || initError != null) { output.close(); return }
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { egl.releaseSurface(it) }
            output.close()
        }
        outputs[output] = egl.createWindowSurface(surface)
    }

    private fun onFrame(st: SurfaceTexture) {
        if (released || st !== input) return
        try {
            egl.makeCurrent(pbuffer)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val p = params.copy(grainSeed = frames++) // grain dances frame to frame, like film
            for ((out, eglSurface) in outputs) {
                egl.makeCurrent(eglSurface)
                out.updateTransformMatrix(texMatrix, stMatrix)
                val w = out.size.width
                val h = out.size.height

                target.resize(w, h)
                target.bind()
                copy.draw(cameraTexture, texMatrix)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, w, h)
                film.draw(target.texture, FilmUniforms(p, w, h))
                egl.swapBuffers(eglSurface, st.timestamp)
            }
            failedFrames = 0
        } catch (t: Throwable) {
            Log.e(TAG, "Live film preview frame failed", t)
            if (++failedFrames >= 10) reportError(t)
        }
    }

    /** Tells the UI once, so it can fall back to the plain preview. */
    private fun reportError(t: Throwable) {
        if (errorReported) return
        errorReported = true
        onError(t)
    }

    /** Frees GL resources and stops the thread. */
    fun release() {
        handler.post {
            if (released) return@post
            released = true
            input?.release(); input = null
            if (initError == null) {
                for ((out, s) in outputs) { egl.releaseSurface(s); out.close() }
                outputs.clear()
                runCatching {
                    egl.makeCurrent(pbuffer)
                    film.release(); copy.release(); target.release()
                    GLES30.glDeleteTextures(1, intArrayOf(cameraTexture), 0)
                    egl.releaseSurface(pbuffer)
                    egl.release()
                }
            }
            thread.quitSafely()
        }
    }

    private companion object { const val TAG = "FilmPilot" }
}

/** Hooks [FilmSurfaceProcessor] into CameraX for the preview stream. */
class FilmPreviewEffect(processor: FilmSurfaceProcessor, onError: (Throwable) -> Unit) :
    CameraEffect(CameraEffect.PREVIEW, processor.executor, processor, { onError(it) })

/** Copies the camera's external texture into a regular texture, applying CameraX's transform. */
private class CameraCopyProgram {
    private val program = GlUtil.program(
        """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTex;
        void main() { gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }
        """.trimIndent(),
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uCamera;
        varying vec2 vTex;
        void main() { gl_FragColor = texture2D(uCamera, vTex); }
        """.trimIndent(),
    )
    private val aPosition = GLES30.glGetAttribLocation(program, "aPosition")
    private val aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
    private val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
    private val uCamera = GLES30.glGetUniformLocation(program, "uCamera")

    fun draw(cameraTexture: Int, texMatrix: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1i(uCamera, 0)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, GlUtil.QUAD)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, GlUtil.QUAD_TEX)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() = GLES30.glDeleteProgram(program)
}
