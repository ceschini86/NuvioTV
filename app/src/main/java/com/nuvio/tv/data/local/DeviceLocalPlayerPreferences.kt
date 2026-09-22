package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.ui.screens.player.AspectMode
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiCredentials
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiProviderCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local player preferences that are NOT tied to any profile.
 * These values stay on the device and are never synced across devices or profiles.
 *
 * Currently stores:
 *  - aspectMode
 *  - playerStatsHudButtonEnabled / playerStatsHudActive
 *  - subtitle AI credentials (multi-provider / multi-key), plus legacy single-key for migration
 */
@Singleton
class DeviceLocalPlayerPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        }
    ) {
        context.preferencesDataStoreFile("device_local_player_prefs")
    }

    private val aspectModeKey = stringPreferencesKey("aspect_mode")
    private val playerStatsHudButtonEnabledKey = booleanPreferencesKey("player_stats_hud_enabled")
    private val playerStatsHudActiveKey = booleanPreferencesKey("player_stats_hud_active")
    private val subtitleAiApiKeyKey = stringPreferencesKey("subtitle_ai_api_key")
    private val subtitleAiCredentialsKey = stringPreferencesKey("subtitle_ai_credentials_json")

    val aspectMode: Flow<AspectMode> = store.data.map { prefs ->
        prefs[aspectModeKey]?.let {
            runCatching { AspectMode.valueOf(it) }.getOrDefault(AspectMode.ORIGINAL)
        } ?: AspectMode.ORIGINAL
    }

    suspend fun setAspectMode(mode: AspectMode) {
        store.edit { prefs ->
            prefs[aspectModeKey] = mode.name
        }
    }

    val playerStatsHudButtonEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[playerStatsHudButtonEnabledKey] ?: false
    }

    val playerStatsHudEnabled: Flow<Boolean> = playerStatsHudButtonEnabled

    val playerStatsHudActive: Flow<Boolean> = store.data.map { prefs ->
        prefs[playerStatsHudActiveKey] ?: false
    }

    /** Legacy single-key flow — still exposed for older UI paths; prefer [subtitleAiCredentials]. */
    val subtitleAiApiKey: Flow<String> = store.data.map { prefs ->
        prefs[subtitleAiApiKeyKey].orEmpty()
    }

    val subtitleAiCredentials: Flow<SubtitleAiCredentials> = store.data.map { prefs ->
        val json = prefs[subtitleAiCredentialsKey]
        if (!json.isNullOrBlank()) {
            SubtitleAiCredentials.fromJson(json)
        } else {
            val legacy = prefs[subtitleAiApiKeyKey].orEmpty()
            if (legacy.isBlank()) {
                SubtitleAiCredentials()
            } else {
                // Preferred model is unknown here — seed onto Groq; settings will rebalance.
                SubtitleAiCredentials.migrateFromLegacy(legacy, SubtitleAiModel.GROQ_LLAMA_70B)
            }
        }
    }

    suspend fun setPlayerStatsHudButtonEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[playerStatsHudButtonEnabledKey] = enabled
            if (!enabled) {
                prefs[playerStatsHudActiveKey] = false
            }
        }
    }

    suspend fun setPlayerStatsHudEnabled(enabled: Boolean) {
        setPlayerStatsHudButtonEnabled(enabled)
    }

    suspend fun setPlayerStatsHudActive(active: Boolean) {
        store.edit { prefs ->
            prefs[playerStatsHudActiveKey] = active
        }
    }

    suspend fun setSubtitleAiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        store.edit { prefs ->
            prefs[subtitleAiApiKeyKey] = trimmed
            // Keep credentials JSON in sync for the currently preferred single-key UX.
            val current = prefs[subtitleAiCredentialsKey]?.let { SubtitleAiCredentials.fromJson(it) }
                ?: SubtitleAiCredentials()
            val preferred = current.enabledProviders().firstOrNull()?.model
                ?: SubtitleAiModel.GROQ_LLAMA_70B
            val migrated = if (trimmed.isBlank()) {
                current.withProvider(current.provider(preferred).copy(keys = emptyList(), enabled = false))
            } else {
                current.withProvider(
                    SubtitleAiProviderCredentials(
                        model = preferred,
                        enabled = true,
                        keys = listOf(trimmed)
                    )
                )
            }
            prefs[subtitleAiCredentialsKey] = migrated.toJson()
        }
    }

    suspend fun setSubtitleAiCredentials(credentials: SubtitleAiCredentials) {
        store.edit { prefs ->
            prefs[subtitleAiCredentialsKey] = credentials.toJson()
            // Mirror first usable key into legacy field for older readers.
            val firstKey = credentials.enabledProviders().firstOrNull()?.usableKeys?.firstOrNull().orEmpty()
            prefs[subtitleAiApiKeyKey] = firstKey
        }
    }

    suspend fun updateSubtitleAiProvider(provider: SubtitleAiProviderCredentials) {
        val current = subtitleAiCredentials.first()
        setSubtitleAiCredentials(current.withProvider(provider))
    }
}
