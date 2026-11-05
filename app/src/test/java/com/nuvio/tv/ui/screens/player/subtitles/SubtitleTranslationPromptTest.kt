package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationPromptTest {

    private val input = """["0: Hello","1: World"]"""

    @Test
    fun noContextIsIdenticalToLegacyFormat() {
        assertEquals(
            "Translate to portuguese:\n$input",
            buildTranslateUserMessage(input, "portuguese", emptyList())
        )
    }

    @Test
    fun contextBlockPrecedesUnchangedTranslateBlock() {
        val msg = buildTranslateUserMessage(input, "portuguese", listOf("Previous \"one\"", "Two"))
        assertTrue(msg.startsWith("Context (previous lines, do NOT translate or output):\n[\"Previous \\\"one\\\"\",\"Two\"]\n\n"))
        assertTrue(msg.endsWith("Translate to portuguese:\n$input"))
    }

    @Test
    fun promptVersionBumped() {
        assertEquals(3, SUBTITLE_PROMPT_VERSION)
    }

    @Test
    fun geminiThrottleIsPerKey() = runBlocking {
        val svc = SubtitleTranslationService()
        svc.geminiMinIntervalMs = 1000L
        assertEquals(0L, svc.geminiReadyInMs("keyA", 5_000L))
        assertEquals(0L, svc.reserveGeminiSlot("keyA", 5_000L))
        assertEquals(1000L, svc.geminiReadyInMs("keyA", 5_000L))
        assertEquals(400L, svc.geminiReadyInMs("keyA", 5_600L))
        // Another key is unaffected.
        assertEquals(0L, svc.geminiReadyInMs("keyB", 5_000L))
        assertEquals(0L, svc.reserveGeminiSlot("keyB", 5_000L))
        // Second reservation on keyA queues behind the first.
        assertEquals(1000L, svc.reserveGeminiSlot("keyA", 5_000L))
        assertEquals(2000L, svc.geminiReadyInMs("keyA", 5_000L))
        Unit
    }
}
