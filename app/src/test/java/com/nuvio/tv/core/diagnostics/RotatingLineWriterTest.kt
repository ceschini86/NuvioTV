package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RotatingLineWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun read(name: String) = File(tmp.root, name).readText()

    @Test
    fun `each line is on disk before close`() {
        val writer = RotatingLineWriter(tmp.root, "d.log", maxBytes = 1_000, keep = 2)
        writer.writeLine("first")
        assertEquals("first\n", read("d.log"))
        writer.close()
    }

    @Test
    fun `rotates to numbered files with higher number older and drops past keep`() {
        // "lineN\n" is 6 bytes, so a 12-byte cap holds two lines per file.
        RotatingLineWriter(tmp.root, "d.log", maxBytes = 12, keep = 2).use { writer ->
            (1..7).forEach { writer.writeLine("line$it") }
        }
        assertEquals("line7\n", read("d.log"))
        assertEquals("line5\nline6\n", read("d.log.1"))
        assertEquals("line3\nline4\n", read("d.log.2"))
        assertFalse(File(tmp.root, "d.log.3").exists())
    }

    @Test
    fun `reopening appends to the existing file`() {
        RotatingLineWriter(tmp.root, "d.log", maxBytes = 1_000, keep = 2).use { it.writeLine("a") }
        RotatingLineWriter(tmp.root, "d.log", maxBytes = 1_000, keep = 2).use { it.writeLine("b") }
        assertEquals("a\nb\n", read("d.log"))
    }
}
