package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.ui.screens.player.TrackInfo
import com.nuvio.tv.ui.screens.player.findAiSourceSubtitleTrackIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAiRouterFallbackTest {

    @Test
    fun normalizeProviderError_mapsHttp429Variants() {
        assertEquals(TRANSLATION_ERROR_RATE_LIMITED, normalizeProviderError("RATE_LIMITED", null))
        assertEquals(TRANSLATION_ERROR_RATE_LIMITED, normalizeProviderError("HTTP 429: quota", 429))
        assertEquals(TRANSLATION_ERROR_RATE_LIMITED, normalizeProviderError("resource_exhausted", null))
        assertEquals(TRANSLATION_ERROR_RATE_LIMITED, normalizeProviderError(null, 429))
        assertEquals(TRANSLATION_ERROR_PROVIDER, normalizeProviderError("boom", 500))
    }

    @Test
    fun normalizeProviderError_mapsCreditBalanceAndHidesJson() {
        val anthropicJson =
            """HTTP 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API"}}"""
        assertEquals(
            TRANSLATION_ERROR_INSUFFICIENT_CREDITS,
            normalizeProviderError(anthropicJson, 400)
        )
        assertEquals(
            TRANSLATION_ERROR_INSUFFICIENT_CREDITS,
            normalizeProviderError("insufficient_quota", null)
        )
        assertEquals(
            TRANSLATION_ERROR_PROVIDER,
            normalizeProviderError("""HTTP 500: {"type":"error","message":"internal"}""", 500)
        )
        assertEquals(TRANSLATION_ERROR_API_KEY_MISSING, normalizeProviderError("API key missing", 401))
    }

    @Test
    fun translateBatch_fallsBackToNextKeyAfterRateLimit() = runBlocking {
        val attempted = mutableListOf<String>()
        val router = SubtitleAiRouter(
            translateAttempt = { _, apiKey, lines, _ ->
                attempted += apiKey
                if (apiKey == "key-a") {
                    ProviderAttemptResult(
                        translation = TranslationResult(lines, false, "RATE_LIMITED"),
                        httpCode = 429
                    )
                } else {
                    ProviderAttemptResult(
                        translation = TranslationResult(lines.map { "ES:$it" }, true)
                    )
                }
            }
        )
        router.credentials = SubtitleAiCredentials(
            providers = listOf(
                SubtitleAiProviderCredentials(
                    model = SubtitleAiModel.CLAUDE_HAIKU,
                    enabled = true,
                    keys = listOf("key-a", "key-b")
                )
            )
        )
        router.preferredModel = SubtitleAiModel.CLAUDE_HAIKU

        val result = router.translateBatch(listOf("hello"), "Spanish")
        assertTrue(result.success)
        assertEquals(listOf("ES:hello"), result.lines)
        assertEquals(listOf("key-a", "key-b"), attempted)
    }

    @Test
    fun translateBatch_fallsBackToNextProviderAfterRateLimit() = runBlocking {
        val attemptedModels = mutableListOf<SubtitleAiModel>()
        val router = SubtitleAiRouter(
            translateAttempt = { model, _, lines, _ ->
                attemptedModels += model
                if (model == SubtitleAiModel.CLAUDE_HAIKU) {
                    ProviderAttemptResult(
                        translation = TranslationResult(lines, false, "HTTP 429"),
                        httpCode = 429
                    )
                } else {
                    ProviderAttemptResult(
                        translation = TranslationResult(lines.map { "OK:$it" }, true)
                    )
                }
            }
        )
        router.credentials = SubtitleAiCredentials(
            providers = listOf(
                SubtitleAiProviderCredentials(
                    model = SubtitleAiModel.CLAUDE_HAIKU,
                    enabled = true,
                    keys = listOf("claude-key")
                ),
                SubtitleAiProviderCredentials(
                    model = SubtitleAiModel.GROQ_LLAMA_70B,
                    enabled = true,
                    keys = listOf("groq-key")
                )
            )
        )
        router.preferredModel = SubtitleAiModel.CLAUDE_HAIKU

        val result = router.translateBatch(listOf("hola"), "Spanish")
        assertTrue(result.success)
        assertEquals(listOf("OK:hola"), result.lines)
        assertEquals(
            listOf(SubtitleAiModel.CLAUDE_HAIKU, SubtitleAiModel.GROQ_LLAMA_70B),
            attemptedModels
        )
    }

    @Test
    fun findAiSource_skipsBitmapAndForced() {
        val tracks = listOf(
            track(name = "Forced", language = "en", forced = true),
            track(name = "PGS", language = "en", codec = "PGS"),
            track(name = "Francais", language = "fr", codec = "SRT")
        )
        val index = findAiSourceSubtitleTrackIndex(tracks, originalLanguage = "en")
        assertEquals(2, index)
        assertEquals("fr", tracks[index].language)
    }

    @Test
    fun findAiSource_prefersOriginalLanguageWhenPresent() {
        val tracks = listOf(
            track(name = "English", language = "en"),
            track(name = "Francais", language = "fr")
        )
        val index = findAiSourceSubtitleTrackIndex(tracks, originalLanguage = "fr")
        assertEquals(1, index)
    }

    private fun track(
        name: String,
        language: String?,
        codec: String? = "SRT",
        forced: Boolean = false
    ) = TrackInfo(
        index = 0,
        name = name,
        language = language,
        trackId = name,
        codec = codec,
        isForced = forced,
        isSelected = false
    )
}
