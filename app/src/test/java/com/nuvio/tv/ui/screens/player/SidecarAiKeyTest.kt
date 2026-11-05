package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Render and prefetch key the AI cache through [sidecarAiKey] over [sidecarShownCues]. When the
 * prefetch skipped the SDH filter, dash lines never hit the cache (Maniac S01E01, real TV).
 */
class SidecarAiKeyTest {

    private fun cue(text: String) = Cue.Builder().setText(text).build()

    private fun key(text: String, stripSdh: Boolean, removeHi: Boolean) =
        sidecarAiKey(sidecarJoinedText(sidecarShownCues(listOf(cue(text)), stripSdh)), removeHi)

    @Test
    fun `dash line with inner speaker tag keys without the leftover space`() {
        assertEquals(
            "- He's my brother.\n- Thank you.",
            key("- He's my brother.\n- [man] Thank you.", stripSdh = true, removeHi = true)
        )
    }

    @Test
    fun `line that is only a sound tag drops out`() {
        assertEquals(
            "- The voices aren't real.",
            key("- [man] The voices aren't real.\n- [clattering]", stripSdh = true, removeHi = true)
        )
    }

    @Test
    fun `without SDH filtering the key keeps the source text`() {
        assertEquals(
            "- He's my brother.\n- [man] Thank you.",
            key("- He's my brother.\n- [man] Thank you.", stripSdh = false, removeHi = false)
        )
    }
}
