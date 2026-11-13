package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.ui.screens.player.resolution.ResolutionOption
import com.nuvio.tv.ui.screens.player.resolution.StreamResolutionPicker
import com.nuvio.tv.ui.screens.player.resolution.displayLabel
import kotlinx.coroutines.flow.update

// Fork: the player's resolution list. It reuses the sources panel's stream list and switch.

internal fun PlayerRuntimeController.showResolutionPicker() {
    _uiState.update {
        it.copy(
            showResolutionPicker = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showControls = true
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.dismissResolutionPicker() {
    _uiState.update { it.copy(showResolutionPicker = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectResolution(resolution: DebridStreamResolution) {
    val state = _uiState.value
    val stream = StreamResolutionPicker.pick(
        streams = state.sourceAllStreams,
        resolution = resolution,
        currentAddonName = state.currentStreamAddonName,
        currentIndex = currentResolutionStreamIndex(state)
    )
    _uiState.update { it.copy(showResolutionPicker = false) }
    if (stream != null) switchToSourceStream(stream)
}

internal fun PlayerRuntimeController.resolutionOptions(state: PlayerUiState): List<ResolutionOption> {
    val index = currentResolutionStreamIndex(state)
    val current = state.sourceAllStreams.getOrNull(index)?.let(StreamResolutionPicker::detect)
        ?: StreamResolutionPicker.detect(state.currentStreamName, currentFilename, state.currentStreamDescription)
    return StreamResolutionPicker.options(state.sourceAllStreams, current)
}

/** Short label of the playing stream's resolution for the control bar, or null when unknown. */
internal fun PlayerRuntimeController.currentResolutionLabel(state: PlayerUiState): String? =
    StreamResolutionPicker.detect(state.currentStreamName, currentFilename, state.currentStreamDescription)
        .takeIf { it != DebridStreamResolution.UNKNOWN }
        ?.displayLabel()

private fun PlayerRuntimeController.currentResolutionStreamIndex(state: PlayerUiState): Int =
    findCurrentStreamIndex(
        streams = state.sourceAllStreams,
        currentStreamInfoHash = state.currentStreamInfoHash,
        currentStreamFileIdx = state.currentStreamFileIdx,
        currentStreamAddonName = state.currentStreamAddonName,
        currentStreamUrl = state.currentStreamUrl,
        currentStreamName = state.currentStreamName,
        currentStreamDescription = state.currentStreamDescription,
        currentFilename = currentFilename
    )
