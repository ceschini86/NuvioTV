package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleBatchValidatorTest {

    private fun ok(lines: List<String>, rejected: Set<Int> = emptySet()) =
        TranslationResult(lines, true, rejected = rejected)

    @Test
    fun failedResultReturnedAsIs() {
        val r = TranslationResult(listOf("a"), false, "boom")
        assertSame(r, SubtitleBatchValidator.validate(listOf("a"), r))
    }

    @Test
    fun cleanBatchIsUntouched() {
        val src = listOf("Where are you going?", "Home.")
        val r = ok(listOf("Para onde voce vai?", "Para casa."))
        val v = SubtitleBatchValidator.validate(src, r)
        assertTrue(v.rejected.isEmpty())
        assertEquals(r.lines, v.lines)
    }

    @Test
    fun preexistingRejectedKept() {
        val src = listOf("Hello there my friend", "Another line here")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("Ola meu amigo", "Another line here"), setOf(1)))
        assertEquals(setOf(1), v.rejected)
    }

    @Test
    fun blankTranslationRejected() {
        val src = listOf("Hello there", "   ")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("  ", "  ")))
        assertEquals(setOf(0), v.rejected)
        assertEquals("Hello there", v.lines[0])
    }

    @Test
    fun echoWithThreeWordsRejected() {
        val src = listOf("I will find you", "Come back soon")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("I will find you!", "Volte logo")))
        assertEquals(setOf(0), v.rejected)
        assertEquals("I will find you", v.lines[0])
        assertEquals("Volte logo", v.lines[1])
    }

    @Test
    fun shortIdenticalLinesAreLegit() {
        val src = listOf("Rick!", "OK.", "Daryl?", "Where are you going now?")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("Rick!", "OK.", "Daryl?", "Para onde voce vai agora?")))
        assertTrue(v.rejected.isEmpty())
    }

    @Test
    fun identicalNameListIsLegit() {
        // Real TV case: rejected 9 times, then given up (Poldark S05E02).
        val src = listOf("Poldark, Despard, Enys, Carne.", "I WILL FIND YOU", "Where are you going now?")
        val tr = listOf("Poldark, Despard, Enys, Carne.", "I WILL FIND YOU", "Para onde voce vai agora?")
        val v = SubtitleBatchValidator.validate(src, ok(tr))
        assertEquals(setOf(1), v.rejected)
    }

    @Test
    fun echoNormalizationIgnoresCaseMarksAndPunctuation() {
        val src = listOf("Don't you dare")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("‏don't  YOU dare...‏")))
        assertEquals(setOf(0), v.rejected)
    }

    @Test
    fun batchLevelEchoRejectsTwoWordIdenticalLines() {
        val src = listOf("Thank you", "Come on", "Where are you going", "Let me go")
        val tr = listOf("Thank you", "Come on", "Para onde voce vai", "Let me go")
        // 4 two+-word lines, 3 echoes (>= half) -> two-word identical lines rejected too.
        val v = SubtitleBatchValidator.validate(src, ok(tr))
        assertEquals(setOf(0, 1, 3), v.rejected)
    }

    @Test
    fun twoWordIdenticalLineAloneIsLegit() {
        val src = listOf("Thank you", "Where are you going", "Let me tell you")
        val tr = listOf("Thank you", "Para onde voce vai", "Deixe-me contar")
        assertTrue(SubtitleBatchValidator.validate(src, ok(tr)).rejected.isEmpty())
    }

    @Test
    fun refusalTextRejected() {
        val src = listOf("Kill him now", "Hello")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("I'm sorry, but I can't help with that", "Ola")))
        assertEquals(setOf(0), v.rejected)
        assertEquals("Kill him now", v.lines[0])
    }

    @Test
    fun refusalPhraseInSourceIsFine() {
        val src = listOf("As an AI, I know")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("Como uma IA, as an AI eu sei")))
        assertTrue(v.rejected.isEmpty())
    }

    @Test
    fun runawayOutputRejected() {
        val src = listOf("Hi", "Fine")
        val v = SubtitleBatchValidator.validate(src, ok(listOf("x".repeat(60), "Bem")))
        assertEquals(setOf(0), v.rejected)
    }

    @Test
    fun mixedBatch() {
        val src = listOf("Rick!", "Get out of here", "Run", "Stay with me", "   ", "I am done")
        val tr = listOf("Rick!", "Get out of here", "", "Fique comigo", "", "Estou farto")
        val v = SubtitleBatchValidator.validate(src, ok(tr))
        assertEquals(setOf(1, 2), v.rejected)
        assertFalse(v.rejected.contains(0))
        assertEquals(listOf("Rick!", "Get out of here", "Run", "Fique comigo", "", "Estou farto"), v.lines)
        assertTrue(v.success)
    }
}
