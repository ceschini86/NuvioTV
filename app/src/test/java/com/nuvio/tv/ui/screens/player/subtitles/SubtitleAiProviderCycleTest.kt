package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAiProviderCycleTest {

    private fun creds(
        vararg entries: Pair<SubtitleAiModel, Pair<Boolean, List<String>>>
    ): SubtitleAiCredentials {
        val byModel = entries.toMap()
        return SubtitleAiCredentials(
            providers = SubtitleAiModel.entries.map { model ->
                val (enabled, keys) = byModel[model] ?: (false to emptyList())
                SubtitleAiProviderCredentials(model = model, enabled = enabled, keys = keys)
            }
        )
    }

    @Test
    fun `visibility hidden when zero eligible providers`() {
        val empty = SubtitleAiCredentials()
        assertEquals(emptyList<SubtitleAiModel>(), eligibleSubtitleAiModels(empty))
        assertFalse(shouldShowSubtitleAiProviderCycleCta(empty))
        assertNull(nextEligibleSubtitleAiModel(SubtitleAiModel.GROQ_LLAMA_70B, empty))
    }

    @Test
    fun `visibility hidden when only one eligible provider`() {
        val one = creds(
            SubtitleAiModel.GROQ_LLAMA_70B to (true to listOf("gsk_test_key_1234567890")),
            SubtitleAiModel.GEMINI_FLASH_25 to (true to emptyList()),
            SubtitleAiModel.CLAUDE_HAIKU to (false to listOf("sk-ant-test-key-1234567890"))
        )
        assertEquals(listOf(SubtitleAiModel.GROQ_LLAMA_70B), eligibleSubtitleAiModels(one))
        assertFalse(shouldShowSubtitleAiProviderCycleCta(one))
        assertNull(nextEligibleSubtitleAiModel(SubtitleAiModel.GROQ_LLAMA_70B, one))
    }

    @Test
    fun `visibility shown when two or more eligible providers`() {
        val two = creds(
            SubtitleAiModel.GROQ_LLAMA_70B to (true to listOf("gsk_a")),
            SubtitleAiModel.GEMINI_FLASH_25 to (true to listOf("AIza_b")),
            SubtitleAiModel.CLAUDE_HAIKU to (false to listOf("sk-ant-c"))
        )
        assertTrue(shouldShowSubtitleAiProviderCycleCta(two))
        assertEquals(
            listOf(SubtitleAiModel.GROQ_LLAMA_70B, SubtitleAiModel.GEMINI_FLASH_25),
            eligibleSubtitleAiModels(two)
        )
    }

    @Test
    fun `cycle is deterministic Groq then Gemini then Claude among eligible`() {
        val all = creds(
            SubtitleAiModel.GROQ_LLAMA_70B to (true to listOf("gsk_a")),
            SubtitleAiModel.GEMINI_FLASH_25 to (true to listOf("AIza_b")),
            SubtitleAiModel.CLAUDE_HAIKU to (true to listOf("sk-ant-c"))
        )
        assertEquals(
            SubtitleAiModel.GEMINI_FLASH_25,
            nextEligibleSubtitleAiModel(SubtitleAiModel.GROQ_LLAMA_70B, all)
        )
        assertEquals(
            SubtitleAiModel.CLAUDE_HAIKU,
            nextEligibleSubtitleAiModel(SubtitleAiModel.GEMINI_FLASH_25, all)
        )
        assertEquals(
            SubtitleAiModel.GROQ_LLAMA_70B,
            nextEligibleSubtitleAiModel(SubtitleAiModel.CLAUDE_HAIKU, all)
        )
    }

    @Test
    fun `cycle skips providers without usable key even if enabled`() {
        val mixed = creds(
            SubtitleAiModel.GROQ_LLAMA_70B to (true to listOf("gsk_a")),
            SubtitleAiModel.GEMINI_FLASH_25 to (true to emptyList()),
            SubtitleAiModel.CLAUDE_HAIKU to (true to listOf("sk-ant-c"))
        )
        assertEquals(
            listOf(SubtitleAiModel.GROQ_LLAMA_70B, SubtitleAiModel.CLAUDE_HAIKU),
            eligibleSubtitleAiModels(mixed)
        )
        assertEquals(
            SubtitleAiModel.CLAUDE_HAIKU,
            nextEligibleSubtitleAiModel(SubtitleAiModel.GROQ_LLAMA_70B, mixed)
        )
        assertEquals(
            SubtitleAiModel.GROQ_LLAMA_70B,
            nextEligibleSubtitleAiModel(SubtitleAiModel.CLAUDE_HAIKU, mixed)
        )
    }

    @Test
    fun `cycle from ineligible preferred lands on first eligible`() {
        val two = creds(
            SubtitleAiModel.GROQ_LLAMA_70B to (false to emptyList()),
            SubtitleAiModel.GEMINI_FLASH_25 to (true to listOf("AIza_b")),
            SubtitleAiModel.CLAUDE_HAIKU to (true to listOf("sk-ant-c"))
        )
        assertEquals(
            SubtitleAiModel.GEMINI_FLASH_25,
            nextEligibleSubtitleAiModel(SubtitleAiModel.GROQ_LLAMA_70B, two)
        )
    }
}
