package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiAdvancedSettings
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiCredentials
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_API_KEY_MISSING
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_CONTENT_BLOCKED
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_FEATURE_DISABLED
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_RATE_LIMITED
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_UNTRANSLATABLE_SOURCE
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Creates and observes AI subtitle translation settings for ExoPlayer playback.
 *
 * Smart-subtitles ladder (when "smart AI subtitles" is on):
 * 1. Preferred-language embedded track (no AI)
 * 2. AI translation of embedded track (prefer original-language)
 * 3. Classic preferred-language auto-select (no AI; may pick an addon without translating)
 *
 * Addons are never auto-translated. Manual "Translate with AI" is user-locked and skips
 * automatic overrides.
 *
 * Product S6: when every usable key is in 429 cooldown and playback is on an AI-on rung
 * (not manual lock), disable translation and preserve the current selection.
 */
internal fun PlayerRuntimeController.ensureSubtitleTranslationManager(): SubtitleTranslationManager {
    subtitleTranslationManager?.let { return it }
    val manager = SubtitleTranslationManager(
        service = SubtitleTranslationService(),
        targetLanguage = resolveSubtitleAiTargetLanguageName(),
        scope = scope
    )
    manager.removeHearingImpaired = currentPlayerSettingsForReport.subtitleStyle.stripSdh
    manager.onTranslatingChanged = { translating ->
        _uiState.update { it.copy(isAiSubtitleTranslating = translating) }
    }
    manager.onBatchResult = { success, error ->
        if (success) {
            refreshAiSubtitleQuotaExhaustedState()
            _uiState.update { it.copy(aiSubtitleLastError = null) }
        } else if (error != null && error != TRANSLATION_ERROR_CONTENT_BLOCKED) {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle batch failed: $error")
            _uiState.update { it.copy(aiSubtitleLastError = error) }
            if (error == TRANSLATION_ERROR_RATE_LIMITED) {
                maybeHandleAiRateLimitExhaustion()
            }
        }
    }
    manager.onUntranslatableSource = {
        if (manager.isEnabled) {
            if (!shouldSwitchSourceOnUntranslatable(userLocked = aiSubtitleUserLocked)) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "AI subtitle source has no extractable text; MANUAL lock — preserving selection"
                )
                _uiState.update { it.copy(aiSubtitleLastError = TRANSLATION_ERROR_UNTRANSLATABLE_SOURCE) }
                val prev = _uiState.value.aiSubtitleDiagnostics
                if (prev != null) {
                    publishAiSubtitleDiagnostics(
                        prev.copy(reason = "source untranslatable (PGS/blank); selection preserved")
                    )
                }
            } else {
                Log.w(PlayerRuntimeController.TAG, "AI subtitle source has no extractable text; trying next source")
                val switched = selectAiTranslationSourceIfAvailable(excludeCurrent = true)
                if (!switched) {
                    Log.w(PlayerRuntimeController.TAG, "No usable AI source left; disabling translation")
                    setAiSubtitleTranslationEnabled(false)
                    if (canRunAiAutoSelectLadder()) {
                        applyAiAutoSelectLadder()
                    }
                }
            }
        }
    }
    subtitleTranslationManager = manager
    return manager
}

/** G8 pure decision: Smart may switch; MANUAL lock must keep the requested source. */
internal fun shouldSwitchSourceOnUntranslatable(userLocked: Boolean): Boolean = !userLocked

/**
 * Pure G3 decision for **user** AI Stop/toggle-off (not S6 rate-limit).
 * Prefer preferred-language embedded when available; otherwise keep the current source AI off.
 */
internal enum class UserAiOffTrackDecision {
    SELECT_PREFERRED_EMBEDDED,
    PRESERVE_CURRENT_SOURCE_AI_OFF
}

internal fun decideUserAiOffTrackSelection(
    preferredEmbeddedIndex: Int
): UserAiOffTrackDecision =
    if (preferredEmbeddedIndex >= 0) {
        UserAiOffTrackDecision.SELECT_PREFERRED_EMBEDDED
    } else {
        UserAiOffTrackDecision.PRESERVE_CURRENT_SOURCE_AI_OFF
    }

/**
 * Index of a preferred-language embedded text track, or -1 if none.
 * Shared by the Smart ladder and G3 user AI-off.
 */
internal fun PlayerRuntimeController.findPreferredLanguageEmbeddedTrackIndex(): Int {
    val state = _uiState.value
    val primaryTarget = subtitleLanguageTargets().firstOrNull() ?: return -1
    if (!hasScannedTextTracksOnce) return -1
    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val preferredInternalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = listOf(primaryTarget),
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = selectedAudioTrack
    )
    if (preferredInternalIndex < 0) return -1
    val track = state.subtitleTracks[preferredInternalIndex]
    if (!trackMatchesPreferredLanguage(track, primaryTarget)) return -1
    return preferredInternalIndex
}

/**
 * G3: UI Stop / toggle AI off (Info CTA on AI option, Col2 toggle).
 * Turns translation off; if a preferred-language embedded track exists, selects it;
 * otherwise keeps the current track/addon with AI off.
 *
 * **Not** for S6 rate-limit — that path must [maybeHandleAiRateLimitExhaustion] preserve selection.
 */
internal fun PlayerRuntimeController.disableAiSubtitleTranslationFromUserToggle() {
    val stateBefore = _uiState.value
    val preferredIndex = findPreferredLanguageEmbeddedTrackIndex()
    val decision = decideUserAiOffTrackSelection(preferredIndex)

    setAiSubtitleTranslationEnabled(false, allowPreferredUpgrade = false)

    when (decision) {
        UserAiOffTrackDecision.SELECT_PREFERRED_EMBEDDED -> {
            val track = stateBefore.subtitleTracks[preferredIndex]
            Log.i(
                PlayerRuntimeController.TAG,
                "AI toggle off: preferred embedded index=$preferredIndex lang=${track.language}"
            )
            selectSubtitleTrack(preferredIndex)
            _uiState.update {
                it.copy(
                    selectedSubtitleTrackIndex = preferredIndex,
                    selectedAddonSubtitle = null
                )
            }
            autoSubtitleSelected = true
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
                    reason = "user toggled AI off; preferred-language embedded selected",
                    sourceKind = AiSubtitleSourceKind.EMBEDDED,
                    sourceLabel = track.name,
                    sourceLanguage = track.language,
                    sourceInternalIndex = track.index,
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = false
                )
            )
            logAiSubtitleAction(
                action = "Select AI option",
                source = track.name ?: track.language,
                reason = "toggle_off_preferred_embedded",
                locked = false,
                rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED
            )
        }
        UserAiOffTrackDecision.PRESERVE_CURRENT_SOURCE_AI_OFF -> {
            val prev = stateBefore.aiSubtitleDiagnostics
            Log.i(
                PlayerRuntimeController.TAG,
                "AI toggle off: no preferred embedded — preserve source " +
                    "(rung=${prev?.rung}, label=${prev?.sourceLabel})"
            )
            val preserved = prev?.copy(
                reason = "user toggled AI off; preferred embedded unavailable — source preserved",
                userLocked = false
            ) ?: AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
                reason = "user toggled AI off; preferred embedded unavailable — source preserved",
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = false
            )
            publishAiSubtitleDiagnostics(preserved)
            logAiSubtitleAction(
                action = "Select AI option",
                source = preserved.sourceLabel ?: preserved.sourceLanguage,
                reason = "toggle_off_preserve_source",
                locked = false,
                rung = preserved.rung
            )
        }
    }
}

/**
 * Rate-limit UX: when every usable key is cooling, stop translating, **keep** the current
 * track/addon selection (never promote French/another language), surface the error, and
 * mark quota exhausted so Translate CTAs hide/disable until cooldown ends.
 */
internal fun PlayerRuntimeController.maybeHandleAiRateLimitExhaustion() {
    val manager = subtitleTranslationManager ?: return
    refreshAiSubtitleQuotaExhaustedState()
    if (!manager.allUsableKeysInCooldown()) return
    if (!shouldDisableAiPreservingSelectionOnRateLimit(
            translationEnabled = manager.isEnabled,
            allKeysInCooldown = true
        )
    ) {
        return
    }
    val prev = _uiState.value.aiSubtitleDiagnostics
    Log.i(
        PlayerRuntimeController.TAG,
        "AI rate-limit: all keys cooling — AI off, preserve selection (rung=${prev?.rung})"
    )
    // Turns translation off without re-running classic/ladder (that used to select embedded FR).
    setAiSubtitleTranslationEnabled(false)
    val preserved = prev?.copy(
        reason = "all keys rate-limited; selection preserved",
        userLocked = false
    ) ?: AiSubtitleDiagnostics(
        rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
        reason = "all keys rate-limited; selection preserved",
        targetLanguage = resolveSubtitleAiTargetLanguageName(),
        model = subtitleAiModel.name,
        userLocked = false
    )
    publishAiSubtitleDiagnostics(preserved)
    _uiState.update {
        it.copy(
            aiSubtitleQuotaExhausted = true,
            aiSubtitleLastError = TRANSLATION_ERROR_RATE_LIMITED
        )
    }
    scheduleAiSubtitleQuotaExhaustionRefresh()
}

internal fun PlayerRuntimeController.refreshAiSubtitleQuotaExhaustedState() {
    val exhausted = subtitleTranslationManager?.allUsableKeysInCooldown() == true
    val current = _uiState.value.aiSubtitleQuotaExhausted
    if (current == exhausted) {
        if (exhausted) scheduleAiSubtitleQuotaExhaustionRefresh()
        return
    }
    _uiState.update { it.copy(aiSubtitleQuotaExhausted = exhausted) }
    if (exhausted) {
        scheduleAiSubtitleQuotaExhaustionRefresh()
    } else {
        aiSubtitleQuotaRefreshJob?.cancel()
        aiSubtitleQuotaRefreshJob = null
    }
}

internal fun PlayerRuntimeController.scheduleAiSubtitleQuotaExhaustionRefresh() {
    val manager = subtitleTranslationManager ?: return
    val waitMs = manager.nextCooldownRemainingMs().coerceAtLeast(500L)
    aiSubtitleQuotaRefreshJob?.cancel()
    aiSubtitleQuotaRefreshJob = scope.launch {
        kotlinx.coroutines.delay(waitMs)
        refreshAiSubtitleQuotaExhaustedState()
        if (subtitleTranslationManager?.allUsableKeysInCooldown() == true) {
            scheduleAiSubtitleQuotaExhaustionRefresh()
        } else if (subtitleAiAutoSelect && !isUserExplicitSubtitleSelection && !aiSubtitleUserLocked) {
            // Quota recovered — smart may resume AI rungs without stealing an explicit pick.
            applySubtitleAutoSelectPolicy()
        }
    }
}

/** Pure decision: stop AI translation when all keys are cooling (unit-tested). */
internal fun shouldDisableAiPreservingSelectionOnRateLimit(
    translationEnabled: Boolean,
    allKeysInCooldown: Boolean
): Boolean = translationEnabled && allKeysInCooldown

/**
 * Legacy name from S6-classic era; now equivalent to [shouldDisableAiPreservingSelectionOnRateLimit]
 * for AI-on paths (userLocked no longer blocks disable — we still preserve the track).
 */
internal fun shouldFallbackAiToClassicOnRateLimit(
    translationEnabled: Boolean,
    userLocked: Boolean,
    allKeysInCooldown: Boolean,
    rung: AiSubtitleLadderRung?
): Boolean {
    // userLocked / rung ignored for the disable decision; selection is always preserved.
    return shouldDisableAiPreservingSelectionOnRateLimit(translationEnabled, allKeysInCooldown)
}

internal fun PlayerRuntimeController.observeSubtitleAiSettings() {
    scope.launch {
        combine(
            playerSettingsDataStore.playerSettings,
            deviceLocalPlayerPreferences.subtitleAiCredentials,
            deviceLocalPlayerPreferences.subtitleAiApiKey,
            deviceLocalPlayerPreferences.subtitleAiAdvancedSettings
        ) { settings, credentials, legacyKey, advanced ->
            AiSettingsSnapshot(settings.subtitleStyle, credentials, legacyKey, advanced)
        }.distinctUntilChanged().collect { snap ->
            val style = snap.style
            subtitleAiModel = runCatching {
                SubtitleAiModel.valueOf(style.aiModel)
            }.getOrDefault(
                snap.credentials.enabledProviders().firstOrNull()?.model
                    ?: SubtitleAiModel.GROQ_LLAMA_70B
            )
            // One-shot legacy→JSON using preferred model so Settings and runtime agree.
            val credentials = deviceLocalPlayerPreferences.ensureSubtitleAiCredentialsMigrated(
                subtitleAiModel
            )
            val legacyKey = snap.legacyKey
            subtitleAiCredentials = credentials
            subtitleAiApiKey = credentials.enabledProviders().firstOrNull()?.usableKeys?.firstOrNull()
                ?: legacyKey
            subtitleAiFeatureEnabled = style.aiEnabled
            subtitleAiAutoSelect = style.aiAutoSelect

            val manager = ensureSubtitleTranslationManager()
            manager.updateCredentials(credentials)
            manager.updatePreferredModel(subtitleAiModel)
            // Fallback if migrate somehow left empty but legacy still present.
            if (!credentials.anyUsable() && legacyKey.isNotBlank()) {
                manager.updateCredentials(
                    SubtitleAiCredentials.migrateFromLegacy(legacyKey, subtitleAiModel)
                )
            }
            manager.updateService(subtitleAiApiKey, subtitleAiModel)
            manager.updateAdvancedSettings(snap.advanced)
            manager.targetLanguage = resolveSubtitleAiTargetLanguageName()
            manager.removeHearingImpaired = style.stripSdh

            val canUseAi = !isUsingMpvEngine() &&
                style.aiEnabled &&
                (credentials.anyUsable() || legacyKey.isNotBlank())

            if (!canUseAi && manager.isEnabled) {
                setAiSubtitleTranslationEnabled(false)
            } else if (style.aiAutoSelect && canUseAi) {
                applySubtitleAutoSelectPolicy()
            }

            _uiState.update {
                it.copy(
                    aiSubtitleAvailable = canUseAi,
                    aiSubtitleTranslationActive = manager.isEnabled && canUseAi,
                    subtitleAiFeatureEnabled = style.aiEnabled
                )
            }
            refreshAiSubtitleQuotaExhaustedState()
        }
    }
}

private data class AiSettingsSnapshot(
    val style: com.nuvio.tv.data.local.SubtitleStyleSettings,
    val credentials: SubtitleAiCredentials,
    val legacyKey: String,
    val advanced: SubtitleAiAdvancedSettings
)

/**
 * Single entry for automatic subtitle selection. When smart AI subtitles are enabled, uses
 * preferred-embedded → AI (embedded or scored addon) → preferred scored addon.
 * Otherwise keeps the classic preferred-language auto-select.
 */
internal fun PlayerRuntimeController.applySubtitleAutoSelectPolicy() {
    if (isUserExplicitSubtitleSelection) {
        Log.d(PlayerRuntimeController.TAG, "SUB_POLICY stop: user explicit selection")
        return
    }
    if (aiSubtitleUserLocked && subtitleTranslationManager?.isEnabled == true) {
        // Lock means keep the user's source. Only re-pick if the selection was cleared
        // (e.g. tracks rebuilt). Calling selectAiTranslationSourceIfAvailable() here used to
        // prefer embedded and steal a manual Translate-with-AI addon mid-playback.
        val lockedState = _uiState.value
        if (lockedState.selectedAddonSubtitle == null &&
            lockedState.selectedSubtitleTrackIndex < 0
        ) {
            selectAiTranslationSourceIfAvailable()
        }
        return
    }
    if (canRunAiAutoSelectLadder()) {
        applyAiAutoSelectLadder()
        return
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "SUB_POLICY classic: smart=$subtitleAiAutoSelect feature=$subtitleAiFeatureEnabled " +
            "keys=${subtitleAiCredentials.anyUsable() || subtitleAiApiKey.isNotBlank()} " +
            "mpv=${isUsingMpvEngine()}"
    )
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    if (subtitleTranslationManager?.isEnabled == true) {
        refreshAiSubtitleSourceAndMaybeUpgrade()
    }
}

internal fun PlayerRuntimeController.canRunAiAutoSelectLadder(): Boolean {
    return subtitleAiAutoSelect &&
        subtitleAiFeatureEnabled &&
        (subtitleAiCredentials.anyUsable() || subtitleAiApiKey.isNotBlank()) &&
        !isUsingMpvEngine()
}

/**
 * Priority: preferred embedded → AI on original/embedded → classic auto-select.
 * Smart never evaluates or translates addons (Translate with AI only).
 */
internal fun PlayerRuntimeController.applyAiAutoSelectLadder() {
    if (isUserExplicitSubtitleSelection || aiSubtitleUserLocked) return
    if (!canRunAiAutoSelectLadder()) return

    val state = _uiState.value
    // Keep-disabled must not confuse "selection cleared for a new media file" (in-player stream
    // switch) with the user choosing Off. Only the persisted/disabled preference means Off.
    if (subtitleDisabledByPersistedPreference ||
        rememberedTrackPreference?.subtitle == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    ) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder stop: subtitles kept disabled")
        return
    }

    val targets = subtitleLanguageTargets()
    val primaryTarget = targets.firstOrNull()
    if (primaryTarget == null) {
        autoSubtitleSelected = true
        setAiSubtitleTranslationEnabled(false)
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.NONE,
                reason = "preferred=none",
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = aiSubtitleUserLocked
            )
        )
        Log.d(PlayerRuntimeController.TAG, "AI ladder stop: preferred=none")
        return
    }

    // Forced preference only diverts to classic when forced can actually bind (audio language
    // matches preferred). Otherwise Forced+Smart used to skip the ladder entirely and classic
    // picked the secondary-language firstOrNull addon (EN) — with zero "AI ladder" logs.
    if (state.subtitleStyle.useForcedSubtitles) {
        val audioForForced = selectedAudioTrackForSubtitleMatching(state)
        if (audioForForced == null) {
            Log.d(PlayerRuntimeController.TAG, "AI ladder defer: forced preference waiting for audio")
            return
        }
        val forcedApplies = audioMatchesSubtitleTargetForForced(audioForForced, primaryTarget)
        if (forcedApplies) {
            Log.d(PlayerRuntimeController.TAG, "AI ladder: forced applies — classic forced path")
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
                    reason = "forced subtitles mode applies for this audio",
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = false
                )
            )
            return
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AI ladder: forced on but audio≠preferred — continuing smart " +
                "(audio=${audioForForced.language}, preferred=$primaryTarget)"
        )
    }

    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder defer: text tracks not scanned yet")
        return
    }

    val preferredInternalIndex = findPreferredLanguageEmbeddedTrackIndex()
    if (preferredInternalIndex >= 0) {
        val track = state.subtitleTracks[preferredInternalIndex]
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: preferred embedded index=$preferredInternalIndex lang=${track.language}"
        )
        selectSubtitleTrack(preferredInternalIndex)
        _uiState.update {
            it.copy(
                selectedSubtitleTrackIndex = preferredInternalIndex,
                selectedAddonSubtitle = null
            )
        }
        autoSubtitleSelected = true
        setAiSubtitleTranslationEnabled(false)
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
                reason = "preferred-language embedded available",
                sourceKind = AiSubtitleSourceKind.EMBEDDED,
                sourceLabel = track.name,
                sourceLanguage = track.language,
                sourceInternalIndex = track.index,
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = false
            )
        )
        return
    }

    val translationCapacityExhausted =
        subtitleTranslationManager?.allUsableKeysInCooldown() == true
    if (translationCapacityExhausted) {
        Log.d(
            PlayerRuntimeController.TAG,
            "AI ladder: all keys in cooldown — skipping AI-on rungs (preferred/classic only)"
        )
    }

    if (!translationCapacityExhausted) {
        val originalContentLanguage = contentLanguage
        val aiSourceIndex = findAiSourceSubtitleTrackIndex(
            subtitleTracks = state.subtitleTracks,
            originalLanguage = originalContentLanguage
        )
        if (aiSourceIndex >= 0) {
            val track = state.subtitleTracks[aiSourceIndex]
            Log.i(
                PlayerRuntimeController.TAG,
                "AI ladder: AI translation source index=$aiSourceIndex lang=${track.language}"
            )
            // Source already chosen — enable AI without re-running source selection (that path
            // prefers embedded and can replace an explicit pick).
            selectSubtitleTrack(aiSourceIndex)
            _uiState.update {
                it.copy(selectedSubtitleTrackIndex = aiSourceIndex, selectedAddonSubtitle = null)
            }
            setAiSubtitleTranslationEnabled(
                true,
                allowPreferredUpgrade = false,
                refreshSource = false
            )
            autoSubtitleSelected = true
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.AI_EMBEDDED,
                    reason = if (originalContentLanguage != null &&
                        PlayerSubtitleUtils.matchesLanguageCode(track.language, originalContentLanguage)
                    ) {
                        "embedded original-language source"
                    } else {
                        "embedded text source"
                    },
                    sourceKind = AiSubtitleSourceKind.EMBEDDED,
                    sourceLabel = track.name,
                    sourceLanguage = track.language,
                    sourceInternalIndex = track.index,
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = false
                )
            )
            return
        }
        if (state.subtitleTracks.isEmpty()) {
            Log.d(
                PlayerRuntimeController.TAG,
                "AI ladder: no embedded text tracks after scan — classic fallback"
            )
        }
    }

    Log.i(PlayerRuntimeController.TAG, "AI ladder: no smart embedded source — classic fallback")
    setAiSubtitleTranslationEnabled(false)
    autoSubtitleSelected = false
    tryAutoSelectPreferredSubtitleFromAvailableTracks(primaryLanguageOnly = true)
    publishAiSubtitleDiagnostics(
        AiSubtitleDiagnostics(
            rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
            reason = if (translationCapacityExhausted) {
                "all keys rate-limited; classic preferred-language auto-select (primary only)"
            } else {
                "classic preferred-language auto-select (primary only)"
            },
            targetLanguage = resolveSubtitleAiTargetLanguageName(),
            model = subtitleAiModel.name,
            userLocked = false
        )
    )
}

/**
 * In-player stream switches use [releasePlayer] with `flushPlaybackState=false`, so AI lock,
 * diagnostics, and [autoSubtitleSelected] would otherwise survive into the next file. That left
 * stale source labels (e.g. French from stream A) and made the ladder treat "nothing selected yet"
 * as keep-disabled. Call this whenever the media file changes without a full flush.
 */
internal fun PlayerRuntimeController.resetSubtitleAiPolicyForNewMedia() {
    val keepDisabled = subtitleDisabledByPersistedPreference ||
        rememberedTrackPreference?.subtitle == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    subtitleTranslationManager?.reset()
    aiSubtitleAutoSelectAttempted = false
    aiSubtitleUserLocked = false
    setUserExplicitSubtitleSelection(keepDisabled)
    autoSubtitleSelected = keepDisabled
    subtitleScoreCache.clear()
    subtitleScoreCacheStreamName = null
    if (!keepDisabled) {
        rememberedTrackPreference = rememberedTrackPreference?.copy(subtitle = null)
    }
    _uiState.update {
        it.copy(
            aiSubtitleTranslationActive = false,
            isAiSubtitleTranslating = false,
            aiSubtitleLastError = null,
                aiSubtitleDiagnostics = null,
            userExplicitSubtitleSelection = keepDisabled,
            showSubtitleTranslateMenuOverlay = false,
            subtitleTranslateMenuOptionId = null,
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1
        )
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "AI policy reset for new media keepDisabled=$keepDisabled"
    )
}

/**
 * @param refreshSource when true (default), pick/refresh an AI pivot via
 * [selectAiTranslationSourceIfAvailable]. Callers that already selected the source
 * (Translate with AI, ladder AI rungs) must pass false — otherwise embedded always wins
 * over a just-selected addon.
 */
internal fun PlayerRuntimeController.setAiSubtitleTranslationEnabled(
    enabled: Boolean,
    allowPreferredUpgrade: Boolean = !aiSubtitleUserLocked,
    refreshSource: Boolean = true
) {
    if (enabled && isUsingMpvEngine()) {
        Log.i(PlayerRuntimeController.TAG, "AI subtitle translation ignored on MPV")
        return
    }
    val manager = ensureSubtitleTranslationManager()
    val apiKeyOk = subtitleAiCredentials.anyUsable() || subtitleAiApiKey.isNotBlank()
    val featureOk = subtitleAiFeatureEnabled
    val effective = enabled && apiKeyOk && featureOk && !isUsingMpvEngine()
    if (!effective) {
        aiSubtitleUserLocked = false
    } else if (allowPreferredUpgrade) {
        aiSubtitleUserLocked = false
    }
    if (manager.isEnabled == effective) {
        if (effective && refreshSource) {
            refreshAiSubtitleSourceAndMaybeUpgrade(allowPreferredUpgrade = allowPreferredUpgrade)
        }
        _uiState.update {
            it.copy(
                aiSubtitleTranslationActive = effective,
                aiSubtitleAvailable = !isUsingMpvEngine() && featureOk && apiKeyOk
            )
        }
        return
    }
    if (effective && refreshSource) {
        selectAiTranslationSourceIfAvailable()
    }
    manager.isEnabled = effective
    if (!effective) {
        manager.reset()
        _uiState.update { it.copy(isAiSubtitleTranslating = false) }
    }
    _uiState.update {
        it.copy(
            aiSubtitleTranslationActive = effective,
            aiSubtitleAvailable = !isUsingMpvEngine() && featureOk && apiKeyOk
        )
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AI subtitle translation enabled=$effective locked=$aiSubtitleUserLocked " +
            "upgrade=$allowPreferredUpgrade refreshSource=$refreshSource"
    )
    if (effective && allowPreferredUpgrade && !aiSubtitleUserLocked) {
        tryUpgradeAiToPreferredEmbeddedSubtitle()
    }
}

/**
 * Manual "translate this subtitle with AI" from the overlay long-press menu.
 */
internal fun PlayerRuntimeController.translateSubtitleWithAi(
    internalTrackIndex: Int?,
    addonSubtitle: Subtitle?
) {
    if (isUsingMpvEngine()) return
    if (!subtitleAiFeatureEnabled) {
        Log.w(PlayerRuntimeController.TAG, "Translate with AI ignored: feature disabled")
        _uiState.update { it.copy(aiSubtitleLastError = TRANSLATION_ERROR_FEATURE_DISABLED) }
        return
    }
    if (!(subtitleAiCredentials.anyUsable() || subtitleAiApiKey.isNotBlank())) {
        Log.w(PlayerRuntimeController.TAG, "Translate with AI ignored: API key missing")
        _uiState.update { it.copy(aiSubtitleLastError = TRANSLATION_ERROR_API_KEY_MISSING) }
        return
    }
    refreshAiSubtitleQuotaExhaustedState()
    if (_uiState.value.aiSubtitleQuotaExhausted) {
        Log.w(PlayerRuntimeController.TAG, "Translate with AI ignored: all keys rate-limited")
        _uiState.update { it.copy(aiSubtitleLastError = TRANSLATION_ERROR_RATE_LIMITED) }
        return
    }
    when {
        internalTrackIndex != null && internalTrackIndex >= 0 -> {
            val track = _uiState.value.subtitleTracks.getOrNull(internalTrackIndex) ?: return
            selectSubtitleTrack(internalTrackIndex)
            _uiState.update {
                it.copy(selectedSubtitleTrackIndex = internalTrackIndex, selectedAddonSubtitle = null)
            }
            aiSubtitleUserLocked = true
            // Do not refreshSource: selectAiTranslationSourceIfAvailable prefers embedded and
            // would replace this explicit track (same for addon path below).
            setAiSubtitleTranslationEnabled(
                true,
                allowPreferredUpgrade = false,
                refreshSource = false
            )
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.MANUAL,
                    reason = "user chose translate with AI",
                    sourceKind = AiSubtitleSourceKind.EMBEDDED,
                    sourceLabel = track.name,
                    sourceLanguage = track.language,
                    sourceInternalIndex = track.index,
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = true
                )
            )
            logAiSubtitleAction(
                action = "Translate with AI",
                source = track.name ?: track.language,
                reason = "user chose translate with AI",
                locked = true,
                rung = AiSubtitleLadderRung.MANUAL
            )
        }
        addonSubtitle != null -> {
            selectAddonSubtitle(addonSubtitle)
            _uiState.update {
                it.copy(selectedAddonSubtitle = addonSubtitle, selectedSubtitleTrackIndex = -1)
            }
            aiSubtitleUserLocked = true
            setAiSubtitleTranslationEnabled(
                true,
                allowPreferredUpgrade = false,
                refreshSource = false
            )
            val score = scoreAddonSubtitleCached(addonSubtitle)
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.MANUAL,
                    reason = "user chose translate with AI",
                    sourceKind = AiSubtitleSourceKind.ADDON,
                    sourceLabel = addonSubtitle.addonName,
                    sourceLanguage = addonSubtitle.lang,
                    matchScore = score.takeIf { it > 0 },
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = true
                )
            )
            logAiSubtitleAction(
                action = "Translate with AI",
                source = addonSubtitle.addonName,
                reason = "user chose translate with AI",
                locked = true,
                rung = AiSubtitleLadderRung.MANUAL
            )
        }
        else -> Log.w(PlayerRuntimeController.TAG, "Translate with AI: no source provided")
    }
}

internal fun PlayerRuntimeController.refreshAiSubtitleSourceAndMaybeUpgrade(
    allowPreferredUpgrade: Boolean = !aiSubtitleUserLocked
) {
    val manager = subtitleTranslationManager ?: return
    if (!manager.isEnabled) return
    if (isUsingMpvEngine()) return
    if (allowPreferredUpgrade && !aiSubtitleUserLocked && !isUserExplicitSubtitleSelection) {
        tryUpgradeAiToPreferredEmbeddedSubtitle()
    }
    if (manager.isEnabled) {
        selectAiTranslationSourceIfAvailable()
    }
}

internal fun PlayerRuntimeController.tryUpgradeAiToPreferredEmbeddedSubtitle(): Boolean {
    val manager = subtitleTranslationManager ?: return false
    if (!manager.isEnabled) return false
    if (aiSubtitleUserLocked) {
        Log.d(PlayerRuntimeController.TAG, "AI upgrade skipped: user locked AI translation")
        return false
    }
    if (isUserExplicitSubtitleSelection) return false
    if (isUsingMpvEngine()) return false

    val state = _uiState.value
    if (state.subtitleStyle.useForcedSubtitles) return false
    val internalIndex = findPreferredLanguageEmbeddedTrackIndex()
    if (internalIndex < 0) return false
    val track = state.subtitleTracks[internalIndex]

    Log.i(
        PlayerRuntimeController.TAG,
        "AI upgrade: preferred embedded index=$internalIndex lang=${track.language}"
    )
    selectSubtitleTrack(internalIndex)
    _uiState.update {
        it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null)
    }
    autoSubtitleSelected = true
    setAiSubtitleTranslationEnabled(false)
    return true
}

internal fun PlayerRuntimeController.selectAiTranslationSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (isUsingMpvEngine()) return false

    val state = _uiState.value
    val excludeId = if (excludeCurrent) state.selectedAddonSubtitle?.id else null

    // Keep current addon only when user-locked (Translate with AI). Smart never hunts addons.
    val current = state.selectedAddonSubtitle
    if (current != null &&
        current.id != excludeId &&
        !current.isStreamProvided &&
        aiSubtitleUserLocked
    ) {
        Log.i(
            PlayerRuntimeController.TAG,
            "AI source: keeping user-locked addon lang=${current.lang} id=${current.id.take(80)}"
        )
        return true
    }

    if (selectEmbeddedAiSourceIfAvailable(excludeCurrent = excludeCurrent)) return true

    Log.i(PlayerRuntimeController.TAG, "AI source: no usable embedded (addons only via Translate with AI)")
    return false
}

internal fun PlayerRuntimeController.resolveStreamReleaseNameForSubtitleScore(): String {
    return listOfNotNull(streamName, contentName, title)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
        .orEmpty()
}

internal fun scoreAddonSubtitle(streamSource: String, subtitle: Subtitle): Int {
    if (streamSource.isBlank()) return 0
    val key = SubtitleReleaseScoring.subtitleScoreKey(subtitle.id, subtitle.url, subtitle.addonName)
    return SubtitleReleaseScoring.score(streamSource, key)
}

/**
 * Cached score lookup; invalidates when the stream release name changes.
 * Used for overlay badges / diagnostics — not by the Smart ladder.
 */
internal fun PlayerRuntimeController.scoreAddonSubtitleCached(subtitle: Subtitle): Int {
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()
    if (streamSrc.isBlank() || subtitle.isStreamProvided) return 0
    if (subtitleScoreCacheStreamName != streamSrc) {
        subtitleScoreCacheStreamName = streamSrc
        subtitleScoreCache.clear()
    }
    return subtitleScoreCache.getOrPut(subtitle.id) {
        scoreAddonSubtitle(streamSrc, subtitle)
    }
}

internal fun PlayerRuntimeController.selectEmbeddedAiSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (isUsingMpvEngine()) return false
    val state = _uiState.value
    val excludeIndex = if (excludeCurrent) state.selectedSubtitleTrackIndex else -1
    val sourceIndex = findAiSourceSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        excludeIndex = excludeIndex,
        originalLanguage = contentLanguage
    )
    if (sourceIndex < 0) {
        Log.i(PlayerRuntimeController.TAG, "AI source: no usable embedded text track")
        return false
    }
    val alreadySelected =
        state.selectedAddonSubtitle == null && state.selectedSubtitleTrackIndex == sourceIndex
    if (!alreadySelected) {
        val track = state.subtitleTracks[sourceIndex]
        Log.i(
            PlayerRuntimeController.TAG,
            "AI source: selecting embedded index=$sourceIndex lang=${track.language} name=${track.name}"
        )
        selectSubtitleTrack(sourceIndex)
        _uiState.update {
            it.copy(selectedSubtitleTrackIndex = sourceIndex, selectedAddonSubtitle = null)
        }
    }
    return true
}

/**
 * Any usable embedded text track for AI.
 * Prefers original language, then plain over SDH/CC, labeled over unlabeled.
 * Skips forced / songs-and-signs and bitmap codecs (PGS/DVB/VOBSUB).
 */
internal fun findAiSourceSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    excludeIndex: Int = -1,
    originalLanguage: String? = null
): Int {
    fun TrackInfo.isEffectivelyForced(): Boolean =
        isForced || name.contains("forced", ignoreCase = true)

    fun TrackInfo.isUsableSource(): Boolean =
        !isEffectivelyForced() && !isBitmapSubtitleCodec()

    fun bestAmong(predicate: (TrackInfo) -> Boolean): Int {
        val matches = subtitleTracks.withIndex().filter { (index, track) ->
            index != excludeIndex && predicate(track) && track.isUsableSource()
        }
        val plain = matches.firstOrNull { (_, track) ->
            !track.name.contains("SDH", ignoreCase = true) &&
                !track.name.contains("CC", ignoreCase = true)
        }
        return (plain ?: matches.firstOrNull())?.index ?: -1
    }

    if (!originalLanguage.isNullOrBlank()) {
        val original = bestAmong { track ->
            PlayerSubtitleUtils.matchesLanguageCode(track.language, originalLanguage) ||
                trackMatchesPreferredLanguage(track, originalLanguage)
        }
        if (original >= 0) return original
    }

    val labeled = bestAmong { track -> !track.language.isNullOrBlank() }
    if (labeled >= 0) return labeled

    return bestAmong { true }
}

internal fun trackMatchesPreferredLanguage(track: TrackInfo, primaryTarget: String): Boolean {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(primaryTarget)
    return PlayerSubtitleUtils.matchesLanguageCode(track.language, primaryTarget) ||
        PlayerSubtitleUtils.matchesLanguageCode(variant, primaryTarget) ||
        variant == normalizedTarget
}

internal fun PlayerRuntimeController.publishAiSubtitleDiagnostics(diagnostics: AiSubtitleDiagnostics) {
    _uiState.update { it.copy(aiSubtitleDiagnostics = diagnostics) }
}

internal fun PlayerRuntimeController.setUserExplicitSubtitleSelection(explicit: Boolean) {
    isUserExplicitSubtitleSelection = explicit
    if (_uiState.value.userExplicitSubtitleSelection != explicit) {
        _uiState.update { it.copy(userExplicitSubtitleSelection = explicit) }
    }
}

internal fun PlayerRuntimeController.logAiSubtitleAction(
    action: String,
    source: String?,
    reason: String,
    locked: Boolean,
    rung: AiSubtitleLadderRung?
) {
    Log.i(
        PlayerRuntimeController.TAG,
        "$action source=${source?.takeIf { it.isNotBlank() } ?: "-"} " +
            "reason=$reason locked=$locked rung=${rung?.name ?: "-"}"
    )
}

/**
 * S1: clear userLocked + explicit, then re-run [applySubtitleAutoSelectPolicy] / ladder.
 * Not a plain Stop — Smart may keep AI on (S2) or move to preferred/classic (S3/S4).
 */
internal fun PlayerRuntimeController.resetToSmartAutoSubtitleSelection() {
    val prev = _uiState.value.aiSubtitleDiagnostics
    logAiSubtitleAction(
        action = "Reset Smart Auto",
        source = prev?.sourceLabel ?: prev?.sourceLanguage,
        reason = "user_reset_smart",
        locked = false,
        rung = prev?.rung
    )
    aiSubtitleUserLocked = false
    setUserExplicitSubtitleSelection(false)
    autoSubtitleSelected = false
    _uiState.update {
        it.copy(
            aiSubtitleDiagnostics = null,
            userExplicitSubtitleSelection = false
        )
    }
    // Drop MANUAL lock state before ladder; ladder publishes the new rung/diagnostics.
    applySubtitleAutoSelectPolicy()
    val after = _uiState.value.aiSubtitleDiagnostics
    logAiSubtitleAction(
        action = "Reset Smart Auto done",
        source = after?.sourceLabel ?: after?.sourceLanguage,
        reason = after?.reason ?: "policy_applied",
        locked = aiSubtitleUserLocked,
        rung = after?.rung
    )
}

/**
 * Publish MANUAL diagnostics from the current playback source (A3 / toggle AI on).
 */
internal fun PlayerRuntimeController.publishManualAiDiagnosticsFromCurrentSource(reason: String) {
    val state = _uiState.value
    val addon = state.selectedAddonSubtitle
    if (addon != null) {
        val score = scoreAddonSubtitleCached(addon)
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.MANUAL,
                reason = reason,
                sourceKind = AiSubtitleSourceKind.ADDON,
                sourceLabel = addon.addonName,
                sourceLanguage = addon.lang,
                matchScore = score.takeIf { it > 0 },
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = true
            )
        )
        return
    }
    val index = state.selectedSubtitleTrackIndex
    val track = state.subtitleTracks.getOrNull(index)
    if (track != null) {
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.MANUAL,
                reason = reason,
                sourceKind = AiSubtitleSourceKind.EMBEDDED,
                sourceLabel = track.name,
                sourceLanguage = track.language,
                sourceInternalIndex = track.index,
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = true
            )
        )
    }
}

/**
 * A3 enable from Col2 AI option: restore prior AI source or Smart embedded —
 * never bind the incidental classic Col2 pick (CTA Translate does that).
 */
internal fun PlayerRuntimeController.enableAiFromOptionClick() {
    val state = _uiState.value
    val prior = state.aiSubtitleDiagnostics
    val decision = decideAiOptionEnableSource(
        priorSourceKind = prior?.sourceKind,
        priorEmbeddedIndex = prior?.sourceInternalIndex,
        priorSourceLabel = prior?.sourceLabel,
        priorSourceLanguage = prior?.sourceLanguage,
        currentEmbeddedIndex = state.selectedSubtitleTrackIndex,
        currentAddonLabel = state.selectedAddonSubtitle?.addonName,
        currentAddonLanguage = state.selectedAddonSubtitle?.lang,
        tracks = state.subtitleTracks
    )

    when (decision.action) {
        AiOptionEnableSourceAction.KEEP_CURRENT -> Unit
        AiOptionEnableSourceAction.RESTORE_EMBEDDED -> {
            val index = decision.embeddedIndex ?: return
            selectSubtitleTrack(index)
            _uiState.update {
                it.copy(selectedSubtitleTrackIndex = index, selectedAddonSubtitle = null)
            }
        }
        AiOptionEnableSourceAction.RESTORE_ADDON -> {
            val label = prior?.sourceLabel?.trim()
            val langKey = normalizeIndicatorLanguageKey(prior?.sourceLanguage)
            val match = state.addonSubtitles.firstOrNull { addon ->
                val labelOk = label.isNullOrEmpty() ||
                    addon.addonName.equals(label, ignoreCase = true)
                val langOk = langKey == null ||
                    normalizeIndicatorLanguageKey(addon.lang) == langKey
                labelOk && langOk && !addon.isStreamProvided
            }
            if (match != null) {
                selectAddonSubtitle(match)
                _uiState.update {
                    it.copy(selectedAddonSubtitle = match, selectedSubtitleTrackIndex = -1)
                }
            } else {
                selectEmbeddedAiSourceIfAvailable()
            }
        }
        AiOptionEnableSourceAction.PICK_EMBEDDED_SMART -> {
            selectEmbeddedAiSourceIfAvailable()
        }
    }

    aiSubtitleUserLocked = true
    setAiSubtitleTranslationEnabled(
        true,
        allowPreferredUpgrade = false,
        refreshSource = false
    )
    publishManualAiDiagnosticsFromCurrentSource(reason = "user selected AI option")
}

internal fun PlayerRuntimeController.resolveSubtitleAiTargetLanguageName(): String {
    val code = currentPlayerSettingsForReport.subtitleStyle.preferredLanguage
    if (code.isBlank() ||
        code.equals("none", ignoreCase = true) ||
        code.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true)
    ) {
        return Locale.getDefault().displayLanguage.ifBlank { "English" }
    }
    return AVAILABLE_SUBTITLE_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }?.displayName
        ?: runCatching {
            Locale.forLanguageTag(code).displayLanguage
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: code
}
