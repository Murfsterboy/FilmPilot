package com.filmpilot.fx

import java.io.BufferedReader
import java.io.InputStream

/** Thrown when a `.cube` file can't be understood. */
class CubeFormatException(message: String) : Exception(message)

/**
 * Parses the Adobe/Resolve `.cube` 3D LUT format:
 *
 * ```
 * TITLE "My Film"          (optional)
 * LUT_3D_SIZE 33           (required)
 * DOMAIN_MIN 0 0 0         (optional)
 * DOMAIN_MAX 1 1 1         (optional)
 * 0.0123 0.0045 0.0101     size³ lines, red changes fastest
 * ...
 * ```
 * Comments (`#`) and blank lines are ignored. 1D LUTs (`LUT_1D_SIZE`) are rejected.
 */
object CubeParser {

    fun parse(input: InputStream, fallbackTitle: String = "Untitled"): Lut3D =
        input.bufferedReader().use { parse(it, fallbackTitle) }

    fun parse(text: String, fallbackTitle: String = "Untitled"): Lut3D =
        parse(text.reader().buffered(), fallbackTitle)

    fun parse(reader: BufferedReader, fallbackTitle: String = "Untitled"): Lut3D {
        var title: String? = null
        var size = -1
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0
        var lineNo = 0

        reader.forEachLine { raw ->
            lineNo++
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine

            val first = line[0]
            if (first.isDigit() || first == '-' || first == '.' || first == '+') {
                val d = data ?: throw CubeFormatException("Line $lineNo: data before LUT_3D_SIZE")
                if (count * 3 >= d.size) throw CubeFormatException("Line $lineNo: more entries than LUT_3D_SIZE³")
                val parts = line.split(WHITESPACE)
                if (parts.size < 3) throw CubeFormatException("Line $lineNo: expected 3 numbers")
                for (c in 0 until 3) {
                    d[count * 3 + c] = parts[c].toFloatOrNull()
                        ?: throw CubeFormatException("Line $lineNo: '${parts[c]}' is not a number")
                }
                count++
                return@forEachLine
            }

            val key = line.substringBefore(' ').substringBefore('\t').uppercase()
            val value = line.substring(key.length).trim()
            when (key) {
                "TITLE" -> title = value.trim('"')
                "LUT_3D_SIZE" -> {
                    size = value.toIntOrNull()?.takeIf { it in 2..256 }
                        ?: throw CubeFormatException("Line $lineNo: bad LUT_3D_SIZE '$value'")
                    data = FloatArray(size * size * size * 3)
                }
                "LUT_1D_SIZE" -> throw CubeFormatException("1D LUTs are not supported, use a 3D .cube")
                "DOMAIN_MIN" -> readTriplet(value, domainMin, lineNo)
                "DOMAIN_MAX" -> readTriplet(value, domainMax, lineNo)
                "LUT_3D_INPUT_RANGE" -> {
                    val (lo, hi) = value.split(WHITESPACE).map {
                        it.toFloatOrNull() ?: throw CubeFormatException("Line $lineNo: bad range")
                    }
                    domainMin.fill(lo); domainMax.fill(hi)
                }
                else -> Unit // Unknown keywords are ignored, as most tools do.
            }
        }

        val d = data ?: throw CubeFormatException("Missing LUT_3D_SIZE")
        val expected = size * size * size
        if (count != expected) throw CubeFormatException("Expected $expected entries, found $count")
        return Lut3D(title?.takeIf { it.isNotBlank() } ?: fallbackTitle, size, d, domainMin, domainMax)
    }

    private fun readTriplet(value: String, into: FloatArray, lineNo: Int) {
        val parts = value.split(WHITESPACE)
        if (parts.size < 3) throw CubeFormatException("Line $lineNo: expected 3 numbers")
        for (c in 0 until 3) into[c] = parts[c].toFloatOrNull()
            ?: throw CubeFormatException("Line $lineNo: '${parts[c]}' is not a number")
    }

    private val WHITESPACE = Regex("\\s+")
}
