package com.filmpilot.fx

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Writes everything needed to run [FilmShaderSource] outside Android (shader sources,
 * test image, LUT, uniforms) plus the CPU result, so a WebGL2 harness can compare
 * GPU and CPU output. Off by default; run with FX_SHADER_DUMP_DIR=/some/dir.
 */
class ShaderDump {
    @Test fun dump() {
        val dir = File(System.getenv("FX_SHADER_DUMP_DIR") ?: return).apply { mkdirs() }
        val w = 640; val h = 480
        val src = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val r = (x * 255 / (w - 1)); val g = (y * 255 / (h - 1)); val b = ((x xor y) and 0xFF)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val lut = File("../app/src/main/assets/luts/fp_warm_400.cube").inputStream().use { CubeParser.parse(it) }
        val cases = mapOf(
            "lut" to FilmParams(lut, grain = 0f, vignette = 0f),
            "all" to FilmParams(lut, exposureEv = 2f / 3f, grain = 0.6f, vignette = 0.5f, grainSeed = 42),
            "minus2" to FilmParams(lut, exposureEv = -2f, grain = 1f, vignette = 1f, grainSeed = -7),
            "noLut" to FilmParams(null, exposureEv = 1f, grain = 0.3f, vignette = 0.2f, grainSeed = 123456789),
        )
        File(dir, "film.frag").writeText(FilmShaderSource.FRAGMENT)
        File(dir, "film.vert").writeText(FilmShaderSource.VERTEX)
        File(dir, "input.rgba").writeBytes(toRgba(src))
        val lb = ByteBuffer.allocate(lut.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        lut.data.forEach { lb.putFloat(it) }
        File(dir, "lut.f32").writeBytes(lb.array())

        val json = StringBuilder("{\"width\":$w,\"height\":$h,\"lutSize\":${lut.size},\"cases\":{")
        cases.entries.forEachIndexed { i, (name, p) ->
            val px = src.copyOf()
            FilmPipeline(p).process(px, w, h)
            File(dir, "expected-$name.rgba").writeBytes(toRgba(px))
            val u = FilmUniforms(p, w, h)
            if (i > 0) json.append(',')
            json.append("\"$name\":{\"useLut\":${u.lut != null},\"useExposure\":${u.useExposure},")
                .append("\"exposureGain\":${u.exposureGain},\"center\":[${u.centerX},${u.centerY}],")
                .append("\"invHalfDiagonal\":${u.invHalfDiagonal},\"vignetteAmount\":${u.vignetteAmount},")
                .append("\"grainAmount\":${u.grainAmount},\"invGrainSize\":${u.invGrainSize},\"grainSeed\":${u.grainSeed}}")
        }
        json.append("}}")
        File(dir, "uniforms.json").writeText(json.toString())
    }

    private fun toRgba(px: IntArray): ByteArray {
        val out = ByteArray(px.size * 4)
        px.forEachIndexed { i, c ->
            out[i * 4] = (c shr 16).toByte(); out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = c.toByte(); out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return out
    }
}
