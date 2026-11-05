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

    @Test
    fun missingIndicesAreRejectedAndKeepSourceText() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("One", "Two", "Three", "Four"),
            targetLanguage = "portuguese",
            rawText = """["0: Um", "2: Tres"]""",
            NL = NL
        )
        assertTrue(result.success)
        assertEquals(listOf("Um", "Two", "Tres", "Four"), result.lines)
        assertEquals(setOf(1, 3), result.rejected)
    }

    @Test
    fun fullyAlignedResponseHasNoRejections() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("One", "Two"),
            targetLanguage = "portuguese",
            rawText = """["0: Um", "1: Dois"]""",
            NL = NL
        )
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun unprefixedArrayOfDifferentLengthIsNotMappedPositionally() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("One", "Two", "Three"),
            targetLanguage = "portuguese",
            rawText = """["Um", "Dois"]""",
            NL = NL
        )
        assertFalse(result.success)
    }

    @Test
    fun tailCutMidElementDropsThatElementInsteadOfClosingIt() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("One", "Two", "Where were you"),
            targetLanguage = "portuguese",
            rawText = """["0: Um", "1: Dois", "2: Onde voc""",
            NL = NL
        )
        assertTrue(result.success)
        assertEquals(listOf("Um", "Dois", "Where were you"), result.lines)
        assertEquals(setOf(2), result.rejected)
    }

    @Test
    fun tailMissingOnlyClosingBracketKeepsEveryElement() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("One", "Two"),
            targetLanguage = "portuguese",
            rawText = """["0: Um", "1: Dois",""",
            NL = NL
        )
        assertEquals(listOf("Um", "Dois"), result.lines)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun tailCutRespectsEscapedQuotes() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("He said \"hi\"", "Bye"),
            targetLanguage = "portuguese",
            rawText = """["0: Ele disse \"oi\"", "1: Tch""",
            NL = NL
        )
        assertEquals("Ele disse \"oi\"", result.lines[0])
        assertEquals(setOf(1), result.rejected)
    }

    @Test
    fun singleLineCutMidStringIsAFailure() {
        val result = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = listOf("Where were you"),
            targetLanguage = "portuguese",
            rawText = """["0: (PT) Where were""",
            NL = NL
        )
        assertFalse(result.success)
    }

    private fun parseObjects(raw: String, lines: List<String> = listOf("a", "b", "c")) =
        SubtitleAiResponseJsonParser.parseTranslationResult(lines, "portuguese", raw, NL)

    @Test
    fun objectArrayInOrder() {
        val r = parseObjects("""[{"i":0,"t":"A"},{"i":1,"t":"B"},{"i":2,"t":"C$NL""" + """D"}]""")
        assertTrue(r.success)
        assertEquals(listOf("A", "B", "C\nD"), r.lines)
        assertTrue(r.rejected.isEmpty())
    }

    @Test
    fun objectArrayOutOfOrderIsMappedById() {
        val r = parseObjects("""[{"i":2,"t":"C"},{"i":0,"t":"A"},{"i":1,"t":"B"}]""")
        assertEquals(listOf("A", "B", "C"), r.lines)
    }

    @Test
    fun objectArrayMissingIdIsRejected() {
        val r = parseObjects("""[{"i":0,"t":"A"},{"i":2,"t":"C"}]""")
        assertTrue(r.success)
        assertEquals(setOf(1), r.rejected)
        assertEquals("A", r.lines[0])
        assertEquals("C", r.lines[2])
    }

    @Test
    fun objectArrayExtraAndDuplicateIdsAreIgnored() {
        val r = parseObjects("""[{"i":0,"t":"A"},{"i":0,"t":"X"},{"i":9,"t":"Z"},{"i":1,"t":"B"},{"i":2,"t":"C"}]""")
        assertEquals(listOf("A", "B", "C"), r.lines)
        assertTrue(r.rejected.isEmpty())
    }

    @Test
    fun objectArrayKeepsBracketedDialogue() {
        val r = parseObjects("""[{"i":0,"t":"[Música]"},{"i":1,"t":"B"},{"i":2,"t":"C"}]""")
        assertEquals("[Música]", r.lines[0])
    }

    @Test
    fun objectArrayToleratesFence() {
        val fenced = parseObjects("```json\n[{\"i\":0,\"t\":\"A\"},{\"i\":1,\"t\":\"B\"},{\"i\":2,\"t\":\"C\"}]\n```")
        assertEquals(listOf("A", "B", "C"), fenced.lines)
    }
}
