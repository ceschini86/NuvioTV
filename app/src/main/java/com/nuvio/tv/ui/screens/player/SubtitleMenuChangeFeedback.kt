package com.nuvio.tv.ui.screens.player

/**
 * Pure decisions for transient subtitle-menu change banners (PRD §B7 F1/F2/F2b).
 * No Compose / string resources — callers resolve copy.
 */

internal enum class SubtitleMenuChangeBannerKind {
    /** F1 — Translate with AI (menu open, focus jump). */
    TRANSLATE_FROM,
    /** F2 — Reset Smart landed on an automatic rung. */
    AUTOMATIC_SELECTION,
    /** F2b — Reset cannot restore Smart AI; classic-only CTA path. */
    CLASSIC_SELECTION
}

internal enum class SubtitleAutomaticSelectionOutcome {
    AI_FROM_EMBEDDED,
    PREFERRED_EMBEDDED,
    CLASSIC
}

internal data class SubtitleMenuChangeBannerSpec(
    val kind: SubtitleMenuChangeBannerKind,
    val sourceShortLabel: String? = null,
    val automaticOutcome: SubtitleAutomaticSelectionOutcome? = null
)

/**
 * Short source label for F1 (e.g. "English · AIOStreams" or embedded language/name).
 */
internal fun buildTranslateSourceShortLabel(
    languageLabel: String?,
    addonName: String?,
    isAddon: Boolean,
    embeddedTitle: String?
): String {
    val lang = languageLabel?.trim()?.takeIf { it.isNotEmpty() }
    if (isAddon) {
        val addon = addonName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            lang != null && addon != null -> "$lang · $addon"
            lang != null -> lang
            addon != null -> addon
            else -> embeddedTitle?.trim().orEmpty()
        }
    }
    return lang ?: embeddedTitle?.trim().orEmpty()
}

internal fun decideTranslateMenuChangeBanner(sourceShortLabel: String): SubtitleMenuChangeBannerSpec =
    SubtitleMenuChangeBannerSpec(
        kind = SubtitleMenuChangeBannerKind.TRANSLATE_FROM,
        sourceShortLabel = sourceShortLabel.trim().ifEmpty { "—" }
    )

/**
 * @param resetToClassicOnly true when CTA is «Voltar à seleção clássica» (F2b).
 * @param postResetRung rung after policy republishes; required for F2 when not classic-only.
 */
internal fun decideResetMenuChangeBanner(
    resetToClassicOnly: Boolean,
    postResetRung: AiSubtitleLadderRung?
): SubtitleMenuChangeBannerSpec? {
    if (resetToClassicOnly) {
        return SubtitleMenuChangeBannerSpec(kind = SubtitleMenuChangeBannerKind.CLASSIC_SELECTION)
    }
    val rung = postResetRung ?: return null
    return SubtitleMenuChangeBannerSpec(
        kind = SubtitleMenuChangeBannerKind.AUTOMATIC_SELECTION,
        automaticOutcome = automaticSelectionOutcomeFromRung(rung)
    )
}

internal fun automaticSelectionOutcomeFromRung(
    rung: AiSubtitleLadderRung
): SubtitleAutomaticSelectionOutcome =
    when (rung) {
        AiSubtitleLadderRung.AI_EMBEDDED -> SubtitleAutomaticSelectionOutcome.AI_FROM_EMBEDDED
        AiSubtitleLadderRung.PREFERRED_EMBEDDED -> SubtitleAutomaticSelectionOutcome.PREFERRED_EMBEDDED
        else -> SubtitleAutomaticSelectionOutcome.CLASSIC
    }

/** Debounce: skip re-show when resolved text is identical. */
internal fun shouldShowMenuChangeBanner(
    currentResolvedText: String?,
    nextResolvedText: String
): Boolean {
    val next = nextResolvedText.trim()
    if (next.isEmpty()) return false
    return currentResolvedText?.trim() != next
}
