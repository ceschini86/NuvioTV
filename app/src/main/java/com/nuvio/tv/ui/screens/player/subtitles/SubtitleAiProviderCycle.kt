package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Pure helpers for the overlay CTA that cycles the preferred AI provider among
 * credentials that match the router eligibility rule: enabled + at least one usable key.
 *
 * Cycle order follows [SubtitleAiModel.entries] (Groq → Gemini → Claude).
 */
fun eligibleSubtitleAiModels(credentials: SubtitleAiCredentials): List<SubtitleAiModel> =
    SubtitleAiModel.entries.filter { credentials.provider(it).hasUsableKey }

fun shouldShowSubtitleAiProviderCycleCta(credentials: SubtitleAiCredentials): Boolean =
    eligibleSubtitleAiModels(credentials).size >= 2

/**
 * Overlay CTA only on the synthetic **AI** option of the preferred language — never on
 * embedded / addon rows (any language).
 */
fun shouldShowSubtitleAiProviderCycleCta(
    credentials: SubtitleAiCredentials,
    isAiOption: Boolean
): Boolean = isAiOption && shouldShowSubtitleAiProviderCycleCta(credentials)

/**
 * Next preferred model among eligible providers. Returns null when cycling is not available
 * (fewer than two eligible providers).
 *
 * If [current] is not eligible, starts from the first eligible entry in enum order.
 */
fun nextEligibleSubtitleAiModel(
    current: SubtitleAiModel,
    credentials: SubtitleAiCredentials
): SubtitleAiModel? {
    val eligible = eligibleSubtitleAiModels(credentials)
    if (eligible.size < 2) return null
    val index = eligible.indexOf(current)
    return if (index < 0) {
        eligible.first()
    } else {
        eligible[(index + 1) % eligible.size]
    }
}
