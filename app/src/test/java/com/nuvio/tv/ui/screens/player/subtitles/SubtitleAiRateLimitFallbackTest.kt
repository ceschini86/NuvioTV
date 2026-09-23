package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.ui.screens.player.AiSubtitleLadderRung
import com.nuvio.tv.ui.screens.player.shouldDisableAiPreservingSelectionOnRateLimit
import com.nuvio.tv.ui.screens.player.shouldFallbackAiToClassicOnRateLimit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAiRateLimitFallbackTest {

    @Test
    fun disablesAiWheneverTranslatingAndAllKeysCooling() {
        assertTrue(
            shouldDisableAiPreservingSelectionOnRateLimit(
                translationEnabled = true,
                allKeysInCooldown = true
            )
        )
        assertFalse(
            shouldDisableAiPreservingSelectionOnRateLimit(
                translationEnabled = false,
                allKeysInCooldown = true
            )
        )
        assertFalse(
            shouldDisableAiPreservingSelectionOnRateLimit(
                translationEnabled = true,
                allKeysInCooldown = false
            )
        )
    }

    @Test
    fun legacyHelperAlsoDisablesForManualAndLocked() {
        // Selection is preserved in runtime; disable applies even for MANUAL / locked.
        assertTrue(
            shouldFallbackAiToClassicOnRateLimit(
                translationEnabled = true,
                userLocked = true,
                allKeysInCooldown = true,
                rung = AiSubtitleLadderRung.MANUAL
            )
        )
        assertTrue(
            shouldFallbackAiToClassicOnRateLimit(
                translationEnabled = true,
                userLocked = false,
                allKeysInCooldown = true,
                rung = AiSubtitleLadderRung.AI_EMBEDDED
            )
        )
        assertFalse(
            shouldFallbackAiToClassicOnRateLimit(
                translationEnabled = true,
                userLocked = false,
                allKeysInCooldown = false,
                rung = AiSubtitleLadderRung.AI_EMBEDDED
            )
        )
    }
}
