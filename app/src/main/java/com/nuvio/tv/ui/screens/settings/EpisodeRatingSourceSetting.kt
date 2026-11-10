package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeRatingSource

/**
 * Fork: which score the Ratings tab and the episode cards show, Series Graph community averages
 * or IMDb. Sits in Layout → Details next to the episode ratings visibility (ADR 0001).
 */
@Composable
internal fun EpisodeRatingSourceSettingRow(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsActionRow(
        title = stringResource(R.string.layout_episode_rating_source),
        subtitle = stringResource(R.string.layout_episode_rating_source_sub),
        value = episodeRatingSourceLabel(uiState.episodeRatingSource),
        onClick = { showDialog = true }
    )

    if (showDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.layout_episode_rating_source),
            subtitle = stringResource(R.string.layout_episode_rating_source_sub),
            options = EpisodeRatingSource.entries.map {
                SettingsPickerOption(it, episodeRatingSourceLabel(it))
            },
            selectedValue = uiState.episodeRatingSource,
            onOptionSelected = { source ->
                onEvent(LayoutSettingsEvent.SetEpisodeRatingSource(source))
                showDialog = false
            },
            onDismiss = { showDialog = false },
            width = 420.dp,
            maxHeight = 280.dp
        )
    }
}

@Composable
private fun episodeRatingSourceLabel(source: EpisodeRatingSource): String =
    when (source) {
        EpisodeRatingSource.SERIES_GRAPH -> stringResource(R.string.episode_rating_source_series_graph)
        EpisodeRatingSource.IMDB -> stringResource(R.string.episode_rating_source_imdb)
    }
