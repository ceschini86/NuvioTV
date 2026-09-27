package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }

    @Test
    fun `subtitle AI keys stay local unless profile sync is enabled`() {
        assertTrue(
            shouldExcludePreferenceFromProfileSettingsSync(
                "player_settings",
                "subtitle_ai_enabled",
                subtitleAiSyncWithProfile = false
            )
        )
        assertTrue(
            shouldExcludePreferenceFromProfileSettingsSync(
                "player_settings",
                "subtitle_ai_credentials_json",
                subtitleAiSyncWithProfile = false
            )
        )
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(
                "player_settings",
                "subtitle_ai_enabled",
                subtitleAiSyncWithProfile = true
            )
        )
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(
                "player_settings",
                "subtitle_ai_credentials_json",
                subtitleAiSyncWithProfile = true
            )
        )
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync(
                "player_settings",
                "subtitle_ai_sync_with_profile",
                subtitleAiSyncWithProfile = false
            )
        )
    }
}
