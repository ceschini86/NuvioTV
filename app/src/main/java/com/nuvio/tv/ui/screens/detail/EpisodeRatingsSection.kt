package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeRatingSource
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.SeriesGraphRatingColors
import com.nuvio.tv.ui.components.SeriesGraphRatingSourceLabel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpisodeRatingsSection(
    episodes: List<Video>,
    ratings: Map<Pair<Int, Int>, Double>,
    isLoading: Boolean,
    error: String?,
    ratingSource: EpisodeRatingSource,
    onRatingSourceSelected: (EpisodeRatingSource) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Ratings",
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null,
    ratingsGridFocusRequester: FocusRequester? = null
) {
    val seasonNumbers = remember(episodes) {
        episodes
            .mapNotNull { it.season }
            .filter { it > 0 } // Never show specials season (S0)
            .distinct()
            .sorted()
    }
    val seasonSignature = remember(seasonNumbers) { seasonNumbers.joinToString(",") }
    val seasonFocusRequesters = remember(seasonNumbers) {
        seasonNumbers.associateWith { FocusRequester() }
    }
    val seriesGraphSourceFocusRequester = remember { FocusRequester() }
    val imdbSourceFocusRequester = remember { FocusRequester() }
    val selectedSourceFocusRequester = when (ratingSource) {
        EpisodeRatingSource.SERIES_GRAPH -> seriesGraphSourceFocusRequester
        EpisodeRatingSource.IMDB -> imdbSourceFocusRequester
    }
    val internalRatingsGridFocusRequester = remember { FocusRequester() }
    val effectiveRatingsGridFocusRequester = ratingsGridFocusRequester ?: internalRatingsGridFocusRequester
    val firstEpisodeRatingFocusRequester = remember { FocusRequester() }
    val defaultSeason = remember(seasonNumbers) {
        seasonNumbers.firstOrNull { it > 0 } ?: seasonNumbers.firstOrNull() ?: 0
    }
    var selectedSeason by rememberSaveable(seasonSignature) {
        mutableIntStateOf(defaultSeason)
    }

    LaunchedEffect(seasonNumbers, defaultSeason) {
        if (selectedSeason !in seasonNumbers) {
            selectedSeason = defaultSeason
        }
    }

    val episodesForSeason = remember(episodes, selectedSeason) {
        episodes
            .filter { it.season == selectedSeason && it.episode != null }
            .distinctBy { it.season to it.episode }
            .sortedBy { it.episode }
    }
    val defaultChipColor = NuvioTheme.colors.BackgroundCard
    val defaultChipTextColor = NuvioTheme.colors.TextSecondary
    val seasonRatings = remember(episodesForSeason, ratings) {
        episodesForSeason.mapNotNull { episode ->
            val season = episode.season ?: return@mapNotNull null
            val episodeNumber = episode.episode ?: return@mapNotNull null
            val rating = ratings[season to episodeNumber]
            val ratingText = rating?.let { String.format("%.1f", it) } ?: "—"
            // Same official SG bands for both sources (both are 0–10 scales).
            val chipColor = rating?.let(SeriesGraphRatingColors::cellColor) ?: defaultChipColor
            val chipTextColor = rating?.let(SeriesGraphRatingColors::labelColor) ?: defaultChipTextColor
            EpisodeRatingChipUi(
                id = episode.id,
                seasonNumber = season,
                episodeNumber = episodeNumber,
                ratingText = ratingText,
                chipColor = chipColor,
                chipTextColor = chipTextColor
            )
        }
    }
    val hasTitle = title.isNotBlank()
    val downFocusModifier = if (downFocusRequester != null) {
        Modifier.focusProperties {
            down = downFocusRequester
        }
    } else {
        Modifier
    }
    val selectedSeasonFocusRequester = firstItemFocusRequester
        ?: seasonFocusRequesters[selectedSeason]
        ?: FocusRequester.Default

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 14.dp else 10.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
            )
        }

        EpisodeRatingSourceHeader(
            ratingSource = ratingSource,
            seriesGraphFocusRequester = seriesGraphSourceFocusRequester,
            imdbFocusRequester = imdbSourceFocusRequester,
            upFocusRequester = upFocusRequester,
            downFocusRequester = selectedSeasonFocusRequester,
            onRatingSourceSelected = onRatingSourceSelected,
            modifier = Modifier.padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = if (hasTitle) 8.dp else 0.dp,
                bottom = 4.dp
            )
        )

        when {
            isLoading -> {
                Text(
                    text = stringResource(R.string.ratings_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.md)
                )
            }
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.md)
                )
            }
            seasonNumbers.isEmpty() -> {
                Text(
                    text = stringResource(R.string.ratings_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.md)
                )
            }
            else -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer {
                            seasonFocusRequesters[selectedSeason] ?: FocusRequester.Default
                        },
                    contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonNumbers, key = { it }) { season ->
                        val isSelected = season == selectedSeason
                        val modifierWithRequester = if (firstItemFocusRequester != null && season == selectedSeason) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier.focusRequester(seasonFocusRequesters.getValue(season))
                        }

                        Card(
                            onClick = { selectedSeason = season },
                            modifier = modifierWithRequester
                                .focusProperties {
                                    up = selectedSourceFocusRequester
                                    down = effectiveRatingsGridFocusRequester
                                }
                                .onFocusChanged { state ->
                                    if (state.isFocused && selectedSeason != season) {
                                        selectedSeason = season
                                    }
                                },
                            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) {
                                    NuvioTheme.colors.FocusBackground
                                } else {
                                    NuvioTheme.colors.BackgroundCard
                                },
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(
                                text = stringResource(R.string.ratings_season_label, season),
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.ratings_season_summary, selectedSeason, episodesForSeason.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xxs)
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(effectiveRatingsGridFocusRequester)
                        .focusRestorer(firstEpisodeRatingFocusRequester),
                    contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonRatings, key = { "${it.seasonNumber}:${it.episodeNumber}" }) { episodeRating ->
                        val selectedSeasonUpRequester = firstItemFocusRequester ?: seasonFocusRequesters[selectedSeason]
                        val isFirstEpisode = episodeRating == seasonRatings.firstOrNull()

                        Card(
                            onClick = { },
                            modifier = if (selectedSeasonUpRequester != null) {
                                Modifier.focusProperties {
                                    up = selectedSeasonUpRequester
                                }.then(downFocusModifier).then(
                                    if (isFirstEpisode) Modifier.focusRequester(firstEpisodeRatingFocusRequester) else Modifier
                                )
                            } else {
                                Modifier.then(downFocusModifier).then(
                                    if (isFirstEpisode) Modifier.focusRequester(firstEpisodeRatingFocusRequester) else Modifier
                                )
                            },
                            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                            colors = CardDefaults.colors(
                                containerColor = episodeRating.chipColor,
                                focusedContainerColor = episodeRating.chipColor
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1.03f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 46.dp)
                                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.ratings_episode_label, episodeRating.episodeNumber),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = episodeRating.chipTextColor
                                )
                                Text(
                                    text = episodeRating.ratingText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = episodeRating.chipTextColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRatingSourceHeader(
    ratingSource: EpisodeRatingSource,
    seriesGraphFocusRequester: FocusRequester,
    imdbFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester,
    onRatingSourceSelected: (EpisodeRatingSource) -> Unit,
    modifier: Modifier = Modifier
) {
    val upModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (ratingSource) {
            EpisodeRatingSource.SERIES_GRAPH -> {
                SeriesGraphRatingSourceLabel(
                    textStyle = MaterialTheme.typography.labelSmall,
                    textColor = NuvioTheme.colors.TextSecondary,
                    logoHeightDp = 18
                )
            }
            EpisodeRatingSource.IMDB -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImdbRatingSourceLabel(
                        logoModifier = Modifier
                            .height(18.dp)
                            .widthIn(max = 72.dp),
                        textStyle = MaterialTheme.typography.labelSmall,
                        textColor = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.episode_rating_attribution_imdb),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EpisodeRatingSourceChip(
                label = stringResource(R.string.episode_rating_source_series_graph),
                selected = ratingSource == EpisodeRatingSource.SERIES_GRAPH,
                onClick = { onRatingSourceSelected(EpisodeRatingSource.SERIES_GRAPH) },
                modifier = Modifier
                    .focusRequester(seriesGraphFocusRequester)
                    .then(upModifier)
                    .focusProperties { down = downFocusRequester }
            )
            EpisodeRatingSourceChip(
                label = stringResource(R.string.episode_rating_source_imdb),
                selected = ratingSource == EpisodeRatingSource.IMDB,
                onClick = { onRatingSourceSelected(EpisodeRatingSource.IMDB) },
                modifier = Modifier
                    .focusRequester(imdbFocusRequester)
                    .then(upModifier)
                    .focusProperties { down = downFocusRequester }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRatingSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                NuvioTheme.colors.FocusBackground
            } else {
                NuvioTheme.colors.BackgroundCard
            },
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(14.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

private data class EpisodeRatingChipUi(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val ratingText: String,
    val chipColor: Color,
    val chipTextColor: Color
)
