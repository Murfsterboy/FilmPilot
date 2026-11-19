package com.filmpilot.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object GlUtil {
    /** Full-screen quad as a triangle strip: (x, y) in clip space. */
    val QUAD: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    /** Matching texture coordinates (s, t). */
    val QUAD_TEX: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    fun floatBuffer(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    fun program(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            error("Program link failed: $log")
        }
        return p
    }

    private fun shader(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            error("Shader compile failed: $log")
        }
        return s
    }

    fun texture(target: Int, filter: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(target, t[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun checkError(where: String) {
        val e = GLES30.glGetError()
        check(e == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} at $where" }
    }

    /** An RGBA8 texture plus a framebuffer that renders into it. */
    class RenderTarget {
        val texture = texture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        val framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        var width = 0; private set
        var height = 0; private set

        fun resize(w: Int, h: Int) {
            if (w == width && h == height) return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${Integer.toHexString(status)}" }
            width = w; height = h
        }

        fun bind() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, width, height)
        }

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
}
