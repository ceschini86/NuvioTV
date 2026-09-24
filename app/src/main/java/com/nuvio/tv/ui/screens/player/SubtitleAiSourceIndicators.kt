package com.nuvio.tv.ui.screens.player

/**
 * Pure decisions for AI source indicators (F1–F4) and Reset Smart focus/outcome (S2–S4).
 * Fatia C/D of overlay-info-rail — no Compose, no side effects.
 */

internal data class AiSourceIndicatorDecision(
    val visible: Boolean,
    /** Col1 language key that should show the yellow source dot (F1). */
    val languageKey: String? = null,
    /** Col2 option id that should show the "Fonte IA" chip (F2). */
    val optionId: String? = null
) {
    companion object {
        val Hidden = AiSourceIndicatorDecision(visible = false)
    }
}

/**
 * F1–F4: yellow indicators follow the active AI translation source, or hide when AI is off.
 * F1 without a resolvable F2 optionId stays Hidden (never show the Col1 dot alone).
 */
internal fun decideAiSourceIndicators(
    translationActive: Boolean,
    sourceLanguageKey: String?,
    sourceOptionId: String?
): AiSourceIndicatorDecision {
    if (!translationActive) return AiSourceIndicatorDecision.Hidden
    val languageKey = sourceLanguageKey?.takeIf { it.isNotBlank() } ?: return AiSourceIndicatorDecision.Hidden
    val optionId = sourceOptionId?.takeIf { it.isNotBlank() } ?: return AiSourceIndicatorDecision.Hidden
    return AiSourceIndicatorDecision(
        visible = true,
        languageKey = languageKey,
        optionId = optionId
    )
}

/**
 * Resolve the Col2 option id for the current AI pivot from playback + diagnostics.
 */
internal fun resolveAiSourceOptionId(
    sourceKind: AiSubtitleSourceKind?,
    selectedInternalIndex: Int,
    selectedAddonOptionId: String?,
    tracks: List<TrackInfo> = emptyList(),
    diagnosticsInternalIndex: Int? = null,
    sourceLanguage: String? = null,
    sourceLabel: String? = null
): String? = when (sourceKind) {
    AiSubtitleSourceKind.ADDON -> selectedAddonOptionId
    AiSubtitleSourceKind.EMBEDDED -> resolveEmbeddedAiSourceOptionId(
        diagnosticsIndex = diagnosticsInternalIndex,
        selectedInternalIndex = selectedInternalIndex,
        tracks = tracks,
        sourceLanguage = sourceLanguage,
        sourceLabel = sourceLabel
    )
    null -> selectedAddonOptionId
        ?: resolveEmbeddedAiSourceOptionId(
            diagnosticsIndex = diagnosticsInternalIndex,
            selectedInternalIndex = selectedInternalIndex,
            tracks = tracks,
            sourceLanguage = sourceLanguage,
            sourceLabel = sourceLabel
        )
}

/**
 * F2 embedded: map diagnostics/selection to `internal:N` present in [tracks].
 *
 * Order: diagnostics index → selected index → unique language (+ label) match → null.
 */
internal fun resolveEmbeddedAiSourceOptionId(
    diagnosticsIndex: Int?,
    selectedInternalIndex: Int,
    tracks: List<TrackInfo>,
    sourceLanguage: String?,
    sourceLabel: String?
): String? {
    fun idIfPresent(index: Int?): String? {
        if (index == null || index < 0) return null
        return tracks.firstOrNull { it.index == index }?.let { "internal:${it.index}" }
    }

    idIfPresent(diagnosticsIndex)?.let { return it }
    idIfPresent(selectedInternalIndex.takeIf { it >= 0 })?.let { return it }

    val langKey = normalizeIndicatorLanguageKey(sourceLanguage) ?: return null
    val langMatches = tracks.filter { normalizeIndicatorLanguageKey(it.language) == langKey }
    if (langMatches.isEmpty()) return null
    if (langMatches.size == 1) return "internal:${langMatches.first().index}"

    val label = sourceLabel?.trim()?.takeIf { it.isNotEmpty() }
    if (label != null) {
        val labeled = langMatches.filter { it.name.equals(label, ignoreCase = true) }
        if (labeled.size == 1) return "internal:${labeled.first().index}"
    }
    return null
}

internal fun normalizeIndicatorLanguageKey(language: String?): String? {
    if (language.isNullOrBlank()) return null
    val normalized = language.trim().lowercase()
        .replace('_', '-')
    return when {
        normalized.startsWith("pt-br") || normalized == "pt-br" -> "pt-br"
        normalized.startsWith("es-419") || normalized == "es-419" -> "es-419"
        else -> normalized.substringBefore('-').ifBlank { null }
    }
}

internal fun internalSubtitleOptionId(index: Int): String = "internal:$index"

internal enum class ResetSmartSelectionKind {
    AI,
    PREFERRED_EMBEDDED,
    CLASSIC
}

/**
 * Post-ladder outcome after Reset Smart (S2–S4): what stays selected and whether AI remains on.
 */
internal data class ResetSmartOutcomeDecision(
    val kind: ResetSmartSelectionKind,
    val translationActive: Boolean,
    /** Info CTA after reset: always none for automatic outcomes. */
    val showResetCta: Boolean = false
)

internal fun decideResetSmartOutcome(rung: AiSubtitleLadderRung): ResetSmartOutcomeDecision =
    when (rung) {
        AiSubtitleLadderRung.AI_EMBEDDED -> ResetSmartOutcomeDecision(
            kind = ResetSmartSelectionKind.AI,
            translationActive = true
        )
        AiSubtitleLadderRung.PREFERRED_EMBEDDED -> ResetSmartOutcomeDecision(
            kind = ResetSmartSelectionKind.PREFERRED_EMBEDDED,
            translationActive = false
        )
        AiSubtitleLadderRung.CLASSIC_FALLBACK,
        AiSubtitleLadderRung.PREFERRED_SCORED_ADDON,
        AiSubtitleLadderRung.AI_SCORED_ADDON,
        AiSubtitleLadderRung.NONE,
        AiSubtitleLadderRung.MANUAL -> ResetSmartOutcomeDecision(
            kind = ResetSmartSelectionKind.CLASSIC,
            translationActive = false
        )
    }

/**
 * S6: after Reset Smart, focus the option that became selected.
 */
internal data class ResetSmartFocusDecision(
    val languageKey: String,
    val optionId: String
)

internal fun decideResetSmartFocus(
    rung: AiSubtitleLadderRung,
    preferredLanguageKey: String,
    playbackLanguageKey: String,
    playbackOptionId: String?,
    aiOptionId: String = SubtitleAiOptionId
): ResetSmartFocusDecision {
    val outcome = decideResetSmartOutcome(rung)
    return when (outcome.kind) {
        ResetSmartSelectionKind.AI -> ResetSmartFocusDecision(
            languageKey = preferredLanguageKey,
            optionId = aiOptionId
        )
        ResetSmartSelectionKind.PREFERRED_EMBEDDED -> ResetSmartFocusDecision(
            languageKey = preferredLanguageKey,
            optionId = playbackOptionId ?: aiOptionId
        )
        ResetSmartSelectionKind.CLASSIC -> ResetSmartFocusDecision(
            languageKey = playbackLanguageKey.ifBlank { preferredLanguageKey },
            optionId = playbackOptionId ?: aiOptionId
        )
    }
}

/**
 * A1–A3: what clicking the synthetic AI option should do.
 */
internal enum class AiOptionClickAction {
    /** A1 — already selected; no-op. */
    NO_OP,
    /** A2 — translation already running; only re-select AI in the UI. */
    SELECT_ONLY,
    /** A3 — turn AI on (MANUAL); runtime picks/keeps source. */
    ENABLE_MANUAL
}

internal fun decideAiOptionClickAction(
    aiOptionAlreadySelected: Boolean,
    translationActive: Boolean,
    userLocked: Boolean
): AiOptionClickAction = when {
    aiOptionAlreadySelected && translationActive -> AiOptionClickAction.NO_OP
    translationActive -> AiOptionClickAction.SELECT_ONLY
    userLocked -> AiOptionClickAction.SELECT_ONLY
    else -> AiOptionClickAction.ENABLE_MANUAL
}

/**
 * How to pick the AI pivot when enabling via Col2 AI option click (A3).
 * Never treat an incidental classic Col2 pick as the new AI source — that is CTA Translate only.
 */
internal enum class AiOptionEnableSourceAction {
    /** Current playback already is the remembered AI source. */
    KEEP_CURRENT,
    /** Reselect embedded track from prior diagnostics. */
    RESTORE_EMBEDDED,
    /** Reselect addon that matches prior diagnostics label/lang. */
    RESTORE_ADDON,
    /** No usable prior source — Smart embedded pick. */
    PICK_EMBEDDED_SMART
}

internal data class AiOptionEnableSourceDecision(
    val action: AiOptionEnableSourceAction,
    val embeddedIndex: Int? = null
)

/**
 * Decide restore vs Smart pick for AI-option enable.
 *
 * @param priorSourceKind diagnostics source when AI was last active (may linger after classic pick)
 * @param priorEmbeddedIndex diagnostics.sourceInternalIndex
 * @param priorSourceLabel diagnostics.sourceLabel (addon name or track name)
 * @param priorSourceLanguage diagnostics.sourceLanguage
 * @param currentEmbeddedIndex currently selected embedded track (-1 if none)
 * @param currentAddonLabel currently selected addon name (null if none)
 * @param currentAddonLanguage currently selected addon language
 * @param tracks available embedded tracks (to validate restore index)
 */
internal fun decideAiOptionEnableSource(
    priorSourceKind: AiSubtitleSourceKind?,
    priorEmbeddedIndex: Int?,
    priorSourceLabel: String?,
    priorSourceLanguage: String?,
    currentEmbeddedIndex: Int,
    currentAddonLabel: String?,
    currentAddonLanguage: String?,
    tracks: List<TrackInfo>
): AiOptionEnableSourceDecision {
    fun trackPresent(index: Int?): Boolean =
        index != null && index >= 0 && tracks.any { it.index == index }

    val priorWasAiSource = priorSourceKind != null

    when (priorSourceKind) {
        AiSubtitleSourceKind.EMBEDDED -> {
            if (trackPresent(priorEmbeddedIndex)) {
                val sameAsCurrent =
                    currentAddonLabel == null && currentEmbeddedIndex == priorEmbeddedIndex
                return if (sameAsCurrent) {
                    AiOptionEnableSourceDecision(AiOptionEnableSourceAction.KEEP_CURRENT)
                } else {
                    AiOptionEnableSourceDecision(
                        action = AiOptionEnableSourceAction.RESTORE_EMBEDDED,
                        embeddedIndex = priorEmbeddedIndex
                    )
                }
            }
        }
        AiSubtitleSourceKind.ADDON -> {
            val label = priorSourceLabel?.trim()?.takeIf { it.isNotEmpty() }
            val lang = normalizeIndicatorLanguageKey(priorSourceLanguage)
            val currentMatches =
                currentAddonLabel != null &&
                    (
                        (label != null && currentAddonLabel.equals(label, ignoreCase = true)) ||
                            (
                                lang != null &&
                                    normalizeIndicatorLanguageKey(currentAddonLanguage) == lang &&
                                    label == null
                                )
                        )
            if (currentMatches) {
                return AiOptionEnableSourceDecision(AiOptionEnableSourceAction.KEEP_CURRENT)
            }
            if (label != null || lang != null) {
                // Caller must resolve addon by label/lang; signal restore intent.
                return AiOptionEnableSourceDecision(AiOptionEnableSourceAction.RESTORE_ADDON)
            }
        }
        null -> Unit
    }

    // Incidental classic selection or missing prior → Smart embedded, never keep stray addon.
    if (!priorWasAiSource &&
        currentAddonLabel == null &&
        currentEmbeddedIndex >= 0 &&
        trackPresent(currentEmbeddedIndex)
    ) {
        // Cold A3 with only embedded selected: keep it as MANUAL pivot.
        return AiOptionEnableSourceDecision(AiOptionEnableSourceAction.KEEP_CURRENT)
    }
    return AiOptionEnableSourceDecision(AiOptionEnableSourceAction.PICK_EMBEDDED_SMART)
}
