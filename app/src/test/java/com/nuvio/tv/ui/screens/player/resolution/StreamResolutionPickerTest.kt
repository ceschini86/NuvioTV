package com.nuvio.tv.ui.screens.player.resolution

import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolutionPickerTest {
    private fun stream(
        name: String?,
        addonName: String = "AIOStreams",
        description: String? = null,
        filename: String? = null,
        url: String = "https://addon.example/${name.hashCode()}"
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = filename?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = it)
        },
        addonName = addonName,
        addonLogo = null
    )

    @Test
    fun `reads the resolution from the AIOStreams name`() {
        assertEquals(DebridStreamResolution.P2160, StreamResolutionPicker.detect(stream("4K ⚽")))
        assertEquals(DebridStreamResolution.P1080, StreamResolutionPicker.detect(stream("1080p ⚽")))
    }

    @Test
    fun `falls back to the filename when the name has no resolution`() {
        val s = stream("Torrentio", filename = "Oppenheimer.2023.2160p.WEB-DL.mkv")
        assertEquals(DebridStreamResolution.P2160, StreamResolutionPicker.detect(s))
    }

    @Test
    fun `does not read 4k inside other words or hdr tags`() {
        assertEquals(DebridStreamResolution.UNKNOWN, StreamResolutionPicker.detect("x264 HDR10 DTS"))
        assertEquals(DebridStreamResolution.UNKNOWN, StreamResolutionPicker.detect("ab4kz"))
    }

    @Test
    fun `lists the resolutions present, highest first, and marks the current one`() {
        val streams = listOf(stream("1080p a"), stream("4K b"), stream("1080p c"), stream("Unknown"))
        val options = StreamResolutionPicker.options(streams, DebridStreamResolution.P1080)
        assertEquals(
            listOf(
                ResolutionOption(DebridStreamResolution.P2160, 1, isCurrent = false),
                ResolutionOption(DebridStreamResolution.P1080, 2, isCurrent = true)
            ),
            options
        )
    }

    @Test
    fun `picks the first stream of the playing addon at that resolution`() {
        val streams = listOf(
            stream("1080p other", addonName = "Torrentio"),
            stream("1080p mine", addonName = "AIOStreams"),
            stream("4K playing", addonName = "AIOStreams")
        )
        val picked = StreamResolutionPicker.pick(streams, DebridStreamResolution.P1080, "AIOStreams", currentIndex = 2)
        assertEquals("1080p mine", picked?.name)
    }

    @Test
    fun `falls back to the first stream at that resolution from any addon`() {
        val streams = listOf(stream("1080p other", addonName = "Torrentio"), stream("4K playing"))
        val picked = StreamResolutionPicker.pick(streams, DebridStreamResolution.P1080, "AIOStreams", currentIndex = 1)
        assertEquals("1080p other", picked?.name)
    }

    @Test
    fun `never picks the playing stream`() {
        val streams = listOf(stream("4K playing"))
        assertNull(StreamResolutionPicker.pick(streams, DebridStreamResolution.P2160, "AIOStreams", currentIndex = 0))
    }
}
