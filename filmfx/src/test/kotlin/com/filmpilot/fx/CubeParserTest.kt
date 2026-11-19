package com.filmpilot.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CubeParserTest {
    private val identity2 = """
        # comment
        TITLE "Tiny"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test fun parsesHeaderAndData() {
        val lut = CubeParser.parse(identity2)
        assertEquals("Tiny", lut.title)
        assertEquals(2, lut.size)
        val out = FloatArray(3)
        lut.sample(0.25f, 0.5f, 0.75f, out)
        assertEquals(0.25f, out[0], 1e-5f)
        assertEquals(0.5f, out[1], 1e-5f)
        assertEquals(0.75f, out[2], 1e-5f)
    }

    @Test fun redChangesFastest() {
        // A LUT that maps everything to its input red only in the red output channel.
        val lut = CubeParser.parse(identity2)
        val out = FloatArray(3)
        lut.sample(1f, 0f, 0f, out)
        assertEquals(listOf(1f, 0f, 0f), out.toList())
    }

    @Test fun fallbackTitleWhenMissing() {
        val lut = CubeParser.parse(identity2.replace("TITLE \"Tiny\"\n", ""), fallbackTitle = "file")
        assertEquals("file", lut.title)
    }

    @Test fun rejectsWrongEntryCount() {
        assertFailsWith<CubeFormatException> {
            CubeParser.parse(identity2.lines().dropLast(1).joinToString("\n"))
        }
    }

    @Test fun rejects1d() {
        assertFailsWith<CubeFormatException> { CubeParser.parse("LUT_1D_SIZE 4\n0 0 0") }
    }

    @Test fun parsesBundledSampleLut() {
        val file = java.io.File("../app/src/main/assets/luts").listFiles { f -> f.extension == "cube" }
        requireNotNull(file).forEach { CubeParser.parse(it.inputStream(), it.nameWithoutExtension) }
    }
}
