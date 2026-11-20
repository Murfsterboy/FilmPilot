package com.filmpilot.app.gl

import android.opengl.GLES30
import com.filmpilot.fx.FilmShaderSource
import com.filmpilot.fx.LensParams
import com.filmpilot.fx.LensShaderSource

/**
 * Draws [LensShaderSource.DEVELOP_FRAGMENT]: lens optics for one output tile of a photo whose
 * full source is in a GL_TEXTURE_2D (linear filtering, clamp to edge). Needs a current GL ES 3
 * context; create, use and release it on that context's thread.
 */
class LensRenderer {
    private val program = GlUtil.program(FilmShaderSource.VERTEX, LensShaderSource.DEVELOP_FRAGMENT)
    private val aPosition = GLES30.glGetAttribLocation(program, "aPosition")
    private val uSource = GLES30.glGetUniformLocation(program, "uSource")
    private val uImageSize = GLES30.glGetUniformLocation(program, "uImageSize")
    private val uTileOrigin = GLES30.glGetUniformLocation(program, "uTileOrigin")
    private val uDistortion = GLES30.glGetUniformLocation(program, "uDistortion")
    private val uChromatic = GLES30.glGetUniformLocation(program, "uChromatic")
    private val uBlur = GLES30.glGetUniformLocation(program, "uBlur")

    fun draw(sourceTexture: Int, width: Int, height: Int, lens: LensParams, tileX: Int, tileY: Int) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
        GLES30.glUniform1i(uSource, 0)
        GLES30.glUniform2f(uImageSize, width.toFloat(), height.toFloat())
        GLES30.glUniform2i(uTileOrigin, tileX, tileY)
        GLES30.glUniform1f(uDistortion, lens.distortion)
        GLES30.glUniform1f(uChromatic, lens.chromatic)
        GLES30.glUniform1f(uBlur, lens.blur)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, GlUtil.QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
    }

    fun release() = GLES30.glDeleteProgram(program)
}
