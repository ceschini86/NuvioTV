package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.MimeTypes
import com.nuvio.tv.domain.model.Subtitle

/*
 * Fork (#16): AI translation on top of 1.1.0's AutoSync and libass.
 *
 * Order: AutoSync retimes the Fonte first, and the translation is applied later, when a cue is
 * rendered, looked up by the cue's text. Retiming only rewrites start and end times, so a retimed
 * Fonte finds the same translation cache entries and nothing is translated twice. With Tradução
 * pausada the same retimed cues are shown in the Fonte's language.
 *
 * libass: its parsers hand Media3 no cues at all (the file goes straight into the native track), so
 * a Fonte drawn by libass never reaches TranslatingTextOutput or the sidecar renderer and would stay
 * untranslated with no sign of it. While AI translates, an ASS/SSA Fonte is drawn by the text
 * renderer instead: an addon file goes to the sidecar (Media3's SsaParser), and an embedded track
 * makes the player rebuild without libass for the rest of the stream. Styling is lost, the
 * translation shows.
 */

/** Whether an ASS/SSA Fonte goes to libass ([libassWanted]) or to the text renderer. */
internal fun keepAssOnLibass(libassWanted: Boolean, aiTranslating: Boolean): Boolean =
    libassWanted && !aiTranslating

/**
 * Whether the player must drop libass so AI can read an embedded ASS/SSA track. Once dropped for a
 * stream ([alreadyDropped]) it stays dropped, so turning AI off or a track change mid-rebuild
 * cannot flip the pipeline back and forth.
 */
internal fun shouldDropLibassForAi(
    aiTranslating: Boolean,
    playerUsesLibass: Boolean,
    selectedTextIsAss: Boolean,
    alreadyDropped: Boolean,
): Boolean = alreadyDropped || (aiTranslating && playerUsesLibass && selectedTextIsAss)

internal fun PlayerRuntimeController.isAiTranslationOn(): Boolean =
    subtitleTranslationManager?.isEnabled == true && !isUsingMpvEngine()

/** Hook in [maybeAdjustLibassPipelineForTracks]: true while this stream must run without libass. */
internal fun PlayerRuntimeController.aiNeedsTextPipeline(selectedTextIsAss: Boolean): Boolean {
    if (isUsingMpvEngine()) return false
    val stream = currentStreamUrl
    val alreadyDropped = aiTextPipelineStreamUrl == stream
    val drop = shouldDropLibassForAi(
        aiTranslating = isAiTranslationOn(),
        playerUsesLibass = activePlayerUsesLibass,
        selectedTextIsAss = selectedTextIsAss,
        alreadyDropped = alreadyDropped,
    )
    if (drop && !alreadyDropped) {
        aiTextPipelineStreamUrl = stream
        Log.i(
            PlayerRuntimeController.TAG,
            "AI_ROUTE fonte=embedded_ass renderer=text reason=libass_has_no_text_cues " +
                "action=rebuild_without_libass"
        )
    }
    return drop
}

/**
 * Called when AI turns on with the subtitle selection already in place. An ASS/SSA addon Fonte
 * that libass is drawing moves to the sidecar and AutoSync runs on it there; an embedded ASS/SSA
 * track gets the libass check again. Returns true when an addon Fonte was moved.
 */
internal fun PlayerRuntimeController.routeAiSourceToTextRenderer(): Boolean {
    val player = _exoPlayer ?: return false
    if (!isAiTranslationOn()) return false
    val addon = _uiState.value.selectedAddonSubtitle
    if (addon == null) {
        maybeAdjustLibassPipelineForTracks(player.currentTracks)
        return false
    }
    if (PlayerSubtitleUtils.mimeTypeFromUrl(addon.url) != MimeTypes.TEXT_SSA) return false
    if (activeSidecarSubtitleKey == addon.url) return false
    if (!canAttachAddonSubtitleViaSidecar(addon)) return false
    Log.i(
        PlayerRuntimeController.TAG,
        "AI_ROUTE fonte=addon_ass renderer=text reason=libass_has_no_text_cues " +
            "addon=${addon.addonName} lang=${addon.lang}"
    )
    disableSubtitles()
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    startSidecarAddonSubtitle(addon)
    runSelectedAutomaticSubtitleSync(addon)
    return true
}

/** Diagnóstico de legendas: AutoSync retimed a Fonte that AI is translating. */
internal fun PlayerRuntimeController.logAutoSyncAdjustedTranslatedSource(subtitle: Subtitle) {
    if (!isAiTranslationOn()) return
    Log.i(
        PlayerRuntimeController.TAG,
        "AI_AUTOSYNC adjusted fonte=addon addon=${subtitle.addonName} lang=${subtitle.lang} " +
            "cues=${sidecarTimedCues.size} paused=${_uiState.value.aiSubtitleQuotaExhausted}"
    )
}
