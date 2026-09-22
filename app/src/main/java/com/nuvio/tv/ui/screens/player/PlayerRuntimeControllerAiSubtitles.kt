package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_CONTENT_BLOCKED
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Creates and observes AI subtitle translation settings for ExoPlayer playback.
 *
 * Auto-select ladder (when "auto-select AI" is on):
 * 1. Preferred-language embedded track
 * 2. AI translation of any usable embedded track
 * 3. High-scoring pivot-language addon as AI source (release-name score ≥ 50)
 * 4. Preferred-language addon subtitle
 *
 * Manual AI picks from the menu are user-locked and skip automatic overrides.
 */
internal fun PlayerRuntimeController.ensureSubtitleTranslationManager(): SubtitleTranslationManager {
    subtitleTranslationManager?.let { return it }
    val manager = SubtitleTranslationManager(
        service = SubtitleTranslationService(
            apiKeyProvider = { subtitleAiApiKey },
            modelProvider = { subtitleAiModel }
        ),
        targetLanguage = resolveSubtitleAiTargetLanguageName(),
        scope = scope
    )
    manager.removeHearingImpaired = currentPlayerSettingsForReport.subtitleStyle.stripSdh
    manager.onTranslatingChanged = { translating ->
        _uiState.update { it.copy(isAiSubtitleTranslating = translating) }
    }
    manager.onBatchResult = { success, error ->
        if (!success && error != null && error != TRANSLATION_ERROR_CONTENT_BLOCKED && error != "RATE_LIMITED") {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle batch failed: $error")
        }
    }
    manager.onUntranslatableSource = {
        if (manager.isEnabled) {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle source has no extractable text; trying next source")
            val switched = selectAiTranslationSourceIfAvailable(excludeCurrent = true)
            if (!switched) {
                Log.w(PlayerRuntimeController.TAG, "No usable AI source left; disabling translation")
                setAiSubtitleTranslationEnabled(false)
                // Fall through to addon ladder if auto-select is on.
                if (canRunAiAutoSelectLadder()) {
                    applyAiAutoSelectLadder()
                }
            }
        }
    }
    subtitleTranslationManager = manager
    return manager
}

internal fun PlayerRuntimeController.observeSubtitleAiSettings() {
    scope.launch {
        combine(
            playerSettingsDataStore.playerSettings,
            deviceLocalPlayerPreferences.subtitleAiApiKey
        ) { settings, apiKey ->
            Triple(settings.subtitleStyle, apiKey, settings.internalPlayerEngine)
        }.distinctUntilChanged().collect { (style, apiKey, _) ->
            subtitleAiApiKey = apiKey
            subtitleAiModel = runCatching {
                SubtitleAiModel.valueOf(style.aiModel)
            }.getOrDefault(SubtitleAiModel.GROQ_LLAMA_70B)
            subtitleAiFeatureEnabled = style.aiEnabled
            subtitleAiAutoSelect = style.aiAutoSelect

            val manager = ensureSubtitleTranslationManager()
            manager.updateService(apiKey, subtitleAiModel)
            manager.targetLanguage = resolveSubtitleAiTargetLanguageName()
            manager.removeHearingImpaired = style.stripSdh

            val canUseAi = !isUsingMpvEngine() &&
                style.aiEnabled &&
                apiKey.isNotBlank()

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
        }
    }
}

/**
 * Single entry for automatic subtitle selection. When AI auto-select is enabled, uses
 * preferred-embedded → AI (embedded or scored addon) → preferred addon.
 * Otherwise keeps the classic preferred-language auto-select.
 */
internal fun PlayerRuntimeController.applySubtitleAutoSelectPolicy() {
    if (isUserExplicitSubtitleSelection) {
        Log.d(PlayerRuntimeController.TAG, "SUB_POLICY stop: user explicit selection")
        return
    }
    if (aiSubtitleUserLocked && subtitleTranslationManager?.isEnabled == true) {
        selectAiTranslationSourceIfAvailable()
        return
    }
    if (canRunAiAutoSelectLadder()) {
        applyAiAutoSelectLadder()
        return
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    if (subtitleTranslationManager?.isEnabled == true) {
        refreshAiSubtitleSourceAndMaybeUpgrade()
    }
}

internal fun PlayerRuntimeController.canRunAiAutoSelectLadder(): Boolean {
    return subtitleAiAutoSelect &&
        subtitleAiFeatureEnabled &&
        subtitleAiApiKey.isNotBlank() &&
        !isUsingMpvEngine()
}

/**
 * Priority: preferred embedded → AI on any embedded → scored pivot addon → preferred addon.
 */
internal fun PlayerRuntimeController.applyAiAutoSelectLadder() {
    if (isUserExplicitSubtitleSelection || aiSubtitleUserLocked) return
    if (!canRunAiAutoSelectLadder()) return

    val state = _uiState.value
    if (state.subtitleStyle.useForcedSubtitles) {
        // Forced mode keeps the classic path (AI does not apply well to forced-only).
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        return
    }

    val targets = subtitleLanguageTargets()
    val primaryTarget = targets.firstOrNull()
    if (primaryTarget == null) {
        autoSubtitleSelected = true
        setAiSubtitleTranslationEnabled(false)
        Log.d(PlayerRuntimeController.TAG, "AI ladder stop: preferred=none")
        return
    }

    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder defer: text tracks not scanned yet")
        return
    }

    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val preferredInternalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = listOf(primaryTarget),
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = selectedAudioTrack
    )
    if (preferredInternalIndex >= 0) {
        val track = state.subtitleTracks[preferredInternalIndex]
        if (trackMatchesPreferredLanguage(track, primaryTarget)) {
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
            return
        }
    }

    val aiSourceIndex = findAiSourceSubtitleTrackIndex(state.subtitleTracks)
    if (aiSourceIndex >= 0) {
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: AI translation source index=$aiSourceIndex " +
                "lang=${state.subtitleTracks[aiSourceIndex].language}"
        )
        // Hold AI over addons — no preferred-addon upgrade while auto ladder chose AI.
        setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
        autoSubtitleSelected = true
        return
    }

    if (state.isLoadingAddonSubtitles) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder defer: waiting for addon subtitles")
        return
    }

    // No embedded text source — try a high-scoring pivot-language addon as AI source.
    val scoredAddon = findBestScoredAddonAiSource(state.addonSubtitles)
    if (scoredAddon != null) {
        val (subtitle, score) = scoredAddon
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: scored addon AI source score=$score lang=${subtitle.lang} " +
                "addon=${subtitle.addonName} id=${subtitle.id.take(80)}"
        )
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
        setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
        autoSubtitleSelected = true
        return
    }

    Log.i(PlayerRuntimeController.TAG, "AI ladder: no AI source — falling back to preferred-language addons")
    setAiSubtitleTranslationEnabled(false)
    autoSubtitleSelected = false
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
}

internal fun PlayerRuntimeController.setAiSubtitleTranslationEnabled(
    enabled: Boolean,
    allowPreferredUpgrade: Boolean = !aiSubtitleUserLocked
) {
    if (enabled && isUsingMpvEngine()) {
        Log.i(PlayerRuntimeController.TAG, "AI subtitle translation ignored on MPV")
        return
    }
    val manager = ensureSubtitleTranslationManager()
    val apiKeyOk = subtitleAiApiKey.isNotBlank()
    val featureOk = subtitleAiFeatureEnabled
    val effective = enabled && apiKeyOk && featureOk && !isUsingMpvEngine()
    if (!effective) {
        aiSubtitleUserLocked = false
    } else if (allowPreferredUpgrade) {
        aiSubtitleUserLocked = false
    }
    if (manager.isEnabled == effective) {
        if (effective) {
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
    if (effective) {
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
        "AI subtitle translation enabled=$effective locked=$aiSubtitleUserLocked upgrade=$allowPreferredUpgrade"
    )
    if (effective && allowPreferredUpgrade && !aiSubtitleUserLocked) {
        tryUpgradeAiToPreferredEmbeddedSubtitle()
    }
}

/**
 * Called after subtitle track / addon updates while AI may be active:
 * upgrade only to preferred-language embedded (never addon); otherwise keep embedded AI source.
 */
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

/**
 * If a preferred-language embedded track is available, switch to it and disable AI.
 * Does not upgrade to addon subtitles (addons are last resort via the AI auto ladder).
 */
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
    val primaryTarget = subtitleLanguageTargets().firstOrNull() ?: return false
    if (state.subtitleStyle.useForcedSubtitles) return false
    if (!hasScannedTextTracksOnce) return false

    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = listOf(primaryTarget),
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = selectedAudioTrack
    )
    if (internalIndex < 0) return false
    val track = state.subtitleTracks[internalIndex]
    if (!trackMatchesPreferredLanguage(track, primaryTarget)) return false

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

/** @deprecated Use [tryUpgradeAiToPreferredEmbeddedSubtitle]. */
internal fun PlayerRuntimeController.tryUpgradeAiToPreferredSubtitle(): Boolean =
    tryUpgradeAiToPreferredEmbeddedSubtitle()

/**
 * Select the best AI translation source: embedded text track first, then a high-scoring
 * pivot-language addon (release-name match ≥ [SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE]).
 */
internal fun PlayerRuntimeController.selectAiTranslationSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (selectEmbeddedAiSourceIfAvailable(excludeCurrent = excludeCurrent)) return true
    if (isUsingMpvEngine()) return false

    val state = _uiState.value
    val excludeId = if (excludeCurrent) state.selectedAddonSubtitle?.id else null
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()

    // Keep the current addon when it still qualifies as a scored AI source.
    val current = state.selectedAddonSubtitle
    if (current != null &&
        current.id != excludeId &&
        !current.isStreamProvided &&
        streamSrc.isNotBlank()
    ) {
        val score = scoreAddonSubtitle(streamSrc, current)
        if (score >= SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE &&
            isUsableAddonAiSourceLanguage(current)
        ) {
            Log.i(
                PlayerRuntimeController.TAG,
                "AI source: keeping addon score=$score lang=${current.lang} id=${current.id.take(80)}"
            )
            return true
        }
    }

    val best = findBestScoredAddonAiSource(
        addonSubtitles = state.addonSubtitles,
        excludeId = excludeId
    )
    if (best == null) {
        Log.i(PlayerRuntimeController.TAG, "AI source: no usable embedded or scored addon")
        return false
    }
    val (subtitle, score) = best
    Log.i(
        PlayerRuntimeController.TAG,
        "AI source: selecting addon score=$score lang=${subtitle.lang} " +
            "addon=${subtitle.addonName} id=${subtitle.id.take(80)}"
    )
    selectAddonSubtitle(subtitle)
    _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
    return true
}

/** Stream release / source name used for weighted subtitle scoring. */
internal fun PlayerRuntimeController.resolveStreamReleaseNameForSubtitleScore(): String {
    return listOfNotNull(streamName, contentName)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

internal fun scoreAddonSubtitle(streamSource: String, subtitle: Subtitle): Int {
    if (streamSource.isBlank()) return 0
    val key = SubtitleReleaseScoring.subtitleScoreKey(subtitle.id, subtitle.url, subtitle.addonName)
    return SubtitleReleaseScoring.score(streamSource, key)
}

/**
 * Best non-preferred-language addon whose release-name score meets the AI source threshold.
 * English is preferred when scores tie.
 */
internal fun PlayerRuntimeController.findBestScoredAddonAiSource(
    addonSubtitles: List<Subtitle>,
    excludeId: String? = null
): Pair<Subtitle, Int>? {
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()
    if (streamSrc.isBlank()) return null

    return addonSubtitles
        .asSequence()
        .filter { !it.isStreamProvided }
        .filter { excludeId == null || it.id != excludeId }
        .filter { isUsableAddonAiSourceLanguage(it) }
        .map { subtitle -> subtitle to scoreAddonSubtitle(streamSrc, subtitle) }
        .filter { (_, score) -> score >= SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE }
        .maxWithOrNull(
            compareByDescending<Pair<Subtitle, Int>> { it.second }
                .thenByDescending { PlayerSubtitleUtils.matchesLanguageCode(it.first.lang, "en") }
        )
}

/** Addon AI sources must not be the preferred (target) language — those display natively. */
internal fun PlayerRuntimeController.isUsableAddonAiSourceLanguage(subtitle: Subtitle): Boolean {
    val preferred = subtitleLanguageTargets().firstOrNull() ?: return true
    return !PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, preferred)
}

/**
 * Select the best embedded text track as AI translation source.
 * Returns true when a source track was selected (or was already selected).
 */
internal fun PlayerRuntimeController.selectEmbeddedAiSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (isUsingMpvEngine()) return false
    val state = _uiState.value
    val excludeIndex = if (excludeCurrent) state.selectedSubtitleTrackIndex else -1
    // When an addon is currently selected, do not treat the embedded scan as "already selected".
    val sourceIndex = findAiSourceSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        excludeIndex = excludeIndex
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
        // Do not mark as user-explicit — preferred auto-select / upgrade must still run.
        selectSubtitleTrack(sourceIndex)
        _uiState.update {
            it.copy(selectedSubtitleTrackIndex = sourceIndex, selectedAddonSubtitle = null)
        }
    }
    return true
}

/**
 * Any usable embedded text track for AI (any language).
 * Prefers plain over SDH/CC, labeled language over unlabeled.
 * Skips forced / songs-and-signs and bitmap codecs (PGS/DVB/VOBSUB).
 */
internal fun findAiSourceSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    excludeIndex: Int = -1
): Int {
    fun TrackInfo.isEffectivelyForced(): Boolean =
        isForced || name.contains("forced", ignoreCase = true)

    fun TrackInfo.isBitmapCodec(): Boolean {
        val c = codec?.uppercase(Locale.ROOT) ?: return false
        return c == "PGS" || c == "DVB" || c.contains("VOB") || c.contains("PGS")
    }

    fun TrackInfo.isUsableSource(): Boolean = !isEffectivelyForced() && !isBitmapCodec()

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

internal fun PlayerRuntimeController.isCurrentlyOnPreferredSubtitleLanguage(): Boolean {
    val targets = subtitleLanguageTargets()
    val primary = targets.firstOrNull() ?: return false
    val state = _uiState.value
    val addon = state.selectedAddonSubtitle
    if (addon != null) {
        return PlayerSubtitleUtils.matchesLanguageCode(addon.lang, primary)
    }
    val track = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex) ?: return false
    return trackMatchesPreferredLanguage(track, primary)
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
