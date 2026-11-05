package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAiBaseUrlOverrideTest {

    private val override = "http://10.0.2.2:8787"

    @Test
    fun nullOrBlankOverrideKeepsOriginal() {
        val url = "https://api.groq.com/openai/v1/chat/completions"
        assertEquals(url, rewriteBaseUrl(url, null))
        assertEquals(url, rewriteBaseUrl(url, ""))
        assertEquals(url, rewriteBaseUrl(url, "   "))
    }

    @Test
    fun invalidOverrideKeepsOriginal() {
        val url = "https://api.anthropic.com/v1/messages"
        assertEquals(url, rewriteBaseUrl(url, "not a url"))
    }

    @Test
    fun replacesSchemeHostPortAndKeepsPath() {
        assertEquals(
            "http://10.0.2.2:8787/openai/v1/chat/completions",
            rewriteBaseUrl("https://api.groq.com/openai/v1/chat/completions", override)
        )
        assertEquals(
            "http://10.0.2.2:8787/v1/messages",
            rewriteBaseUrl("https://api.anthropic.com/v1/messages", override)
        )
    }

    @Test
    fun keepsGeminiColonPathAndQuery() {
        assertEquals(
            "http://10.0.2.2:8787/v1beta/models/gemini-3.5-flash-lite:generateContent?key=abc",
            rewriteBaseUrl(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=abc",
                override
            )
        )
    }

    @Test
    fun overridePathIsIgnoredAndTrailingSlashTolerated() {
        assertEquals(
            "http://localhost:9000/v1/messages",
            rewriteBaseUrl("https://api.anthropic.com/v1/messages", " http://localhost:9000/ignored/ ")
        )
    }
}
