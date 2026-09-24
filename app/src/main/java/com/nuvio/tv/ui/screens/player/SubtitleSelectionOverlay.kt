@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"
private const val SubtitleFocusTag = "SubtitleFocus"

internal const val SubtitleAiOptionId = "ai:translate"

private const val RailFadeDurationMs = 120
private val InfoRailWidth = 280.dp
private val SelectedWithoutFocusAlpha = 0.5f
private val LanguageBrowsedWithoutFocusAlpha = 0.18f
/** F1/F2 yellow accent for the current AI translation source. */
private val AiSourceIndicatorYellow = Color(0xFFFFD54F)

@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    streamReleaseName: String? = null,
    useLibass: Boolean = false,
    isUsingMpv: Boolean = false,
    aiSubtitleAvailable: Boolean = false,
    aiSubtitleQuotaExhausted: Boolean = false,
    aiSubtitleTranslationActive: Boolean = false,
    isAiSubtitleTranslating: Boolean = false,
    aiSubtitleDiagnostics: AiSubtitleDiagnostics? = null,
    aiSubtitleLastError: String? = null,
    userExplicitSubtitleSelection: Boolean = false,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onToggleAiTranslation: () -> Unit = {},
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val unknownLabel = stringResource(R.string.subtitle_language_unknown)
    val builtInLabel = stringResource(R.string.subtitle_built_in)
    val forcedLabel = stringResource(R.string.sub_forced_lang)
    val sessionPreferredLanguage = remember(visible) { subtitleStyle.preferredLanguage }
    val sessionSecondaryPreferredLanguage = remember(visible) { subtitleStyle.secondaryPreferredLanguage }
    val sessionShowOnlyPreferredLanguages = remember(visible) { subtitleStyle.showOnlyPreferredLanguages }
    val sessionSelectedInternalIndex = remember(visible) { selectedInternalIndex }
    val sessionInternalTracks = remember(visible) { internalTracks.map(TrackInfo::copy) }
    val sessionAddonSubtitles = remember(visible, addonSubtitles) { addonSubtitles.map(Subtitle::copy) }
    val sessionSelectedAddonSubtitle = remember(visible) { selectedAddonSubtitle?.copy() }
    val sessionInstalledSubtitleAddonOrder = remember(visible) { installedSubtitleAddonOrder.toList() }
    val sessionStreamReleaseName = remember(visible, streamReleaseName) {
        streamReleaseName?.trim().orEmpty()
    }
    val sessionScoreByOptionId = remember(visible, sessionAddonSubtitles, sessionStreamReleaseName) {
        if (!visible || sessionStreamReleaseName.isBlank()) {
            emptyMap()
        } else {
            sessionAddonSubtitles
                .filter { !it.isStreamProvided }
                .associate { subtitle ->
                    addonSubtitleOptionId(subtitle) to scoreAddonSubtitle(sessionStreamReleaseName, subtitle)
                }
        }
    }
    val sessionIsLoadingAddons = isLoadingAddons
    val aiOptionBadge = stringResource(R.string.sub_ai_option_badge)
    val aiOptionMeta = stringResource(R.string.sub_ai_option_meta)
    val aiOptionTranslatingMeta = stringResource(R.string.sub_ai_translating)
    val aiOptionApiKeyMeta = stringResource(R.string.sub_ai_api_key)
    val sessionSelectedSubtitleLanguageKey = remember(visible) {
        selectedSubtitleLanguageKey(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = sessionSelectedInternalIndex,
            selectedAddonSubtitle = sessionSelectedAddonSubtitle
        )
    }
    // K1: preferred Col1 count includes synthetic AI whenever that option is listed.
    val aiOptionListedOnPreferred =
        (aiSubtitleAvailable || aiSubtitleTranslationActive)
    val languageItems = remember(
        visible,
        sessionAddonSubtitles,
        aiSubtitleAvailable,
        aiSubtitleTranslationActive
    ) {
        buildSubtitleLanguageRailItems(
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            preferredLanguage = sessionPreferredLanguage,
            secondaryPreferredLanguage = sessionSecondaryPreferredLanguage,
            showOnlyPreferredLanguages = sessionShowOnlyPreferredLanguages,
            currentLanguageKey = sessionSelectedSubtitleLanguageKey,
            noneLabel = noneLabel,
            unknownLabel = unknownLabel,
            includeSyntheticAiOnPreferred = aiOptionListedOnPreferred
        )
    }
    val preferredLanguageKey = remember(visible) {
        normalizeOverlayLanguageKey(sessionPreferredLanguage)
    }
    val sessionInitialLanguageKey = remember(
        visible,
        languageItems,
        sessionSelectedSubtitleLanguageKey,
        aiSubtitleTranslationActive,
        preferredLanguageKey
    ) {
        when {
            aiSubtitleTranslationActive && languageItems.any { it.key == preferredLanguageKey } -> preferredLanguageKey
            else -> sessionSelectedSubtitleLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
                ?: languageItems.firstOrNull { it.key != SubtitleOffLanguageKey }?.key
                ?: SubtitleOffLanguageKey
        }
    }
    val sessionInitialSelectedOptionId = remember(
        visible,
        sessionInitialLanguageKey,
        sessionSelectedSubtitleLanguageKey,
        aiSubtitleTranslationActive,
        preferredLanguageKey
    ) {
        when {
            aiSubtitleTranslationActive && sessionInitialLanguageKey == preferredLanguageKey -> SubtitleAiOptionId
            else -> {
                val optionId = selectedSubtitleOptionId(
                    internalTracks = sessionInternalTracks,
                    selectedInternalIndex = sessionSelectedInternalIndex,
                    selectedAddonSubtitle = sessionSelectedAddonSubtitle
                )
                optionId.takeIf { sessionInitialLanguageKey == sessionSelectedSubtitleLanguageKey }
            }
        }
    }
    fun buildSessionOptions(
        languageKey: String,
        activeSelectedOptionId: String?,
        aiSourceOptionId: String? = null
    ): List<SubtitleOptionRailItem> {
        return buildSubtitleOptionRailItems(
            selectedLanguageKey = languageKey,
            preferredLanguageKey = preferredLanguageKey,
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            installedAddonOrder = sessionInstalledSubtitleAddonOrder,
            selectedOptionId = activeSelectedOptionId,
            scoreByOptionId = sessionScoreByOptionId,
            builtInLabel = builtInLabel,
            forcedLabel = forcedLabel,
            unknownLabel = unknownLabel,
            aiSubtitleAvailable = aiSubtitleAvailable || aiSubtitleTranslationActive,
            aiSubtitleTranslationActive = aiSubtitleTranslationActive,
            isAiSubtitleTranslating = isAiSubtitleTranslating,
            aiOptionBadge = aiOptionBadge,
            aiOptionMeta = aiOptionMeta,
            aiOptionTranslatingMeta = aiOptionTranslatingMeta,
            aiOptionApiKeyMeta = aiOptionApiKeyMeta,
            aiSourceOptionId = aiSourceOptionId
        )
    }
    val aiSourceIndicators = remember(
        aiSubtitleTranslationActive,
        aiSubtitleDiagnostics,
        selectedInternalIndex,
        selectedAddonSubtitle,
        internalTracks,
        sessionInternalTracks,
        sessionAddonSubtitles,
        languageItems
    ) {
        if (!aiSubtitleTranslationActive) {
            AiSourceIndicatorDecision.Hidden
        } else {
            val diag = aiSubtitleDiagnostics
            val tracksForResolve = sessionInternalTracks.ifEmpty { internalTracks }
            val rawSourceLangKey = when {
                !diag?.sourceLanguage.isNullOrBlank() ->
                    normalizeOverlayLanguageKey(diag!!.sourceLanguage)
                selectedAddonSubtitle != null ->
                    normalizeOverlayLanguageKey(selectedAddonSubtitle.lang)
                selectedInternalIndex >= 0 ->
                    tracksForResolve.firstOrNull { it.index == selectedInternalIndex }
                        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
                else -> null
            }
            val addonMeta = sessionAddonSubtitles.map { subtitle ->
                addonSubtitleOptionId(subtitle) to
                    "${subtitle.addonName}\u0000${normalizeOverlayLanguageKey(subtitle.lang)}"
            }
            val sourceOptionId = resolveAiSourceOptionId(
                sourceKind = diag?.sourceKind,
                selectedInternalIndex = selectedInternalIndex,
                selectedAddonOptionId = selectedAddonSubtitle?.let { addonSubtitleOptionId(it) },
                tracks = tracksForResolve,
                diagnosticsInternalIndex = diag?.sourceInternalIndex,
                sourceLanguage = diag?.sourceLanguage,
                sourceLabel = diag?.sourceLabel,
                addonOptionIdsByLabelLang = addonMeta
            )
            val railLangKey = resolveIndicatorLanguageKeyForRail(
                sourceLanguageKey = rawSourceLangKey,
                availableLanguageKeys = languageItems.map { it.key }
            )
            decideAiSourceIndicators(
                translationActive = true,
                sourceLanguageKey = railLangKey,
                sourceOptionId = sourceOptionId,
                rung = diag?.rung
            )
        }
    }
    val sessionInitialSubtitleOptions = remember(visible) {
        buildSessionOptions(sessionInitialLanguageKey, sessionInitialSelectedOptionId, aiSourceIndicators.optionId)
    }
    val sessionInitialOptionTargetId = remember(visible) {
        sessionInitialSelectedOptionId
            ?.takeIf { id -> sessionInitialSubtitleOptions.any { it.id == id } }
            ?: sessionInitialSubtitleOptions.firstOrNull()?.id
    }

    // Browsed language for Col2 (navigation only — does not drive playback).
    var browsedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey)
    }
    // Playback selection highlighted with ✓ in Col2.
    var selectedOptionId by remember(visible) {
        mutableStateOf(sessionInitialSelectedOptionId)
    }
    val subtitleOptions = remember(
        browsedLanguageKey,
        selectedOptionId,
        sessionInternalTracks,
        sessionAddonSubtitles,
        sessionInstalledSubtitleAddonOrder,
        sessionScoreByOptionId,
        aiSubtitleAvailable,
        aiSubtitleTranslationActive,
        isAiSubtitleTranslating,
        preferredLanguageKey,
        aiOptionBadge,
        aiOptionMeta,
        aiOptionTranslatingMeta,
        aiOptionApiKeyMeta,
        aiSourceIndicators.optionId
    ) {
        buildSessionOptions(browsedLanguageKey, selectedOptionId, aiSourceIndicators.optionId)
    }
    val playbackSelectedOption = remember(selectedOptionId, subtitleOptions, aiSubtitleTranslationActive) {
        subtitleOptions.firstOrNull { it.id == selectedOptionId && it.isSelected }
            ?: subtitleOptions.firstOrNull { it.isSelected }
            ?: SubtitleAiOptionId.takeIf { aiSubtitleTranslationActive && browsedLanguageKey == preferredLanguageKey }
                ?.let { id -> subtitleOptions.firstOrNull { it.id == id } }
    }
    var lastFocusedLanguageKey by remember(visible) {
        mutableStateOf(sessionInitialLanguageKey.takeIf { key -> languageItems.any { it.key == key } })
    }
    var optionFocusMemory by remember(visible) {
        mutableStateOf<Map<String, String>>(
            sessionInitialOptionTargetId
                ?.let { mapOf(sessionInitialLanguageKey to it) }
                ?: emptyMap()
        )
    }
    var optionEntryLanguageKey by remember(visible) { mutableStateOf(sessionInitialLanguageKey) }
    var infoEntryOptionId by remember(visible) { mutableStateOf(sessionInitialOptionTargetId) }
    var activeRail by remember(visible) { mutableStateOf<OverlayFocusRail?>(null) }
    var activeOptionFocusId by remember(visible) { mutableStateOf<String?>(sessionInitialOptionTargetId) }
    var activeInfoFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var languageFocusToken by remember(visible) { mutableStateOf(0) }
    var optionFocusToken by remember(visible) { mutableStateOf(0) }
    /** Bumped only from Col2 `onOptionFocused` — real FocusRequester success, not optimistic state. */
    var optionFocusGeneration by remember(visible) { mutableStateOf(0) }
    var infoFocusToken by remember(visible) { mutableStateOf(0) }
    var pendingLanguageFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusId by remember(visible) { mutableStateOf<String?>(null) }
    var pendingOptionFocusLanguageKey by remember(visible) { mutableStateOf<String?>(null) }
    var pendingInfoFocusKey by remember(visible) { mutableStateOf<String?>(null) }
    val overlaySessionKey = remember(visible) { Any() }
    val languageInitialVisibleIndex = remember(visible, languageItems, sessionInitialLanguageKey) {
        preferredVisibleStartIndex(languageItems.indexOfFirst { it.key == sessionInitialLanguageKey })
    }
    val optionInitialVisibleIndex = remember(visible, sessionInitialSubtitleOptions, sessionInitialSelectedOptionId) {
        preferredVisibleStartIndex(sessionInitialSubtitleOptions.indexOfFirst { it.id == sessionInitialSelectedOptionId })
    }
    val languageListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = languageInitialVisibleIndex)
    }
    val optionListState = remember(overlaySessionKey) {
        LazyListState(firstVisibleItemIndex = optionInitialVisibleIndex)
    }
    val languageItemRequesters = rememberFocusRequesterMap(languageItems.map { it.key })
    val optionItemRequesters = rememberFocusRequesterMap(subtitleOptions.map { it.id })
    val infoTranslateRequester = remember { FocusRequester() }
    val optionRailVisible = browsedLanguageKey != SubtitleOffLanguageKey
    // N1: Col3 content only when focus is on Col2 or Col3 (not Col1).
    val infoContentVisible = optionRailVisible &&
        (activeRail == OverlayFocusRail.OPTION || activeRail == OverlayFocusRail.INFO)
    val optionTargetId: String? = remember(subtitleOptions, optionFocusMemory, browsedLanguageKey, selectedOptionId) {
        selectedOptionId?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: optionFocusMemory[browsedLanguageKey]?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: subtitleOptions.firstOrNull()?.id
    }
    val focusedOption = remember(activeOptionFocusId, subtitleOptions, playbackSelectedOption) {
        subtitleOptions.firstOrNull { it.id == activeOptionFocusId } ?: playbackSelectedOption
    }
    val infoAnchorOption = remember(infoEntryOptionId, subtitleOptions) {
        infoEntryOptionId?.let { id -> subtitleOptions.firstOrNull { it.id == id } }
    }
    // Col3 shows the focused Col2 option; when on INFO, keep the option that
    // was focused at Right (infoEntryOptionId) so CTA/Info do not jump to playback.
    val infoDisplayOption = when (activeRail) {
        OverlayFocusRail.OPTION -> focusedOption
        OverlayFocusRail.INFO -> infoAnchorOption ?: focusedOption ?: playbackSelectedOption
        else -> playbackSelectedOption
    }
    val rateLimitedAll = stringResource(R.string.sub_ai_error_rate_limited_all)
    val rateLimited = stringResource(R.string.sub_ai_error_rate_limited)
    val apiKeyMissing = stringResource(R.string.sub_ai_error_api_key_missing)
    val insufficientCredits = stringResource(R.string.sub_ai_error_insufficient_credits)
    val providerError = stringResource(R.string.sub_ai_error_generic)
    val translatingLabel = stringResource(R.string.sub_ai_translating)
    val aiOptionMetaLabel = stringResource(R.string.sub_ai_option_meta)
    val isAiInfoOption = infoDisplayOption?.kind == SubtitleOptionKind.AI
    val mappedAiError = when {
        aiSubtitleLastError.isNullOrBlank() -> null
        aiSubtitleLastError.equals("RATE_LIMITED", ignoreCase = true) ||
            aiSubtitleLastError.contains("429") ||
            aiSubtitleLastError.contains("rate limit", ignoreCase = true) ->
            if (aiSubtitleQuotaExhausted) rateLimitedAll else rateLimited
        aiSubtitleLastError.equals("API key missing", ignoreCase = true) -> apiKeyMissing
        aiSubtitleLastError.equals("INSUFFICIENT_CREDITS", ignoreCase = true) ||
            aiSubtitleLastError.contains("credit balance", ignoreCase = true) ||
            aiSubtitleLastError.contains("insufficient credit", ignoreCase = true) ->
            insufficientCredits
        else -> providerError
    }
    // AI error / translating / meta only on the AI option card (Bug 3).
    val infoStatusLine = when {
        isAiInfoOption && mappedAiError != null -> mappedAiError
        isAiInfoOption && aiSubtitleQuotaExhausted -> rateLimitedAll
        isAiInfoOption && isAiSubtitleTranslating -> translatingLabel
        isAiInfoOption && aiSubtitleTranslationActive -> aiOptionMetaLabel
        else -> infoDisplayOption?.meta
    }
    val infoRailDecision = remember(
        infoDisplayOption,
        playbackSelectedOption,
        activeRail,
        aiSubtitleAvailable,
        aiSubtitleQuotaExhausted,
        aiSubtitleTranslationActive,
        isUsingMpv,
        aiSubtitleDiagnostics,
        infoStatusLine,
        userExplicitSubtitleSelection
    ) {
        val snapshot = infoDisplayOption?.toInfoSnapshot(unknownLabel = unknownLabel)
        val selectedId = playbackSelectedOption?.id
        decideSubtitleInfoRail(
            displayOption = snapshot,
            isPlaybackSelected = snapshot != null && snapshot.id == selectedId,
            diagnostics = aiSubtitleDiagnostics?.toInfoSnapshot(),
            statusLine = infoStatusLine,
            aiAvailable = aiSubtitleAvailable,
            aiQuotaExhausted = aiSubtitleQuotaExhausted,
            isUsingMpv = isUsingMpv,
            userExplicitSelection = userExplicitSubtitleSelection,
            translationActive = aiSubtitleTranslationActive
        )
    }
    val infoCtaState = infoRailDecision.cta
    var pendingPostActionFocus by remember(visible) { mutableStateOf<String?>(null) }

    fun requestLanguageFocus(targetKey: String?) {
        val resolvedKey = targetKey
            ?.takeIf { key -> languageItems.any { it.key == key } }
            ?: languageItems.firstOrNull()?.key
            ?: return
        pendingLanguageFocusKey = resolvedKey
        languageFocusToken += 1
    }

    fun requestOptionFocus(
        targetId: String?,
        languageKey: String = browsedLanguageKey,
        reason: String,
        force: Boolean = false
    ) {
        val resolvedId = targetId ?: subtitleOptions.firstOrNull()?.id
            ?: return
        // Never treat "state says OPTION" as real DPAD focus when the CTA/INFO still owns it,
        // or when a post-CTA settle must re-fire FocusRequester after the button leaves composition.
        if (!force && pendingOptionFocusId == resolvedId && pendingOptionFocusLanguageKey == languageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=duplicate_pending source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        if (
            !force &&
            activeRail == OverlayFocusRail.OPTION &&
            activeInfoFocusKey == null &&
            languageKey == browsedLanguageKey &&
            activeOptionFocusId == resolvedId
        ) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_skip reason=already_focused source=$reason language=$languageKey id=$resolvedId"
            )
            return
        }
        pendingOptionFocusId = resolvedId
        pendingOptionFocusLanguageKey = languageKey
        Log.d(
            SubtitleFocusTag,
            "option_restore_schedule source=$reason language=$languageKey id=$resolvedId force=$force"
        )
        optionFocusToken += 1
    }

    fun jumpFocusToPreferredAi(reason: String, force: Boolean = false) {
        browsedLanguageKey = preferredLanguageKey
        selectedOptionId = SubtitleAiOptionId
        optionFocusMemory = optionFocusMemory + (preferredLanguageKey to SubtitleAiOptionId)
        infoEntryOptionId = SubtitleAiOptionId
        activeInfoFocusKey = null
        // Schedule FocusRequester before claiming OPTION ownership for skip checks.
        requestOptionFocus(
            targetId = SubtitleAiOptionId,
            languageKey = preferredLanguageKey,
            reason = reason,
            force = force
        )
        activeOptionFocusId = SubtitleAiOptionId
        activeRail = OverlayFocusRail.OPTION
    }

    fun applyResetSmartFocusFromPlayback(force: Boolean = false) {
        val diag = aiSubtitleDiagnostics
        val rung = diag?.rung ?: return
        val playbackLang = when {
            selectedAddonSubtitle != null -> normalizeOverlayLanguageKey(selectedAddonSubtitle.lang)
            selectedInternalIndex >= 0 ->
                internalTracks.firstOrNull { it.index == selectedInternalIndex }
                    ?.let { normalizeOverlayLanguageKeyForTrack(it) }
                    ?: preferredLanguageKey
            else -> preferredLanguageKey
        }
        val playbackOptionId = selectedSubtitleOptionId(
            internalTracks = internalTracks,
            selectedInternalIndex = selectedInternalIndex,
            selectedAddonSubtitle = selectedAddonSubtitle
        )
        val focus = decideResetSmartFocus(
            rung = rung,
            preferredLanguageKey = preferredLanguageKey,
            playbackLanguageKey = playbackLang,
            playbackOptionId = playbackOptionId
        )
        browsedLanguageKey = focus.languageKey
        selectedOptionId = focus.optionId
        optionFocusMemory = optionFocusMemory + (focus.languageKey to focus.optionId)
        infoEntryOptionId = focus.optionId
        activeInfoFocusKey = null
        requestOptionFocus(
            targetId = focus.optionId,
            languageKey = focus.languageKey,
            reason = "reset_smart",
            force = force
        )
        activeOptionFocusId = focus.optionId
        activeRail = OverlayFocusRail.OPTION
    }

    fun requestInfoFocus(reason: String) {
        if (!infoCtaState.canMoveFocusToCta) return
        val focusKey = when (infoCtaState.action) {
            SubtitleInfoCtaAction.RESET_TO_SMART_AUTO -> InfoFocusKey.ResetSmart
            else -> InfoFocusKey.Translate
        }
        pendingInfoFocusKey = focusKey
        Log.d(SubtitleFocusTag, "info_focus_schedule source=$reason key=$focusKey")
        infoFocusToken += 1
    }

    fun moveFocusToLanguageRail() {
        requestLanguageFocus(optionEntryLanguageKey)
    }

    fun moveFocusToOptionRail() {
        if (!optionRailVisible) return
        optionEntryLanguageKey = lastFocusedLanguageKey ?: browsedLanguageKey
        requestOptionFocus(
            targetId = optionTargetId,
            languageKey = browsedLanguageKey,
            reason = "language_to_option"
        )
    }

    fun moveFocusBackToOptionRail() {
        val targetId = infoEntryOptionId?.takeIf { id -> subtitleOptions.any { it.id == id } }
            ?: optionTargetId
        requestOptionFocus(
            targetId = targetId,
            languageKey = browsedLanguageKey,
            reason = "info_to_option"
        )
    }

    fun moveFocusToInfoRail() {
        val option = focusedOption ?: return
        // N6: only move Right when the focused option has an enabled CTA.
        // CTA may apply to a focused-but-not-selected option (Translate).
        if (!infoCtaState.canMoveFocusToCta) return
        infoEntryOptionId = option.id
        requestInfoFocus(reason = "option_to_info")
    }

    fun browseLanguage(languageKey: String, reason: String) {
        Log.d(
            SubtitleFocusTag,
            "language_browse key=$languageKey previous=$browsedLanguageKey reason=$reason"
        )
        browsedLanguageKey = languageKey
        lastFocusedLanguageKey = languageKey
        optionEntryLanguageKey = languageKey
        activeRail = OverlayFocusRail.LANGUAGE
        activeInfoFocusKey = null
        if (languageKey != SubtitleOffLanguageKey) {
            val nextOptions = buildSessionOptions(
                languageKey,
                selectedOptionId,
                aiSourceIndicators.optionId
            )
            val nextTargetId = selectedOptionId?.takeIf { id -> nextOptions.any { it.id == id } }
                ?: optionFocusMemory[languageKey]?.takeIf { id -> nextOptions.any { it.id == id } }
                ?: nextOptions.firstOrNull()?.id
            if (nextTargetId != null) {
                optionFocusMemory = optionFocusMemory + (languageKey to nextTargetId)
            }
        }
    }

    fun handleOverlayBack() {
        when (activeRail) {
            // Col3: one column back to Col2. Col1/Col2 Back always dismisses.
            OverlayFocusRail.INFO -> moveFocusBackToOptionRail()
            else -> onDismiss()
        }
    }

    BackHandler(enabled = visible) {
        handleOverlayBack()
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 76.dp)
    ) {
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            if (sessionInitialLanguageKey != SubtitleOffLanguageKey && sessionInitialSelectedOptionId != null) {
                Log.d(
                    SubtitleFocusTag,
                    "overlay_open focus=option selectedLanguage=$sessionInitialLanguageKey selectedOption=$sessionInitialSelectedOptionId"
                )
                requestOptionFocus(
                    targetId = sessionInitialSelectedOptionId,
                    languageKey = sessionInitialLanguageKey,
                    reason = "overlay_open"
                )
            } else {
                Log.d(
                    SubtitleFocusTag,
                    "overlay_open focus=language selectedLanguage=$sessionInitialLanguageKey showOption=${sessionInitialLanguageKey != SubtitleOffLanguageKey}"
                )
                requestLanguageFocus(sessionInitialLanguageKey)
            }
        }

        // After Translate/Reset CTAs remove or replace themselves, settle real FocusRequester on Col2.
        // Do not trust activeOptionFocusId alone — CTA disposal orphans DPAD without requestFocus().
        LaunchedEffect(
            pendingPostActionFocus,
            infoCtaState.action,
            infoCtaState.canMoveFocusToCta,
            aiSubtitleDiagnostics?.rung,
            aiSubtitleTranslationActive,
            selectedInternalIndex,
            selectedAddonSubtitle?.id,
            browsedLanguageKey,
            subtitleOptions.map { it.id }
        ) {
            when (pendingPostActionFocus) {
                "translate_with_ai" -> {
                    browsedLanguageKey = preferredLanguageKey
                    selectedOptionId = SubtitleAiOptionId
                    optionFocusMemory = optionFocusMemory + (preferredLanguageKey to SubtitleAiOptionId)
                    infoEntryOptionId = SubtitleAiOptionId
                    if (subtitleOptions.none { it.id == SubtitleAiOptionId }) {
                        return@LaunchedEffect
                    }
                    val genAtStart = optionFocusGeneration
                    // Let the Translate CTA leave (or swap to Reset) before requesting Col2 focus.
                    repeat(2) { withFrameNanos { } }
                    jumpFocusToPreferredAi(reason = "translate_with_ai_settle", force = true)
                    repeat(8) { attempt ->
                        if (
                            optionFocusGeneration > genAtStart &&
                            activeRail == OverlayFocusRail.OPTION &&
                            activeOptionFocusId == SubtitleAiOptionId &&
                            activeInfoFocusKey == null
                        ) {
                            Log.d(
                                SubtitleFocusTag,
                                "post_cta_focus_complete cta=translate target=$SubtitleAiOptionId " +
                                    "lang=$preferredLanguageKey attempt=$attempt gen=$optionFocusGeneration"
                            )
                            pendingPostActionFocus = null
                            return@LaunchedEffect
                        }
                        withFrameNanos { }
                        requestOptionFocus(
                            targetId = SubtitleAiOptionId,
                            languageKey = preferredLanguageKey,
                            reason = "translate_with_ai_retry_$attempt",
                            force = true
                        )
                    }
                    Log.d(
                        SubtitleFocusTag,
                        "post_cta_focus_timeout cta=translate target=$SubtitleAiOptionId " +
                            "rail=$activeRail option=$activeOptionFocusId infoKey=$activeInfoFocusKey " +
                            "gen=$optionFocusGeneration startGen=$genAtStart"
                    )
                    pendingPostActionFocus = null
                }
                "reset_smart" -> {
                    // Wait until policy published a new rung (reset clears diagnostics first).
                    if (aiSubtitleDiagnostics == null) return@LaunchedEffect
                    val genAtStart = optionFocusGeneration
                    applyResetSmartFocusFromPlayback(force = true)
                    // CTA may disappear (C1) or change; settle FocusRequester after recomposition.
                    repeat(2) { withFrameNanos { } }
                    val targetId = activeOptionFocusId ?: selectedOptionId
                    val targetLang = browsedLanguageKey
                    if (targetId != null) {
                        requestOptionFocus(
                            targetId = targetId,
                            languageKey = targetLang,
                            reason = "reset_smart_settle",
                            force = true
                        )
                    }
                    repeat(8) { attempt ->
                        if (
                            optionFocusGeneration > genAtStart &&
                            activeRail == OverlayFocusRail.OPTION &&
                            activeOptionFocusId == targetId &&
                            activeInfoFocusKey == null
                        ) {
                            Log.d(
                                SubtitleFocusTag,
                                "post_cta_focus_complete cta=reset target=$targetId " +
                                    "lang=$targetLang attempt=$attempt gen=$optionFocusGeneration"
                            )
                            pendingPostActionFocus = null
                            return@LaunchedEffect
                        }
                        withFrameNanos { }
                        if (targetId != null) {
                            requestOptionFocus(
                                targetId = targetId,
                                languageKey = targetLang,
                                reason = "reset_smart_retry_$attempt",
                                force = true
                            )
                        }
                    }
                    Log.d(
                        SubtitleFocusTag,
                        "post_cta_focus_timeout cta=reset target=$targetId " +
                            "rail=$activeRail option=$activeOptionFocusId infoKey=$activeInfoFocusKey " +
                            "gen=$optionFocusGeneration startGen=$genAtStart"
                    )
                    pendingPostActionFocus = null
                }
                else -> Unit
            }
        }

        LaunchedEffect(visible, sessionInitialLanguageKey, languageItems) {
            if (!visible) return@LaunchedEffect
            val targetIndex = languageItems.indexOfFirst { it.key == sessionInitialLanguageKey }
            if (targetIndex >= 0) {
                languageListState.scrollItemIntoView(targetIndex)
            }
        }

        LaunchedEffect(visible, infoContentVisible, infoFocusToken, infoCtaState.canMoveFocusToCta) {
            if (!visible || !infoContentVisible || infoFocusToken <= 0 || !infoCtaState.canMoveFocusToCta) {
                return@LaunchedEffect
            }
            val targetKey = pendingInfoFocusKey ?: return@LaunchedEffect
            repeat(8) { attempt ->
                Log.d(
                    SubtitleFocusTag,
                    "info_focus_request attempt=$attempt key=$targetKey activeRail=$activeRail activeInfoKey=$activeInfoFocusKey"
                )
                runCatching {
                    infoTranslateRequester.requestFocusAfterFrames(frames = if (attempt == 0) 2 else 1)
                }
                if (activeRail == OverlayFocusRail.INFO && activeInfoFocusKey == targetKey) {
                    Log.d(SubtitleFocusTag, "info_focus_complete attempt=$attempt key=$targetKey")
                    pendingInfoFocusKey = null
                    return@LaunchedEffect
                }
            }
            Log.d(SubtitleFocusTag, "info_focus_timeout key=$targetKey activeInfoKey=$activeInfoFocusKey")
            pendingInfoFocusKey = null
        }

        Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.subtitle_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
            )
            if (isUsingMpv && subtitleStyle.aiEnabled) {
                Text(
                    text = stringResource(R.string.sub_ai_unavailable_mpv),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SubtitleLanguageRail(
                    items = languageItems,
                    browsedLanguageKey = browsedLanguageKey,
                    aiSourceLanguageKey = aiSourceIndicators.languageKey.takeIf { aiSourceIndicators.visible },
                    focusOnLanguageRail = activeRail == OverlayFocusRail.LANGUAGE || activeRail == null,
                    listState = languageListState,
                    itemFocusRequesters = languageItemRequesters,
                    focusTargetKey = pendingLanguageFocusKey,
                    focusToken = languageFocusToken,
                    onFocusRequestConsumed = {
                        pendingLanguageFocusKey = null
                    },
                    onMoveRight = if (optionRailVisible && subtitleOptions.isNotEmpty()) ::moveFocusToOptionRail else null,
                    onBack = ::handleOverlayBack,
                    onLanguageClicked = { languageKey ->
                        if (languageKey == SubtitleOffLanguageKey) {
                            browsedLanguageKey = SubtitleOffLanguageKey
                            selectedOptionId = null
                            activeRail = OverlayFocusRail.LANGUAGE
                            onDisableSubtitles()
                        } else {
                            // N3: click browses only (no playback change). Like Right, OK
                            // also moves real focus into Col2. Resolve the target from the
                            // clicked language immediately — subtitleOptions is still stale
                            // until the next composition.
                            browseLanguage(languageKey, reason = "language_click")
                            val nextOptions = buildSessionOptions(
                                languageKey,
                                selectedOptionId,
                                aiSourceIndicators.optionId
                            )
                            val nextTargetId = selectedOptionId
                                ?.takeIf { id -> nextOptions.any { it.id == id } }
                                ?: optionFocusMemory[languageKey]
                                    ?.takeIf { id -> nextOptions.any { it.id == id } }
                                ?: nextOptions.firstOrNull()?.id
                            if (nextTargetId != null) {
                                optionEntryLanguageKey = languageKey
                                requestOptionFocus(
                                    targetId = nextTargetId,
                                    languageKey = languageKey,
                                    reason = "language_click_to_option"
                                )
                            }
                        }
                    },
                    onLanguageFocused = { key ->
                        browseLanguage(key, reason = "language_focused")
                    }
                )

                if (optionRailVisible) {
                    RailFadeIn(visible = true) {
                        SubtitleOptionsRail(
                            selectedLanguageKey = browsedLanguageKey,
                            options = subtitleOptions,
                            isLoadingAddons = sessionIsLoadingAddons,
                            listState = optionListState,
                            itemFocusRequesters = optionItemRequesters,
                            focusTargetId = pendingOptionFocusId,
                            focusLanguageKey = pendingOptionFocusLanguageKey,
                            focusToken = optionFocusToken,
                            onFocusRequestConsumed = {
                                pendingOptionFocusId = null
                                pendingOptionFocusLanguageKey = null
                            },
                            onOptionFocused = {
                                optionFocusMemory = optionFocusMemory + (browsedLanguageKey to it)
                                infoEntryOptionId = it
                                activeOptionFocusId = it
                                activeRail = OverlayFocusRail.OPTION
                                activeInfoFocusKey = null
                                optionFocusGeneration += 1
                            },
                            onMoveLeft = ::moveFocusToLanguageRail,
                            onMoveRight = ::moveFocusToInfoRail,
                            onBack = ::handleOverlayBack,
                            onInternalTrackSelected = { optionId, trackIndex ->
                                if (selectedOptionId == optionId) return@SubtitleOptionsRail // G1
                                selectedOptionId = optionId
                                optionFocusMemory = optionFocusMemory + (browsedLanguageKey to optionId)
                                infoEntryOptionId = optionId
                                activeOptionFocusId = optionId
                                activeRail = OverlayFocusRail.OPTION
                                onInternalTrackSelected(trackIndex)
                            },
                            onAddonSubtitleSelected = { optionId, subtitle ->
                                if (selectedOptionId == optionId) return@SubtitleOptionsRail // G1
                                selectedOptionId = optionId
                                optionFocusMemory = optionFocusMemory + (browsedLanguageKey to optionId)
                                infoEntryOptionId = optionId
                                activeOptionFocusId = optionId
                                activeRail = OverlayFocusRail.OPTION
                                onAddonSubtitleSelected(subtitle)
                            },
                            onAiOptionSelected = { optionId ->
                                val action = decideAiOptionClickAction(
                                    aiOptionAlreadySelected = selectedOptionId == optionId &&
                                        aiSubtitleTranslationActive,
                                    translationActive = aiSubtitleTranslationActive,
                                    userLocked = aiSubtitleDiagnostics?.userLocked == true
                                )
                                when (action) {
                                    AiOptionClickAction.NO_OP -> {
                                        Log.d(
                                            SubtitleFocusTag,
                                            "Select AI option source=${aiSubtitleDiagnostics?.sourceLabel ?: "-"} " +
                                                "reason=already_selected locked=${aiSubtitleDiagnostics?.userLocked == true} " +
                                                "rung=${aiSubtitleDiagnostics?.rung?.name ?: "-"}"
                                        )
                                        return@SubtitleOptionsRail // A1 / G1
                                    }
                                    AiOptionClickAction.SELECT_ONLY -> {
                                        // A2: keep current manual/auto source; only align UI.
                                        selectedOptionId = optionId
                                        optionFocusMemory = optionFocusMemory + (browsedLanguageKey to optionId)
                                        infoEntryOptionId = optionId
                                        activeOptionFocusId = optionId
                                        activeRail = OverlayFocusRail.OPTION
                                        Log.d(
                                            SubtitleFocusTag,
                                            "Select AI option source=${aiSubtitleDiagnostics?.sourceLabel ?: "-"} " +
                                                "reason=select_only_keep_source locked=${aiSubtitleDiagnostics?.userLocked == true} " +
                                                "rung=${aiSubtitleDiagnostics?.rung?.name ?: "-"}"
                                        )
                                    }
                                    AiOptionClickAction.ENABLE_MANUAL -> {
                                        // A3
                                        selectedOptionId = optionId
                                        optionFocusMemory = optionFocusMemory + (browsedLanguageKey to optionId)
                                        infoEntryOptionId = optionId
                                        activeOptionFocusId = optionId
                                        activeRail = OverlayFocusRail.OPTION
                                        onToggleAiTranslation()
                                    }
                                }
                            }
                        )
                    }

                    // L1: always reserve Col3 width when Col2 exists; content only when N1 allows.
                    Box(
                        modifier = Modifier
                            .width(InfoRailWidth)
                            .fillMaxHeight()
                    ) {
                        if (infoContentVisible) {
                            SubtitleInfoRail(
                                selectedOption = infoDisplayOption,
                                decision = infoRailDecision,
                                aiSubtitleDiagnostics = aiSubtitleDiagnostics,
                                aiSubtitleLastError = aiSubtitleLastError,
                                aiSubtitleTranslationActive = aiSubtitleTranslationActive,
                                onMoveLeft = ::moveFocusBackToOptionRail,
                                onBack = ::handleOverlayBack,
                                ctaFocusRequester = infoTranslateRequester,
                                onInfoFocused = {
                                    activeRail = OverlayFocusRail.INFO
                                    activeInfoFocusKey = it
                                    pendingInfoFocusKey = null
                                },
                                onTranslateWithAi = {
                                    // Capture the anchored/focused source (may not be playback-selected).
                                    val option = infoDisplayOption
                                        ?: infoAnchorOption
                                        ?: focusedOption
                                        ?: playbackSelectedOption
                                        ?: subtitleOptions.firstOrNull { it.id == selectedOptionId }
                                    browsedLanguageKey = preferredLanguageKey
                                    selectedOptionId = SubtitleAiOptionId
                                    optionFocusMemory =
                                        optionFocusMemory + (preferredLanguageKey to SubtitleAiOptionId)
                                    infoEntryOptionId = SubtitleAiOptionId
                                    activeInfoFocusKey = null
                                    pendingPostActionFocus = "translate_with_ai"
                                    Log.d(
                                        SubtitleFocusTag,
                                        "post_cta_focus cta=translate target=$SubtitleAiOptionId lang=$preferredLanguageKey"
                                    )
                                    when (option?.kind) {
                                        SubtitleOptionKind.INTERNAL -> {
                                            onEvent(
                                                PlayerEvent.OnTranslateSubtitleWithAi(
                                                    internalTrackIndex = option.internalTrackIndex
                                                )
                                            )
                                        }
                                        SubtitleOptionKind.ADDON -> {
                                            onEvent(
                                                PlayerEvent.OnTranslateSubtitleWithAi(
                                                    addonSubtitle = option.addonSubtitle
                                                )
                                            )
                                        }
                                        SubtitleOptionKind.AI -> onToggleAiTranslation()
                                        null -> onToggleAiTranslation()
                                    }
                                },
                                onResetToSmartAuto = {
                                    activeInfoFocusKey = null
                                    pendingPostActionFocus = "reset_smart"
                                    Log.d(
                                        SubtitleFocusTag,
                                        "post_cta_focus cta=reset pending=true lang=$browsedLanguageKey " +
                                            "option=${selectedOptionId ?: SubtitleAiOptionId}"
                                    )
                                    onEvent(PlayerEvent.OnResetToSmartAuto)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private object InfoFocusKey {
    const val Translate = "info_translate"
    const val ResetSmart = "info_reset_smart"
}

@Composable
private fun RailFadeIn(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    if (!visible) return

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RailFadeDurationMs,
                easing = FastOutLinearInEasing
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer(alpha = alpha.value)
    ) {
        content()
    }
}

@Composable
private fun SubtitleLanguageRail(
    items: List<SubtitleLanguageRailItem>,
    browsedLanguageKey: String,
    aiSourceLanguageKey: String?,
    focusOnLanguageRail: Boolean,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onMoveRight: (() -> Unit)?,
    onBack: () -> Unit,
    onLanguageClicked: (String) -> Unit,
    onLanguageFocused: (String) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val targetKey = focusTargetKey ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.key == targetKey }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        Log.d(
            SubtitleFocusTag,
            "language_restore_request key=$targetKey index=$targetIndex browsed=$browsedLanguageKey firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        itemFocusRequesters[targetKey]?.requestFocusAfterFrames()
        Log.d(
            SubtitleFocusTag,
            "language_restore_complete key=$targetKey index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        onFocusRequestConsumed()
    }

    RailColumn(width = 200.dp, title = stringResource(R.string.subtitle_tab_languages)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
            modifier = Modifier.fillMaxHeight()
        ) {
            items(items = items, key = { item -> item.key }) { item ->
                val isBrowsed = item.key == browsedLanguageKey
                SubtitleLanguageCard(
                    item = item,
                    // V1: ~50% when this language's Col2 is open and focus is elsewhere.
                    emphasized = isBrowsed && !focusOnLanguageRail,
                    showAiSourceDot = item.key == aiSourceLanguageKey,
                    onClick = { onLanguageClicked(item.key) },
                    focusRequester = itemFocusRequesters[item.key],
                    onMoveRight = onMoveRight,
                    onBack = onBack,
                    onFocused = { onLanguageFocused(item.key) }
                )
            }
        }
    }
}

@Composable
private fun SubtitleOptionsRail(
    selectedLanguageKey: String,
    options: List<SubtitleOptionRailItem>,
    isLoadingAddons: Boolean,
    listState: LazyListState,
    itemFocusRequesters: Map<String, FocusRequester>,
    focusTargetId: String?,
    focusLanguageKey: String?,
    focusToken: Int,
    onFocusRequestConsumed: () -> Unit,
    onOptionFocused: (String) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onBack: () -> Unit,
    onInternalTrackSelected: (String, Int) -> Unit,
    onAddonSubtitleSelected: (String, Subtitle) -> Unit,
    onAiOptionSelected: (String) -> Unit
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val requestLanguageKey = focusLanguageKey
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        if (requestLanguageKey != selectedLanguageKey) {
            Log.d(
                SubtitleFocusTag,
                "option_restore_drop reason=language_mismatch requestLanguage=$requestLanguageKey selectedLanguage=$selectedLanguageKey target=$focusTargetId"
            )
            onFocusRequestConsumed()
            return@LaunchedEffect
        }
        val targetId = focusTargetId
            ?.takeIf { id -> options.any { it.id == id } }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        val targetIndex = options.indexOfFirst { it.id == targetId }
            .takeIf { it >= 0 }
            ?: run {
                onFocusRequestConsumed()
                return@LaunchedEffect
            }
        Log.d(
            SubtitleFocusTag,
            "option_restore_request language=$selectedLanguageKey id=$targetId index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        val focused = itemFocusRequesters[targetId]?.requestFocusAfterFrames(frames = 2) == true
        Log.d(
            SubtitleFocusTag,
            "option_restore_complete language=$selectedLanguageKey id=$targetId index=$targetIndex " +
                "firstVisible=${listState.firstVisibleItemIndex} requested=$focused"
        )
        onFocusRequestConsumed()
    }

    RailColumn(width = 300.dp, title = stringResource(R.string.subtitle_dialog_title)) {
        when {
            selectedLanguageKey == SubtitleOffLanguageKey -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_none))
            }

            options.isEmpty() && isLoadingAddons -> {
                OverlayLoadingCard(text = stringResource(R.string.subtitle_loading_addon))
            }

            options.isEmpty() -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_no_addon))
            }

            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    items(items = options, key = { option -> option.id }) { option ->
                        SubtitleOptionCard(
                            item = option,
                            focusRequester = itemFocusRequesters[option.id],
                            onMoveLeft = onMoveLeft,
                            onMoveRight = onMoveRight,
                            onBack = onBack,
                            onFocused = { onOptionFocused(option.id) },
                            onClick = {
                                when (option.kind) {
                                    SubtitleOptionKind.INTERNAL -> {
                                        option.internalTrackIndex?.let { trackIndex ->
                                            onInternalTrackSelected(option.id, trackIndex)
                                        }
                                    }

                                    SubtitleOptionKind.ADDON -> {
                                        option.addonSubtitle?.let { subtitle ->
                                            onAddonSubtitleSelected(option.id, subtitle)
                                        }
                                    }

                                    SubtitleOptionKind.AI -> onAiOptionSelected(option.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleInfoRail(
    selectedOption: SubtitleOptionRailItem?,
    decision: SubtitleInfoRailDecision,
    aiSubtitleDiagnostics: AiSubtitleDiagnostics?,
    aiSubtitleLastError: String?,
    aiSubtitleTranslationActive: Boolean,
    onMoveLeft: () -> Unit,
    onBack: () -> Unit,
    ctaFocusRequester: FocusRequester,
    onInfoFocused: (String) -> Unit,
    onTranslateWithAi: () -> Unit,
    onResetToSmartAuto: () -> Unit
) {
    // L2: no "Info" header — content only. Fill remaining overlay height; no hard 720dp clip.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        SubtitleInfoPane(
            selectedOption = selectedOption,
            decision = decision,
            aiSubtitleDiagnostics = aiSubtitleDiagnostics,
            aiSubtitleLastError = aiSubtitleLastError,
            aiSubtitleTranslationActive = aiSubtitleTranslationActive,
            onMoveLeft = onMoveLeft,
            onBack = onBack,
            ctaFocusRequester = ctaFocusRequester,
            onInfoFocused = onInfoFocused,
            onTranslateWithAi = onTranslateWithAi,
            onResetToSmartAuto = onResetToSmartAuto
        )
    }
}

@Composable
private fun SubtitleInfoPane(
    selectedOption: SubtitleOptionRailItem?,
    decision: SubtitleInfoRailDecision,
    aiSubtitleDiagnostics: AiSubtitleDiagnostics?,
    aiSubtitleLastError: String?,
    aiSubtitleTranslationActive: Boolean,
    onMoveLeft: () -> Unit,
    onBack: () -> Unit,
    ctaFocusRequester: FocusRequester,
    onInfoFocused: (String) -> Unit,
    onTranslateWithAi: () -> Unit,
    onResetToSmartAuto: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    val content = decision.content
    val cta = decision.cta
    val statusLine = content.statusLine
    val displayScore = content.matchScorePercent
    val hasErrorTint = content.kind == SubtitleInfoContentKind.AI && (
        !aiSubtitleLastError.isNullOrBlank() ||
            content.unavailableReason == SubtitleInfoUnavailableReason.RATE_LIMITED ||
            content.unavailableReason == SubtitleInfoUnavailableReason.NO_API_KEY
        )
    val showCard = selectedOption != null ||
        aiSubtitleTranslationActive ||
        aiSubtitleDiagnostics != null ||
        content.kind != SubtitleInfoContentKind.EMPTY

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (showCard) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(NuvioTheme.radii.md))
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceChip(
                        label = content.sourceChipLabel.ifBlank {
                            selectedOption?.sourceLabel
                                ?: stringResource(R.string.sub_ai_option_badge)
                        },
                        selected = false
                    )
                    Text(
                        text = content.title.ifBlank {
                            selectedOption?.title
                                ?: stringResource(R.string.sub_ai_diagnostics_title)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    if (!statusLine.isNullOrBlank()) {
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasErrorTint) {
                                Color(0xFFFF8A80)
                            } else {
                                Color.White.copy(alpha = 0.7f)
                            }
                        )
                    }
                    content.unavailableReason?.let { reason ->
                        val reasonText = when (reason) {
                            SubtitleInfoUnavailableReason.MPV ->
                                stringResource(R.string.sub_ai_unavailable_mpv)
                            SubtitleInfoUnavailableReason.RATE_LIMITED ->
                                stringResource(R.string.sub_ai_error_rate_limited_all)
                            SubtitleInfoUnavailableReason.NO_API_KEY ->
                                stringResource(R.string.sub_ai_error_api_key_missing)
                        }
                        if (reasonText != statusLine) {
                            Text(
                                text = reasonText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF8A80)
                            )
                        }
                    }
                    if (displayScore != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sub_ai_diagnostics_score),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            MatchScoreBadge(scorePercent = displayScore, selected = false)
                        }
                    }
                    content.fields.forEach { field ->
                        if (field.key == SubtitleInfoFieldKey.STATUS ||
                            field.key == SubtitleInfoFieldKey.SCORE ||
                            field.key == SubtitleInfoFieldKey.TRACK_NAME
                        ) {
                            return@forEach
                        }
                        val label = infoFieldLabel(field.key) ?: return@forEach
                        val value = infoFieldDisplayValue(field, aiSubtitleDiagnostics)
                        if (value.isNotBlank()) {
                            Text(
                                text = "$label: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (!selectedOption?.meta.isNullOrBlank() &&
                        selectedOption?.meta != statusLine &&
                        selectedOption?.kind != SubtitleOptionKind.AI &&
                        content.kind != SubtitleInfoContentKind.AI
                    ) {
                        Text(
                            text = selectedOption!!.meta!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        } else {
            OverlayEmptyCard(text = stringResource(R.string.subtitle_none))
        }

        when (cta.action) {
            SubtitleInfoCtaAction.TRANSLATE_WITH_AI -> {
                SubtitleInfoActionCard(
                    label = stringResource(R.string.sub_ai_translate_this),
                    focusKey = InfoFocusKey.Translate,
                    focusRequester = if (cta.focusable) ctaFocusRequester else null,
                    enabled = cta.enabled,
                    focusable = cta.focusable,
                    onClick = { if (cta.enabled) onTranslateWithAi() },
                    onMoveLeft = onMoveLeft,
                    onBack = onBack,
                    onFocused = onInfoFocused,
                    moveLeftKey = moveLeftKey
                )
            }
            SubtitleInfoCtaAction.RESET_TO_SMART_AUTO -> {
                SubtitleInfoActionCard(
                    label = stringResource(R.string.sub_ai_reset_to_smart),
                    focusKey = InfoFocusKey.ResetSmart,
                    focusRequester = if (cta.focusable) ctaFocusRequester else null,
                    enabled = cta.enabled,
                    focusable = cta.focusable,
                    onClick = { if (cta.enabled) onResetToSmartAuto() },
                    onMoveLeft = onMoveLeft,
                    onBack = onBack,
                    onFocused = onInfoFocused,
                    moveLeftKey = moveLeftKey
                )
            }
            SubtitleInfoCtaAction.NONE -> Unit
        }
    }
}

@Composable
private fun infoFieldLabel(key: SubtitleInfoFieldKey): String? = when (key) {
    SubtitleInfoFieldKey.SOURCE -> stringResource(R.string.sub_ai_diagnostics_source)
    SubtitleInfoFieldKey.METHOD -> stringResource(R.string.sub_ai_method_label)
    SubtitleInfoFieldKey.RUNG -> stringResource(R.string.sub_ai_diagnostics_rung)
    SubtitleInfoFieldKey.REASON -> stringResource(R.string.sub_ai_diagnostics_reason)
    SubtitleInfoFieldKey.TARGET -> stringResource(R.string.sub_ai_diagnostics_target)
    SubtitleInfoFieldKey.MODEL -> stringResource(R.string.sub_ai_diagnostics_model)
    SubtitleInfoFieldKey.LOCKED -> stringResource(R.string.sub_ai_diagnostics_locked)
    SubtitleInfoFieldKey.LANGUAGE -> stringResource(R.string.subtitle_tab_languages)
    SubtitleInfoFieldKey.TRACK_NAME -> null
    SubtitleInfoFieldKey.FORMAT -> stringResource(R.string.sub_ai_info_format)
    SubtitleInfoFieldKey.FORCED -> stringResource(R.string.sub_forced_lang)
    SubtitleInfoFieldKey.SDH -> stringResource(R.string.sub_ai_info_sdh)
    SubtitleInfoFieldKey.ADDON_NAME -> stringResource(R.string.sub_ai_info_addon)
    SubtitleInfoFieldKey.FILE_NAME -> stringResource(R.string.sub_ai_info_file)
    SubtitleInfoFieldKey.STATUS, SubtitleInfoFieldKey.SCORE -> null
}

@Composable
private fun infoFieldDisplayValue(
    field: SubtitleInfoField,
    diagnostics: AiSubtitleDiagnostics?
): String = when (field.key) {
    SubtitleInfoFieldKey.TARGET ->
        field.value.takeIf { it.isNotBlank() }?.let(Subtitle::languageCodeToName) ?: field.value
    SubtitleInfoFieldKey.SOURCE -> {
        val fromDiag = listOfNotNull(
            diagnostics?.sourceLabel,
            diagnostics?.sourceLanguage?.let { Subtitle.languageCodeToName(it) }
        ).joinToString(" · ").ifBlank { null }
        fromDiag ?: field.value
    }
    SubtitleInfoFieldKey.METHOD -> when (field.value) {
        "User selected" -> stringResource(R.string.sub_ai_method_user_selected)
        "Automatic" -> stringResource(R.string.sub_ai_method_automatic)
        else -> field.value
    }
    SubtitleInfoFieldKey.LOCKED -> when (field.value) {
        "true" -> stringResource(R.string.sub_ai_locked_on)
        "false" -> stringResource(R.string.sub_ai_locked_off)
        else -> field.value
    }
    SubtitleInfoFieldKey.RUNG -> rungLabel(field.value)
    else -> field.value
}

@Composable
private fun rungLabel(rungName: String): String = when (rungName) {
    AiSubtitleLadderRung.PREFERRED_EMBEDDED.name ->
        stringResource(R.string.sub_ai_rung_preferred_embedded)
    AiSubtitleLadderRung.AI_EMBEDDED.name ->
        stringResource(R.string.sub_ai_rung_ai_embedded)
    AiSubtitleLadderRung.AI_SCORED_ADDON.name ->
        stringResource(R.string.sub_ai_rung_ai_scored_addon)
    AiSubtitleLadderRung.PREFERRED_SCORED_ADDON.name ->
        stringResource(R.string.sub_ai_rung_preferred_scored_addon)
    AiSubtitleLadderRung.CLASSIC_FALLBACK.name ->
        stringResource(R.string.sub_ai_rung_classic_fallback)
    AiSubtitleLadderRung.MANUAL.name ->
        stringResource(R.string.sub_ai_rung_manual)
    AiSubtitleLadderRung.NONE.name ->
        stringResource(R.string.sub_ai_rung_none)
    else -> rungName
}

@Composable
private fun SubtitleInfoActionCard(
    label: String,
    focusKey: String,
    focusRequester: FocusRequester?,
    enabled: Boolean,
    focusable: Boolean = true,
    onClick: () -> Unit,
    onMoveLeft: () -> Unit,
    onBack: () -> Unit,
    onFocused: (String) -> Unit,
    moveLeftKey: Int
) {
    // S8–S9: CTA focus = FocusBackground; never purple when unfocused.
    Card(
        onClick = { if (enabled) onClick() },
        colors = overlayRailCardColors(OverlayRailVisualRole.Cta),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = overlayCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = focusable }
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onMoveLeft()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onBack()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onFocused(focusKey) },
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = 12.dp)
        )
    }
}


@Composable
private fun RailColumn(
    width: androidx.compose.ui.unit.Dp,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextTertiary
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun SubtitleLanguageCard(
    item: SubtitleLanguageRailItem,
    emphasized: Boolean,
    showAiSourceDot: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onMoveRight: (() -> Unit)?,
    onBack: () -> Unit,
    onFocused: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveToOptionsKey = if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    var isFocused by remember { mutableStateOf(false) }
    // S1–S3: focus uses FocusBackground (white text); browsed Secondary ~18% uses OnSecondary.
    val textColor = when {
        isFocused -> Color.White
        emphasized -> NuvioTheme.colors.OnSecondary
        else -> Color.White
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveToOptionsKey -> {
                        val moveRight = onMoveRight ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                moveRight()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onBack()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "language_focused key=${item.key} label=${item.label}"
                    )
                    onFocused()
                }
            },
        colors = overlayRailCardColors(
            if (emphasized) OverlayRailVisualRole.LanguageBrowsed else OverlayRailVisualRole.Neutral
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // F1: yellow dot on the AI source language (no ✓ on Col1).
                if (showAiSourceDot) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(AiSourceIndicatorYellow, CircleShape)
                    )
                }
                if (item.count > 0) {
                    CountBadge(count = item.count, selected = emphasized && !isFocused)
                }
            }
        }
    }
}

@Composable
private fun SubtitleOptionCard(
    item: SubtitleOptionRailItem,
    focusRequester: FocusRequester?,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onBack: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    // S4–S7: Secondary fill only when selected; focus alone uses FocusBackground.
    val onPurpleFill = item.isSelected
    val titleColor = when {
        onPurpleFill -> NuvioTheme.colors.OnSecondary
        else -> Color.White
    }
    val metaColor = when {
        onPurpleFill -> NuvioTheme.colors.OnSecondary.copy(alpha = 0.72f)
        else -> NuvioTheme.colors.TextTertiary
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    val moveRightKey = if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onMoveLeft()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    moveRightKey -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onMoveRight()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> {
                                onBack()
                                true
                            }
                            KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "option_focused id=${item.id} title=${item.title} selected=${item.isSelected}"
                    )
                    onFocused()
                }
            },
        colors = overlayRailCardColors(
            if (item.isSelected) OverlayRailVisualRole.OptionSelected else OverlayRailVisualRole.Neutral
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceChip(label = item.sourceLabel, selected = onPurpleFill)
                    // F2: yellow "Fonte IA" chip beside the origin chip.
                    if (item.isAiSource) {
                        SourceChip(
                            label = stringResource(R.string.sub_ai_source_chip),
                            selected = false,
                            accentYellow = true
                        )
                    }
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor
                )
                if (!item.meta.isNullOrBlank()) {
                    Text(
                        text = item.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = metaColor
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.matchScore > 0) {
                    MatchScoreBadge(
                        scorePercent = item.matchScore,
                        selected = onPurpleFill
                    )
                }
                // V2: ✓ only on Col2 selected (playback) option.
                if (item.isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = NuvioTheme.colors.OnSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchScoreBadge(
    scorePercent: Int,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    NuvioTheme.colors.Secondary.copy(alpha = 0.85f)
                },
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp)
    ) {
        Text(
            text = "$scorePercent%",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.OnSecondary
        )
    }
}

@Composable
private fun CountBadge(
    count: Int,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    NuvioTheme.colors.Secondary.copy(alpha = 0.85f)
                },
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.OnSecondary
        )
    }
}

@Composable
private fun SourceChip(
    label: String,
    selected: Boolean = false,
    accentYellow: Boolean = false
) {
    val background = when {
        accentYellow -> AiSourceIndicatorYellow.copy(alpha = 0.22f)
        selected -> NuvioTheme.colors.OnSecondary.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val textColor = when {
        accentYellow -> AiSourceIndicatorYellow
        selected -> NuvioTheme.colors.OnSecondary.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.78f)
    }
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .then(
                if (selected && !accentYellow) {
                    Modifier.border(
                        width = NuvioTheme.spacing.hairline,
                        color = NuvioTheme.colors.OnSecondary.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else if (accentYellow) {
                    Modifier.border(
                        width = NuvioTheme.spacing.hairline,
                        color = AiSourceIndicatorYellow.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

@Composable
private fun OverlayLoadingCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.xl))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun OverlayEmptyCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

private enum class OverlayRailVisualRole {
    /** Col1 language whose Col2 is open, without DPAD focus (S2). */
    LanguageBrowsed,
    /** Col2 option active in playback (S5/S6). */
    OptionSelected,
    /** Default list row / CTA idle (S3/S7/S9). */
    Neutral,
    /** Col3 action button (S8/S9). */
    Cta
}

@Composable
private fun overlayRailCardColors(role: OverlayRailVisualRole) = CardDefaults.colors(
    // Unfocused fill: Secondary only for selection/browsed — never for bare focus.
    containerColor = when (role) {
        OverlayRailVisualRole.LanguageBrowsed ->
            NuvioTheme.colors.Secondary.copy(alpha = LanguageBrowsedWithoutFocusAlpha)
        OverlayRailVisualRole.OptionSelected ->
            NuvioTheme.colors.Secondary.copy(alpha = SelectedWithoutFocusAlpha)
        OverlayRailVisualRole.Neutral,
        OverlayRailVisualRole.Cta -> Color.Transparent
    },
    // Focused fill: Secondary only when already selected (S5); else FocusBackground (S1/S4/S8).
    focusedContainerColor = when (role) {
        OverlayRailVisualRole.OptionSelected -> NuvioTheme.colors.Secondary
        OverlayRailVisualRole.LanguageBrowsed,
        OverlayRailVisualRole.Neutral,
        OverlayRailVisualRole.Cta -> NuvioTheme.colors.FocusBackground
    },
    pressedContainerColor = when (role) {
        OverlayRailVisualRole.OptionSelected -> NuvioTheme.colors.Secondary
        else -> NuvioTheme.colors.FocusBackground
    }
)

@Composable
private fun overlayCardBorder() = CardDefaults.border(
    border = Border(
        border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ),
    focusedBorder = Border(
        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    )
)

private enum class OverlayFocusRail {
    LANGUAGE,
    OPTION,
    INFO
}

@Composable
private fun rememberFocusRequesterMap(keys: List<String>): Map<String, FocusRequester> {
    return remember(keys) { keys.associateWith { FocusRequester() } }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollItemIntoView(
    targetIndex: Int,
    contextItemsBefore: Int = 1
) {
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem((targetIndex - contextItemsBefore).coerceAtLeast(0))
}

private fun preferredVisibleStartIndex(targetIndex: Int): Int {
    if (targetIndex < 0) return 0
    return (targetIndex - 1).coerceAtLeast(0)
}

internal data class SubtitleLanguageRailItem(
    val key: String,
    val label: String,
    val count: Int
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON,
    AI
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val isSelected: Boolean,
    val matchScore: Int = 0,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null,
    val languageCode: String? = null,
    val formatLabel: String? = null,
    val isBitmap: Boolean = false,
    val isForced: Boolean = false,
    val isSdh: Boolean = false,
    val fileName: String? = null,
    /** F2: this option is the current AI translation source. */
    val isAiSource: Boolean = false
)

private fun SubtitleOptionRailItem.toInfoSnapshot(unknownLabel: String): SubtitleInfoOptionSnapshot {
    val languageLabel = languageCode
        ?.takeIf { it.isNotBlank() }
        ?.let { subtitleLanguageLabel(normalizeOverlayLanguageKey(it), unknownLabel) }
    return SubtitleInfoOptionSnapshot(
        kind = when (kind) {
            SubtitleOptionKind.INTERNAL -> SubtitleInfoOptionKind.INTERNAL
            SubtitleOptionKind.ADDON -> SubtitleInfoOptionKind.ADDON
            SubtitleOptionKind.AI -> SubtitleInfoOptionKind.AI
        },
        id = id,
        title = title,
        sourceLabel = sourceLabel,
        languageLabel = languageLabel,
        languageCode = languageCode,
        meta = meta,
        matchScorePercent = matchScore,
        formatLabel = formatLabel,
        isBitmap = isBitmap,
        isForced = isForced,
        isSdh = isSdh,
        addonName = addonSubtitle?.addonName ?: sourceLabel.takeIf { kind == SubtitleOptionKind.ADDON },
        fileName = fileName ?: meta,
        trackName = title.takeIf { kind == SubtitleOptionKind.INTERNAL }
    )
}

internal fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    currentLanguageKey: String,
    noneLabel: String,
    unknownLabel: String,
    includeSyntheticAiOnPreferred: Boolean = false
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    // K1: preferred Col1 count includes the synthetic AI option when listed.
    // Also ensures the preferred row exists when there are zero tracks/addons.
    if (includeSyntheticAiOnPreferred) {
        preferredOrder.firstOrNull()?.let { preferredKey ->
            counts[preferredKey] = languageRailCountIncludingAi(
                trackAndAddonCount = counts[preferredKey] ?: 0,
                aiOptionListed = true
            )
        }
    }

    val languageEntries = if (showOnlyPreferredLanguages) {
        val preferredKeys = preferredOrder.toSet()
        counts.entries.filter { entry ->
            entry.key in preferredKeys || entry.key == currentLanguageKey
        }
    } else {
        counts.entries
    }

    val sortedItems = languageEntries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, count) ->
            SubtitleLanguageRailItem(
                key = key,
                label = subtitleLanguageLabel(key, unknownLabel),
                count = count
            )
        }

    return listOf(
        SubtitleLanguageRailItem(
            key = SubtitleOffLanguageKey,
            label = noneLabel,
            count = 0
        )
    ) + sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    preferredLanguageKey: String,
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    installedAddonOrder: List<String>,
    selectedOptionId: String?,
    scoreByOptionId: Map<String, Int>,
    builtInLabel: String,
    forcedLabel: String,
    unknownLabel: String,
    aiSubtitleAvailable: Boolean,
    aiSubtitleTranslationActive: Boolean,
    isAiSubtitleTranslating: Boolean,
    aiOptionBadge: String,
    aiOptionMeta: String,
    aiOptionTranslatingMeta: String,
    aiOptionApiKeyMeta: String,
    aiSourceOptionId: String? = null
): List<SubtitleOptionRailItem> {
    if (selectedLanguageKey == SubtitleOffLanguageKey) return emptyList()

    val isPreferredLanguage = selectedLanguageKey == preferredLanguageKey
    // Prefer the explicit rail selection. Only highlight AI when translation is active and the
    // user has not pointed at another option in this overlay session.
    val aiOptionSelected = selectedOptionId == SubtitleAiOptionId ||
        (aiSubtitleTranslationActive && isPreferredLanguage && selectedOptionId == null)

    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    fun toAddonItem(subtitle: Subtitle): SubtitleOptionRailItem {
        val optionId = addonSubtitleOptionId(subtitle)
        val fileName = subtitle.id.takeIf { it.isNotBlank() && it != subtitle.lang && it != subtitle.url }
        return SubtitleOptionRailItem(
            id = optionId,
            kind = SubtitleOptionKind.ADDON,
            title = if (subtitle.isStreamProvided) {
                streamProvidedSubtitleTitle(subtitle)
            } else {
                Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang))
            },
            sourceLabel = if (subtitle.isStreamProvided) builtInLabel else subtitle.addonName,
            meta = fileName,
            isSelected = optionId == selectedOptionId,
            matchScore = scoreByOptionId[optionId] ?: 0,
            addonSubtitle = subtitle,
            languageCode = subtitle.lang,
            formatLabel = subtitleFormatLabelFromUrl(subtitle.url),
            fileName = fileName,
            isAiSource = optionId == aiSourceOptionId
        )
    }

    val matchingAddonSubtitles = addonSubtitles
        .filter { normalizeOverlayLanguageKey(it.lang) == selectedLanguageKey }
        .distinctBy { addonSubtitleOptionId(it) }

    val streamProvidedItems = matchingAddonSubtitles
        .filter { it.isStreamProvided }
        .map(::toAddonItem)

    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            val (formatLabel, isBitmap) = subtitleFormatLabelFromCodec(track.codec)
            val optionId = "internal:${track.index}"
            SubtitleOptionRailItem(
                id = optionId,
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = builtInLabel,
                meta = listOfNotNull(
                    track.codec,
                    if (track.isForced) forcedLabel else null
                ).joinToString(" • ").ifBlank { null },
                isSelected = optionId == selectedOptionId,
                internalTrackIndex = track.index,
                languageCode = track.language,
                formatLabel = formatLabel,
                isBitmap = isBitmap,
                isForced = track.isForced,
                isSdh = trackNameLooksSdh(track.name),
                isAiSource = optionId == aiSourceOptionId
            )
        }

    val addonFetchedItems = matchingAddonSubtitles
        .filter { !it.isStreamProvided }
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<Subtitle>> { (_, subtitle) ->
                scoreByOptionId[addonSubtitleOptionId(subtitle)] ?: 0
            }.thenBy { (_, subtitle) ->
                addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE
            }.thenBy { (index, _) -> index }
        )
        .map { (_, subtitle) -> toAddonItem(subtitle) }

    val aiOptionItem = if ((aiSubtitleAvailable || aiSubtitleTranslationActive) && isPreferredLanguage) {
        listOf(
            SubtitleOptionRailItem(
                id = SubtitleAiOptionId,
                kind = SubtitleOptionKind.AI,
                title = subtitleLanguageLabel(selectedLanguageKey, unknownLabel),
                sourceLabel = aiOptionBadge,
                meta = when {
                    isAiSubtitleTranslating -> aiOptionTranslatingMeta
                    !aiSubtitleAvailable -> aiOptionApiKeyMeta
                    else -> aiOptionMeta
                },
                isSelected = aiOptionSelected,
                matchScore = 0
            )
        )
    } else {
        emptyList()
    }

    return aiOptionItem + internalItems + streamProvidedItems + addonFetchedItems
}

private fun selectedSubtitleLanguageKey(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun selectedSubtitleOptionId(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String? {
    selectedAddonSubtitle?.let { subtitle ->
        return addonSubtitleOptionId(subtitle)
    }

    internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { track ->
            return "internal:${track.index}"
        }

    internalTracks
        .firstOrNull { it.isSelected }
        ?.let { track ->
            return "internal:${track.index}"
        }

    return null
}

internal fun addonSubtitleOptionId(subtitle: Subtitle): String {
    return "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}"
}

internal fun resolveAddonSubtitleByOptionId(
    optionId: String,
    addonSubtitles: List<Subtitle>
): Subtitle? {
    if (!optionId.startsWith("addon:")) return null
    return addonSubtitles.firstOrNull { addonSubtitleOptionId(it) == optionId }
}

private fun streamProvidedSubtitleTitle(subtitle: Subtitle): String {
    val name = subtitle.addonName.trim()
    if (name.isNotBlank() && !name.equals("Plugin", ignoreCase = true)) {
        return name
    }
    return subtitle.lang
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String, unknownLabel: String): String {
    return when (key) {
        SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none")
        SubtitleUnknownLanguageKey -> unknownLabel
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none").lowercase()
    else -> Subtitle.languageCodeToName(key).lowercase()
}
