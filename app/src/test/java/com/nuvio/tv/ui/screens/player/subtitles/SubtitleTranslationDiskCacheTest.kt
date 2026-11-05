package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubtitleTranslationDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(
        promptVersion: Int = 1,
        maxFiles: Int = 300,
        maxEntries: Int = 20_000
    ) = SubtitleTranslationDiskCache(
        rootDir = File(tmp.root, "cache"),
        promptVersion = promptVersion,
        maxFiles = maxFiles,
        maxEntriesPerFile = maxEntries
    )

    private fun tsvFiles(): List<File> =
        File(tmp.root, "cache").listFiles { f -> f.name.endsWith(".tsv") }?.toList().orEmpty()

    @Test
    fun `round trip persists across instances`() = runBlocking {
        val a = cache()
        a.put("src", "pt", "Hello", "Ola")
        assertEquals("Ola", a.get("src", "pt", "Hello"))
        a.flush()

        val b = cache()
        b.preload("src", "pt")
        assertEquals("Ola", b.get("src", "pt", "Hello"))
        assertNull(b.get("src", "pt", "Other"))
    }

    @Test
    fun `separated by language promptVersion and sourceKey`() = runBlocking {
        val a = cache(promptVersion = 1)
        a.put("src", "pt", "Hello", "Ola")
        a.flush()

        val same = cache(promptVersion = 1)
        assertEquals("Ola", same.get("src", "pt", "Hello"))
        assertNull(same.get("src", "es", "Hello"))
        assertNull(same.get("other", "pt", "Hello"))
        assertNull(cache(promptVersion = 2).get("src", "pt", "Hello"))
    }

    @Test
    fun `escaping of newlines tabs and backslashes`() = runBlocking {
        val tricky = "line1\nline2\r\n\tTabbed \\n literal \\ end\\"
        val a = cache()
        a.put("src", "pt", "Hi", tricky)
        a.flush()
        assertEquals(tricky, cache().get("src", "pt", "Hi"))
        assertEquals(1, tsvFiles().single().readLines().size)
    }

    @Test
    fun `corrupt and partial lines are skipped`() = runBlocking {
        val a = cache()
        a.put("src", "pt", "Good", "Bom")
        a.flush()
        val file = tsvFiles().single()
        file.appendText("garbage without tab\n")
        file.appendText("short\tbad hash\n")
        file.appendText("0123456789012345678901234567890123456789\tbad escape \\x\n")
        file.appendText("0123456789012345678901234567890123456789\tpartial no newline")

        val b = cache()
        assertEquals("Bom", b.get("src", "pt", "Good"))
        // Appending after a partial line must not corrupt the new entry.
        b.put("src", "pt", "Next", "Proximo")
        b.flush()
        val c = cache()
        assertEquals("Bom", c.get("src", "pt", "Good"))
        assertEquals("Proximo", c.get("src", "pt", "Next"))
    }

    @Test
    fun `unreadable cache dir yields empty and never throws`() = runBlocking {
        val blocker = File(tmp.root, "cache")
        blocker.writeText("i am a file, not a dir")
        val a = cache()
        assertNull(a.get("src", "pt", "Hello"))
        a.put("src", "pt", "Hello", "Ola")
        a.flush()
        assertEquals("Ola", a.get("src", "pt", "Hello"))
    }

    @Test
    fun `last write wins`() = runBlocking {
        val a = cache()
        a.put("src", "pt", "Hello", "first")
        a.put("src", "pt", "Hello", "second")
        a.flush()
        assertEquals("second", a.get("src", "pt", "Hello"))
        assertEquals("second", cache().get("src", "pt", "Hello"))
    }

    @Test
    fun `blank inputs ignored`() = runBlocking {
        val a = cache()
        a.put("src", "pt", "  ", "x")
        a.put("src", "pt", "x", "")
        a.flush()
        assertTrue(tsvFiles().isEmpty())
        assertNull(a.get("src", "pt", " "))
    }

    @Test
    fun `evicts least recently modified files beyond maxFiles`() = runBlocking {
        val a = cache(maxFiles = 2)
        a.put("a", "pt", "t", "A")
        a.flush()
        tsvFiles().forEach { it.setLastModified(1_000_000L) }
        a.put("b", "pt", "t", "B")
        a.flush()
        tsvFiles().filter { it.lastModified() != 1_000_000L }.forEach { it.setLastModified(2_000_000L) }
        a.put("c", "pt", "t", "C")
        a.flush()

        assertEquals(2, tsvFiles().size)
        val fresh = cache(maxFiles = 2)
        assertNull(fresh.get("a", "pt", "t"))
        assertEquals("B", fresh.get("b", "pt", "t"))
        assertEquals("C", fresh.get("c", "pt", "t"))
    }

    @Test
    fun `compaction rewrites unique latest entries`() = runBlocking {
        val a = cache(maxEntries = 5)
        repeat(20) { a.put("src", "pt", "same", "v$it") }
        a.put("src", "pt", "other", "o")
        a.flush()

        val lines = tsvFiles().single().readLines()
        assertTrue("expected compacted file, got ${lines.size} lines", lines.size <= 5)
        val fresh = cache(maxEntries = 5)
        assertEquals("v19", fresh.get("src", "pt", "same"))
        assertEquals("o", fresh.get("src", "pt", "other"))
    }

    @Test
    fun `peek is IO free and only sees loaded indexes`() = runBlocking {
        val a = cache()
        a.put("src", "pt", "Hello", "Ola")
        a.flush()

        val b = cache()
        assertNull(b.peek("src", "pt", "Hello"))
        b.preload("src", "pt")
        assertEquals("Ola", b.peek("src", "pt", "Hello"))
        assertNull(b.peek("src", "pt", "Nope"))
    }
}
