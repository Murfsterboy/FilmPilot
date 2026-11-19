package com.filmpilot.app.gl

import android.opengl.GLES30
import com.filmpilot.fx.FilmShaderSource
import com.filmpilot.fx.FilmUniforms
import com.filmpilot.fx.Lut3D
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws [FilmShaderSource] over the current viewport. Needs a current GL ES 3 context;
 * create, use and release it on that context's thread.
 */
class FilmRenderer {
    private val program = GlUtil.program(FilmShaderSource.VERTEX, FilmShaderSource.FRAGMENT)
    private val aPosition = GLES30.glGetAttribLocation(program, "aPosition")
    private val locations = HashMap<String, Int>()
    private val lutTexture = GlUtil.texture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
    private var uploadedLut: Lut3D? = null

    private fun loc(name: String) = locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /**
     * Applies the film look to [inputTexture] (a GL_TEXTURE_2D the same size as the viewport),
     * which sits at ([tileX], [tileY]) inside the image that [u] describes.
     */
    fun draw(inputTexture: Int, u: FilmUniforms, tileX: Int = 0, tileY: Int = 0) {
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(loc("uImage"), 0)

        val lut = u.lut
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(loc("uLut"), 1)
        GLES30.glUniform1i(loc("uUseLut"), if (lut != null) 1 else 0)
        if (lut != null) {
            if (lut !== uploadedLut) upload(lut)
            GLES30.glUniform3f(loc("uLutMin"), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
            GLES30.glUniform3f(
                loc("uLutInvRange"),
                1f / (lut.domainMax[0] - lut.domainMin[0]),
                1f / (lut.domainMax[1] - lut.domainMin[1]),
                1f / (lut.domainMax[2] - lut.domainMin[2]),
            )
            GLES30.glUniform1f(loc("uLutSize"), lut.size.toFloat())
        }

        GLES30.glUniform1i(loc("uUseExposure"), if (u.useExposure) 1 else 0)
        GLES30.glUniform1f(loc("uExposureGain"), u.exposureGain)
        GLES30.glUniform2f(loc("uCenter"), u.centerX, u.centerY)
        GLES30.glUniform1f(loc("uInvHalfDiagonal"), u.invHalfDiagonal)
        GLES30.glUniform1f(loc("uVignetteAmount"), u.vignetteAmount)
        GLES30.glUniform1f(loc("uGrainAmount"), u.grainAmount)
        GLES30.glUniform1f(loc("uInvGrainSize"), u.invGrainSize)
        GLES30.glUniform1i(loc("uGrainSeed"), u.grainSeed)
        GLES30.glUniform2i(loc("uTileOrigin"), tileX, tileY)

        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, GlUtil.QUAD)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
    }

    private fun upload(lut: Lut3D) {
        val data = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(lut.data).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, data,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GlUtil.checkError("LUT upload")
        uploadedLut = lut
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
    }
}
