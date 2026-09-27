package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAiOffTrackSelectionTest {

    @Test
    fun prefersPreferredEmbeddedWhenIndexAvailable() {
        assertEquals(
            UserAiOffTrackDecision.SELECT_PREFERRED_EMBEDDED,
            decideUserAiOffTrackSelection(preferredEmbeddedIndex = 2)
        )
    }

    @Test
    fun preservesCurrentSourceWhenNoPreferredEmbedded() {
        assertEquals(
            UserAiOffTrackDecision.PRESERVE_CURRENT_SOURCE_AI_OFF,
            decideUserAiOffTrackSelection(preferredEmbeddedIndex = -1)
        )
    }
}
