package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleAiKeyValidationTest {

    @Test
    fun emptyKeyRejected() {
        assertEquals(
            SubtitleAiKeyFormatError.EMPTY,
            validateSubtitleAiApiKeyFormat(SubtitleAiModel.GROQ_LLAMA_70B, "   ")
        )
    }

    @Test
    fun whitespaceRejected() {
        assertEquals(
            SubtitleAiKeyFormatError.WHITESPACE,
            validateSubtitleAiApiKeyFormat(SubtitleAiModel.GROQ_LLAMA_70B, "gsk_abc def_1234567890")
        )
    }

    @Test
    fun tooShortRejected() {
        assertEquals(
            SubtitleAiKeyFormatError.TOO_SHORT,
            validateSubtitleAiApiKeyFormat(SubtitleAiModel.GEMINI_FLASH_25, "AIzaShort")
        )
    }

    @Test
    fun groqAcceptsGskPrefix() {
        assertNull(
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.GROQ_LLAMA_70B,
                "gsk_" + "a".repeat(20)
            )
        )
    }

    @Test
    fun geminiAcceptsAizaPrefix() {
        assertNull(
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.GEMINI_FLASH_25,
                "AIza" + "b".repeat(20)
            )
        )
    }

    @Test
    fun claudeAcceptsSkAntPrefix() {
        assertNull(
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.CLAUDE_HAIKU,
                "sk-ant-" + "c".repeat(20)
            )
        )
    }

    @Test
    fun wrongProviderPrefixRejected() {
        assertEquals(
            SubtitleAiKeyFormatError.WRONG_PREFIX,
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.GROQ_LLAMA_70B,
                "AIza" + "d".repeat(20)
            )
        )
        assertEquals(
            SubtitleAiKeyFormatError.WRONG_PREFIX,
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.GEMINI_FLASH_25,
                "gsk_" + "e".repeat(20)
            )
        )
        assertEquals(
            SubtitleAiKeyFormatError.WRONG_PREFIX,
            validateSubtitleAiApiKeyFormat(
                SubtitleAiModel.CLAUDE_HAIKU,
                "gsk_" + "f".repeat(20)
            )
        )
    }
}
