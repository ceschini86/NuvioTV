package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.ui.screens.player.AspectMode
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiAdvancedSettings
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
 *  - subtitle AI advanced tunables (batch / cooldown / Gemini pacing)
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
    private val subtitleAiMaxBatchSizeKey = intPreferencesKey("subtitle_ai_max_batch_size")
    private val subtitleAiBatchWindowMsKey = intPreferencesKey("subtitle_ai_batch_window_ms")
    private val subtitleAiGeminiBatchWindowMsKey = intPreferencesKey("subtitle_ai_gemini_batch_window_ms")
    private val subtitleAiRateLimitCooldownMsKey = intPreferencesKey("subtitle_ai_rate_limit_cooldown_ms")
    private val subtitleAiGeminiMinIntervalMsKey = intPreferencesKey("subtitle_ai_gemini_min_interval_ms")

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

    /** Legacy single-key field — read for migration / player fallback; mirrored on credential writes. */
    val subtitleAiApiKey: Flow<String> = store.data.map { prefs ->
        prefs[subtitleAiApiKeyKey].orEmpty()
    }

    /**
     * Credentials flow. When JSON is missing but a legacy key exists, returns an in-memory
     * migration seeded onto Groq until [ensureSubtitleAiCredentialsMigrated] persists JSON
     * with the real preferred model (called from Settings / player observe).
     */
    val subtitleAiCredentials: Flow<SubtitleAiCredentials> = store.data.map { prefs ->
        readCredentialsFromPrefs(prefs)
    }

    val subtitleAiAdvancedSettings: Flow<SubtitleAiAdvancedSettings> = store.data.map { prefs ->
        readAdvancedFromPrefs(prefs)
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

    /**
     * Test helper: simulate a pre-multi-provider install that only has the legacy key field.
     */
    suspend fun seedLegacySubtitleAiApiKeyOnlyForTests(apiKey: String) {
        store.edit { prefs ->
            prefs[subtitleAiApiKeyKey] = apiKey.trim()
            prefs.remove(subtitleAiCredentialsKey)
        }
    }

    /**
     * One-shot: if credentials JSON is empty but [subtitle_ai_api_key] has a value, persist
     * multi-provider JSON with that key on [preferredModel] (enabled). Idempotent when JSON
     * already exists — never drops stored keys.
     */
    suspend fun ensureSubtitleAiCredentialsMigrated(
        preferredModel: SubtitleAiModel
    ): SubtitleAiCredentials {
        val prefs = store.data.first()
        val json = prefs[subtitleAiCredentialsKey]
        if (!json.isNullOrBlank()) {
            return SubtitleAiCredentials.fromJson(json)
        }
        val legacy = prefs[subtitleAiApiKeyKey].orEmpty().trim()
        if (legacy.isBlank()) {
            return SubtitleAiCredentials()
        }
        val migrated = SubtitleAiCredentials.migrateFromLegacy(legacy, preferredModel)
        setSubtitleAiCredentials(migrated)
        return migrated
    }

    suspend fun setSubtitleAiCredentials(credentials: SubtitleAiCredentials) {
        store.edit { prefs ->
            prefs[subtitleAiCredentialsKey] = credentials.toJson()
            // Mirror first usable key into legacy field for older readers / fallback.
            val firstKey = credentials.enabledProviders().firstOrNull()?.usableKeys?.firstOrNull()
                .orEmpty()
            prefs[subtitleAiApiKeyKey] = firstKey
        }
    }

    suspend fun updateSubtitleAiProvider(provider: SubtitleAiProviderCredentials) {
        // Ensure any pending legacy→JSON migrate has run before RMW (caller should pass preferred
        // via ensure… first; here we persist whatever the Flow currently exposes).
        val current = subtitleAiCredentials.first()
        setSubtitleAiCredentials(current.withProvider(provider))
    }

    suspend fun setSubtitleAiAdvancedSettings(settings: SubtitleAiAdvancedSettings) {
        val clamped = SubtitleAiAdvancedSettings.clamp(settings)
        store.edit { prefs ->
            prefs[subtitleAiMaxBatchSizeKey] = clamped.maxBatchSize
            prefs[subtitleAiBatchWindowMsKey] = clamped.batchWindowMs
            prefs[subtitleAiGeminiBatchWindowMsKey] = clamped.geminiBatchWindowMs
            prefs[subtitleAiRateLimitCooldownMsKey] = clamped.rateLimitCooldownMs
            prefs[subtitleAiGeminiMinIntervalMsKey] = clamped.geminiMinIntervalMs
        }
    }

    suspend fun resetSubtitleAiAdvancedSettings() {
        setSubtitleAiAdvancedSettings(SubtitleAiAdvancedSettings.DEFAULT)
    }

    private fun readCredentialsFromPrefs(prefs: Preferences): SubtitleAiCredentials {
        val json = prefs[subtitleAiCredentialsKey]
        if (!json.isNullOrBlank()) {
            return SubtitleAiCredentials.fromJson(json)
        }
        val legacy = prefs[subtitleAiApiKeyKey].orEmpty()
        return if (legacy.isBlank()) {
            SubtitleAiCredentials()
        } else {
            // Temporary until ensureSubtitleAiCredentialsMigrated persists with preferred model.
            SubtitleAiCredentials.migrateFromLegacy(legacy, SubtitleAiModel.GROQ_LLAMA_70B)
        }
    }

    private fun readAdvancedFromPrefs(prefs: Preferences): SubtitleAiAdvancedSettings {
        return SubtitleAiAdvancedSettings.clamp(
            SubtitleAiAdvancedSettings(
                maxBatchSize = prefs[subtitleAiMaxBatchSizeKey]
                    ?: SubtitleAiAdvancedSettings.DEFAULT_MAX_BATCH_SIZE,
                batchWindowMs = prefs[subtitleAiBatchWindowMsKey]
                    ?: SubtitleAiAdvancedSettings.DEFAULT_BATCH_WINDOW_MS,
                geminiBatchWindowMs = prefs[subtitleAiGeminiBatchWindowMsKey]
                    ?: SubtitleAiAdvancedSettings.DEFAULT_GEMINI_BATCH_WINDOW_MS,
                rateLimitCooldownMs = prefs[subtitleAiRateLimitCooldownMsKey]
                    ?: SubtitleAiAdvancedSettings.DEFAULT_RATE_LIMIT_COOLDOWN_MS,
                geminiMinIntervalMs = prefs[subtitleAiGeminiMinIntervalMsKey]
                    ?: SubtitleAiAdvancedSettings.DEFAULT_GEMINI_MIN_INTERVAL_MS
            )
        )
    }
}
