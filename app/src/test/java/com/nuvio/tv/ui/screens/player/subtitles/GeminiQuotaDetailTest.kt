package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiQuotaDetailTest {

    @Test
    fun readsQuotaIdValueAndRetryDelay() {
        val body = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
              {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_input_token_count",
                 "quotaId":"GenerateContentInputTokensPerModelPerMinute-FreeTier","quotaValue":"250000"}]},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}
        """.trimIndent()
        assertEquals(
            "GenerateContentInputTokensPerModelPerMinute-FreeTier=250000,retry=37s",
            geminiQuotaDetail(body)
        )
    }

    @Test
    fun missingOrBrokenBodiesDoNotThrow() {
        assertEquals("none", geminiQuotaDetail(null))
        assertEquals("none", geminiQuotaDetail("""{"error":{"code":429}}"""))
        assertEquals("unparsed", geminiQuotaDetail("<html>"))
    }

    @Test
    fun retryDelayIsReadInMilliseconds() {
        val daily = """{"error":{"details":[{"@type":"x.RetryInfo","retryDelay":"43621s"}]}}"""
        assertEquals(43_621_000L, geminiRetryDelayMs(daily))
        assertEquals(500L, geminiRetryDelayMs("""{"error":{"details":[{"retryDelay":"0.5s"}]}}"""))
        assertNull(geminiRetryDelayMs("""{"error":{"code":429}}"""))
        assertNull(geminiRetryDelayMs("<html>"))
    }
}
