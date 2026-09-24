package com.nuvio.tv.ui.screens.player

/**
 * Pure decisions for AI source indicators (F1–F4) and Reset Smart focus/outcome (S2–S4).
 * Fatia C of overlay-info-rail — no Compose, no side effects.
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
    selectedAddonOptionId: String?
): String? = when (sourceKind) {
    AiSubtitleSourceKind.ADDON -> selectedAddonOptionId
    AiSubtitleSourceKind.EMBEDDED ->
        selectedInternalIndex.takeIf { it >= 0 }?.let { "internal:$it" }
    null -> selectedAddonOptionId
        ?: selectedInternalIndex.takeIf { it >= 0 }?.let { "internal:$it" }
}

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
