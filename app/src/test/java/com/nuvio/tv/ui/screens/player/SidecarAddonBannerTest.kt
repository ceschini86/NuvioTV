package com.nuvio.tv.ui.screens.player

import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The OpenSubtitles v3 banner cue is dropped at parse time; its file name is kept.
 * Cues come from the lenient parser: Media3's VTT parser spins on the android.jar stubs in JVM tests.
 */
class SidecarAddonBannerTest {

    private fun texts(cues: List<CuesWithTiming>): List<String> =
        cues.map { entry -> entry.cues.joinToString("\n") { it.text.toString() } }

    @Test
    fun `opensubtitles banner is dropped and its file name kept`() {
        // Header as served for Babylon (2022), opensubtitles.stremio.homes.
        val srt = """
            1
            00:00:01,000 --> 00:00:06,000
            >>OpenSubtitles v3+ v0.0.4<<
            <u>=>Babylon 2022 1080p uhd bluray ddpa51 pgs hdr x265-SM737.eng.Forced.srt</u>

            2
            00:02:34,030 --> 00:02:35,866
            What are you doing?
        """.trimIndent()
        val parsed = parseSidecarTimedCuesLenient(srt, "https://example.test/sub.srt")
        val (cues, fileName) = stripAddonBanner(parsed)
        assertEquals(listOf("What are you doing?"), texts(cues))
        assertEquals(
            "Babylon 2022 1080p uhd bluray ddpa51 pgs hdr x265-SM737.eng.Forced.srt",
            fileName
        )
    }

    @Test
    fun `ordinary first cue is kept`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            >> Previously on the show

            2
            00:00:04,000 --> 00:00:05,000
            Hello.
        """.trimIndent()
        val parsed = parseSidecarTimedCuesLenient(srt, "https://example.test/sub.srt")
        val (cues, fileName) = stripAddonBanner(parsed)
        assertEquals(2, cues.size)
        assertNull(fileName)
    }
}
