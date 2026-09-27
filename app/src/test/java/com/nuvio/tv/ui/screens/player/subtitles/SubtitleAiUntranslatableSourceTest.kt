package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.ui.screens.player.shouldSwitchSourceOnUntranslatable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAiUntranslatableSourceTest {

    @Test
    fun manualLockDoesNotSwitchSource() {
        assertFalse(shouldSwitchSourceOnUntranslatable(userLocked = true))
    }

    @Test
    fun smartUnlockedMaySwitchSource() {
        assertTrue(shouldSwitchSourceOnUntranslatable(userLocked = false))
    }
}
