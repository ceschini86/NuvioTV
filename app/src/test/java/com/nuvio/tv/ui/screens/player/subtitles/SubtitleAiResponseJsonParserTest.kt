package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAiResponseJsonParserTest {

    private val NL = "␤"

    @Test
    fun stripsMarkdownJsonFence() {
        val raw = """
            ```json
            ["0: Olá mundo", "1: Como vai?"]
            ```
        """.trimIndent()

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("Hello world", "How are you?"),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertTrue(result.success)
        assertEquals(listOf("Olá mundo", "Como vai?"), result.lines)
    }

    @Test
    fun stripsBareJsonBracketPrefix() {
        val raw = """json["0: Olá", "1: Tudo bem"]"""

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("Hello", "All good"),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertTrue(result.success)
        assertEquals(listOf("Olá", "Tudo bem"), result.lines)
        assertFalse(result.lines.any { it.contains("json", ignoreCase = true) })
    }

    @Test
    fun stripsMixedLanguagePreambleOutsideArray() {
        val raw = """
            Sure! Here's the Portuguese translation:
            Claro, aqui está:

            ["0: Eu não acredito nisso.", "1: Vamos embora."]
        """.trimIndent()

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("I can't believe this.", "Let's go."),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertTrue(result.success)
        assertEquals(listOf("Eu não acredito nisso.", "Vamos embora."), result.lines)
        assertFalse(result.lines.any { it.contains("Sure", ignoreCase = true) })
        assertFalse(result.lines.any { it.contains("Claro", ignoreCase = true) })
    }

    @Test
    fun rejectsBrokenJsonBracketAsParseFailure() {
        val original = "Hello there"
        val raw = "json[0: Olá aí"

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf(original),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        // Incomplete wrapper is not a valid array — fail closed (original, no cache of junk).
        assertFalse(result.success)
        assertEquals(listOf(original), result.lines)
    }

    @Test
    fun rejectsIrrecoverableJsonBracketJunk() {
        val original = "Hello there"
        val raw = "json[???###"

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf(original),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertFalse(result.success)
        assertEquals(listOf(original), result.lines)
    }

    @Test
    fun rejectsUnclosedMarkdownFenceJunkAsSingleLineFallback() {
        val original = "Stay original"
        val raw = "```json\njson[not a real array"

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf(original),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertFalse(result.success)
        assertEquals(listOf(original), result.lines)
    }

    @Test
    fun sanitizesJsonPrefixLeakedIntoArrayElement() {
        val raw = """["0: json[Olá]", "1: Mundo"]"""

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("Hello", "World"),
            targetLanguage = "portuguese",
            rawText = raw,
            NL = NL
        )

        assertTrue(result.success)
        assertEquals("Olá", result.lines[0])
        assertEquals("Mundo", result.lines[1])
    }

    @Test
    fun parsesCleanArrayUnchanged() {
        val raw = """["0: First", "1: Second"]"""

        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("A", "B"),
            targetLanguage = "english",
            rawText = raw,
            NL = NL
        )

        assertTrue(result.success)
        assertEquals(listOf("First", "Second"), result.lines)
    }

    @Test
    fun acceptsPlainSingleLineWithoutWrapper() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("Hello"),
            targetLanguage = "portuguese",
            rawText = "0: Olá",
            NL = NL
        )
        assertTrue(result.success)
        assertEquals(listOf("Olá"), result.lines)
    }

    @Test
    fun looksLikeJsonArtifactDetectsObservedJunk() {
        assertTrue(SubtitleAiResponseJsonParser.looksLikeJsonArtifact("json[\"0: x\"]"))
        assertTrue(SubtitleAiResponseJsonParser.looksLikeJsonArtifact("```json\n[\"x\"]\n```"))
        assertTrue(SubtitleAiResponseJsonParser.looksLikeJsonArtifact("[\"0: x\"]"))
        assertFalse(SubtitleAiResponseJsonParser.looksLikeJsonArtifact("Olá mundo"))
    }

    @Test
    fun extractJsonArrayIgnoresProseAndFenceWrappers() {
        val array = SubtitleAiResponseJsonParser.extractJsonArray(
            """
            Aqui vai em francês:
            ```json
            ["0: Bonjour", "1: Merci"]
            ```
            """.trimIndent()
        )
        requireNotNull(array)
        assertEquals(2, array.length())
        assertEquals("0: Bonjour", array.getString(0))
        assertEquals("1: Merci", array.getString(1))
    }
}
