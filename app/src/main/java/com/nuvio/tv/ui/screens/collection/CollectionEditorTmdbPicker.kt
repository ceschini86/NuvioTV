package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TmdbSourcePickerContent(
    uiState: CollectionEditorUiState,
    presets: List<TmdbPresetSource>,
    onModeChange: (TmdbBuilderMode) -> Unit,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onFiltersChange: (TmdbCollectionFilters) -> Unit,
    onSearchCompanies: () -> Unit,
    onSearchCollections: () -> Unit,
    onAddSource: (TmdbCollectionSource) -> Unit,
    onAddFromInput: () -> Unit,
    onAddDiscover: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_tmdb_sources),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TmdbBuilderMode.values().toList()) { mode ->
                val selected = uiState.tmdbBuilderMode == mode
                Button(
                    onClick = { onModeChange(mode) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                        contentColor = if (selected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Text(tmdbModeLabel(mode))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.tmdbSearchError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(error, color = NuvioColors.Error, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TmdbModeHelp(mode = uiState.tmdbBuilderMode)
            }
            when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.PRESETS -> {
                    items(presets) { preset ->
                        TmdbPickerCard(
                            title = preset.title,
                            subtitle = tmdbSourceSubtitle(preset.source),
                            onClick = { onAddSource(preset.source) }
                        )
                    }
                }
                TmdbBuilderMode.LIST,
                TmdbBuilderMode.NETWORK -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            lockTv = uiState.tmdbBuilderMode == TmdbBuilderMode.NETWORK
                        )
                    }
                }
                TmdbBuilderMode.PRODUCTION -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            onSearch = onSearchCompanies
                        )
                    }
                    items(uiState.tmdbCompanyResults) { result ->
                        val title = result.name ?: "TMDB Company ${result.id}"
                        TmdbPickerCard(
                            title = title,
                            subtitle = listOfNotNull("Production", result.originCountry).joinToString(" • "),
                            onClick = {
                                onAddSource(
                                    TmdbCollectionSource(
                                        sourceType = TmdbCollectionSourceType.COMPANY,
                                        title = title,
                                        tmdbId = result.id,
                                        mediaType = uiState.tmdbMediaType,
                                        sortBy = uiState.tmdbSortBy,
                                        filters = uiState.tmdbFilters
                                    )
                                )
                            }
                        )
                    }
                }
                TmdbBuilderMode.COLLECTION -> {
                    item {
                        TmdbBasicSourceForm(
                            uiState = uiState,
                            onInputChange = onInputChange,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onAdd = onAddFromInput,
                            onSearch = onSearchCollections,
                            lockMovie = true
                        )
                    }
                    items(uiState.tmdbCollectionResults) { result ->
                        val title = result.name ?: "TMDB Collection ${result.id}"
                        TmdbPickerCard(
                            title = title,
                            subtitle = stringResource(R.string.collections_editor_tmdb_collection),
                            onClick = {
                                onAddSource(
                                    TmdbCollectionSource(
                                        sourceType = TmdbCollectionSourceType.COLLECTION,
                                        title = title,
                                        tmdbId = result.id,
                                        mediaType = TmdbCollectionMediaType.MOVIE,
                                        sortBy = uiState.tmdbSortBy
                                    )
                                )
                            }
                        )
                    }
                }
                TmdbBuilderMode.DISCOVER -> {
                    item {
                        TmdbDiscoverForm(
                            uiState = uiState,
                            onTitleChange = onTitleChange,
                            onMediaTypeChange = onMediaTypeChange,
                            onSortChange = onSortChange,
                            onFiltersChange = onFiltersChange,
                            onAdd = onAddDiscover
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbBasicSourceForm(
    uiState: CollectionEditorUiState,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSearch: (() -> Unit)? = null,
    lockTv: Boolean = false,
    lockMovie: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TmdbLabeledField(
            label = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_public_list)
                TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_network_id)
                TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_collection_id)
                TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_company_search)
                else -> stringResource(R.string.collections_editor_tmdb_id_or_url)
            },
            value = uiState.tmdbInput,
            onValueChange = onInputChange,
            placeholder = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.LIST -> "https://www.themoviedb.org/list/8504994 or 8504994"
                TmdbBuilderMode.NETWORK -> "213 for Netflix, 49 for HBO, 2739 for Disney+"
                TmdbBuilderMode.COLLECTION -> "10 for Star Wars Collection"
                TmdbBuilderMode.PRODUCTION -> "Marvel Studios, Pixar, Warner Bros."
                else -> stringResource(R.string.collections_editor_tmdb_id_or_url)
            },
            helper = when (uiState.tmdbBuilderMode) {
                TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_search_helper)
                TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_collection_helper)
                TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_network_helper)
                TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_list_helper)
                else -> ""
            }
        )
        TmdbLabeledField(
            label = stringResource(R.string.collections_editor_tmdb_display_title),
            value = uiState.tmdbTitleInput,
            onValueChange = onTitleChange,
            placeholder = "Marvel Movies, Netflix Originals, Pixar",
            helper = stringResource(R.string.collections_editor_tmdb_title_helper)
        )
        TmdbMediaSortControls(
            mediaType = uiState.tmdbMediaType,
            sortBy = uiState.tmdbSortBy,
            onMediaTypeChange = onMediaTypeChange,
            onSortChange = onSortChange,
            lockTv = lockTv,
            lockMovie = lockMovie
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onSearch?.let {
                NuvioButton(onClick = it) { Text(stringResource(R.string.collections_editor_tmdb_search)) }
            }
            NuvioButton(onClick = onAdd) { Text(stringResource(R.string.collections_editor_add_source)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbDiscoverForm(
    uiState: CollectionEditorUiState,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    onFiltersChange: (TmdbCollectionFilters) -> Unit,
    onAdd: () -> Unit
) {
    val filters = uiState.tmdbFilters
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TmdbLabeledField(
            label = stringResource(R.string.collections_editor_tmdb_display_title),
            value = uiState.tmdbTitleInput,
            onValueChange = onTitleChange,
            placeholder = "Best Action Movies, Korean Dramas, 2024 Animation",
            helper = stringResource(R.string.collections_editor_tmdb_title_helper)
        )
        TmdbMediaSortControls(
            mediaType = uiState.tmdbMediaType,
            sortBy = uiState.tmdbSortBy,
            onMediaTypeChange = onMediaTypeChange,
            onSortChange = onSortChange
        )
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_genres),
            chips = tmdbGenreQuickChips(uiState.tmdbMediaType),
            onSelect = { onFiltersChange(filters.copy(withGenres = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_genres),
            helper = stringResource(R.string.collections_editor_tmdb_genres_helper),
            placeholder = if (uiState.tmdbMediaType == TmdbCollectionMediaType.MOVIE) "28,12" else "18,35",
            value = filters.withGenres
        ) {
            onFiltersChange(filters.copy(withGenres = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_date_from),
            helper = stringResource(R.string.collections_editor_tmdb_date_helper),
            placeholder = "2020-01-01",
            value = filters.releaseDateGte
        ) {
            onFiltersChange(filters.copy(releaseDateGte = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_date_to),
            helper = stringResource(R.string.collections_editor_tmdb_date_helper),
            placeholder = "2024-12-31",
            value = filters.releaseDateLte
        ) {
            onFiltersChange(filters.copy(releaseDateLte = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_rating_min),
            helper = stringResource(R.string.collections_editor_tmdb_rating_helper),
            placeholder = "7.0",
            value = filters.voteAverageGte?.toString()
        ) {
            onFiltersChange(filters.copy(voteAverageGte = it.toDoubleOrNull()))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_rating_max),
            helper = stringResource(R.string.collections_editor_tmdb_rating_helper),
            placeholder = "10",
            value = filters.voteAverageLte?.toString()
        ) {
            onFiltersChange(filters.copy(voteAverageLte = it.toDoubleOrNull()))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_votes_min),
            helper = stringResource(R.string.collections_editor_tmdb_votes_helper),
            placeholder = "100",
            value = filters.voteCountGte?.toString()
        ) {
            onFiltersChange(filters.copy(voteCountGte = it.toIntOrNull()))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_languages),
            chips = listOf("English" to "en", "Korean" to "ko", "Japanese" to "ja", "Hindi" to "hi", "Spanish" to "es"),
            onSelect = { onFiltersChange(filters.copy(withOriginalLanguage = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_language),
            helper = stringResource(R.string.collections_editor_tmdb_language_helper),
            placeholder = "en, ko, ja, hi",
            value = filters.withOriginalLanguage
        ) {
            onFiltersChange(filters.copy(withOriginalLanguage = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_countries),
            chips = listOf("United States" to "US", "Korea" to "KR", "Japan" to "JP", "India" to "IN", "United Kingdom" to "GB"),
            onSelect = { onFiltersChange(filters.copy(withOriginCountry = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_country),
            helper = stringResource(R.string.collections_editor_tmdb_country_helper),
            placeholder = "US, KR, JP, IN",
            value = filters.withOriginCountry
        ) {
            onFiltersChange(filters.copy(withOriginCountry = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_keywords),
            chips = listOf("Superhero" to "9715", "Based on Novel" to "818", "Time Travel" to "4379", "Space" to "9882"),
            onSelect = { onFiltersChange(filters.copy(withKeywords = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_keywords),
            helper = stringResource(R.string.collections_editor_tmdb_keywords_helper),
            placeholder = "9715 for superhero",
            value = filters.withKeywords
        ) {
            onFiltersChange(filters.copy(withKeywords = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_companies),
            chips = listOf("Marvel" to "420", "Disney" to "2", "Pixar" to "3", "Lucasfilm" to "1", "Warner Bros." to "174"),
            onSelect = { onFiltersChange(filters.copy(withCompanies = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_companies),
            helper = stringResource(R.string.collections_editor_tmdb_companies_helper),
            placeholder = "420 for Marvel Studios",
            value = filters.withCompanies
        ) {
            onFiltersChange(filters.copy(withCompanies = it.ifBlank { null }))
        }
        TmdbQuickChips(
            label = stringResource(R.string.collections_editor_tmdb_quick_networks),
            chips = listOf("Netflix" to "213", "HBO" to "49", "Disney+" to "2739", "Prime Video" to "1024", "Hulu" to "453"),
            onSelect = { onFiltersChange(filters.copy(withNetworks = it)) }
        )
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_networks),
            helper = stringResource(R.string.collections_editor_tmdb_networks_helper),
            placeholder = "213 for Netflix",
            value = filters.withNetworks
        ) {
            onFiltersChange(filters.copy(withNetworks = it.ifBlank { null }))
        }
        TmdbFilterField(
            label = stringResource(R.string.collections_editor_tmdb_year),
            helper = stringResource(R.string.collections_editor_tmdb_year_helper),
            placeholder = "2024",
            value = filters.year?.toString()
        ) {
            onFiltersChange(filters.copy(year = it.toIntOrNull()))
        }
        NuvioButton(onClick = onAdd) { Text(stringResource(R.string.collections_editor_add_source)) }
    }
}

@Composable
private fun TmdbFilterField(
    label: String,
    helper: String,
    placeholder: String,
    value: String?,
    onValueChange: (String) -> Unit
) {
    TmdbLabeledField(
        label = label,
        value = value.orEmpty(),
        onValueChange = onValueChange,
        placeholder = placeholder,
        helper = helper
    )
}

@Composable
private fun TmdbLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    helper: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextPrimary)
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder
        )
        if (helper.isNotBlank()) {
            Text(helper, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextTertiary)
        }
    }
}

@Composable
private fun TmdbModeHelp(mode: TmdbBuilderMode) {
    val text = when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(R.string.collections_editor_tmdb_help_presets)
        TmdbBuilderMode.LIST -> stringResource(R.string.collections_editor_tmdb_help_list)
        TmdbBuilderMode.PRODUCTION -> stringResource(R.string.collections_editor_tmdb_help_production)
        TmdbBuilderMode.NETWORK -> stringResource(R.string.collections_editor_tmdb_help_network)
        TmdbBuilderMode.COLLECTION -> stringResource(R.string.collections_editor_tmdb_help_collection)
        TmdbBuilderMode.DISCOVER -> stringResource(R.string.collections_editor_tmdb_help_discover)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextSecondary)
}

@Composable
private fun TmdbQuickChips(
    label: String,
    chips: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips) { (chipLabel, value) ->
                TmdbChoiceButton(
                    label = chipLabel,
                    selected = false,
                    onClick = { onSelect(value) }
                )
            }
        }
    }
}

private fun tmdbGenreQuickChips(mediaType: TmdbCollectionMediaType): List<Pair<String, String>> {
    return when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> listOf(
            "Action" to "28",
            "Adventure" to "12",
            "Animation" to "16",
            "Comedy" to "35",
            "Horror" to "27",
            "Sci-Fi" to "878"
        )
        TmdbCollectionMediaType.TV -> listOf(
            "Drama" to "18",
            "Comedy" to "35",
            "Animation" to "16",
            "Crime" to "80",
            "Sci-Fi" to "10765",
            "Reality" to "10764"
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbMediaSortControls(
    mediaType: TmdbCollectionMediaType,
    sortBy: String,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onSortChange: (String) -> Unit,
    lockTv: Boolean = false,
    lockMovie: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TmdbChoiceButton(
                label = stringResource(R.string.type_movie),
                selected = mediaType == TmdbCollectionMediaType.MOVIE,
                enabled = !lockTv,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.MOVIE) }
            )
            TmdbChoiceButton(
                label = stringResource(R.string.type_series),
                selected = mediaType == TmdbCollectionMediaType.TV,
                enabled = !lockMovie,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.TV) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sorts = listOf(
                TmdbCollectionSort.POPULAR_DESC.value to stringResource(R.string.tmdb_entity_rail_popular),
                TmdbCollectionSort.VOTE_AVERAGE_DESC.value to stringResource(R.string.tmdb_entity_rail_top_rated),
                if (mediaType == TmdbCollectionMediaType.TV) {
                    TmdbCollectionSort.FIRST_AIR_DATE_DESC.value to stringResource(R.string.tmdb_entity_rail_recent)
                } else {
                    TmdbCollectionSort.RELEASE_DATE_DESC.value to stringResource(R.string.tmdb_entity_rail_recent)
                }
            )
            sorts.forEach { (value, label) ->
                TmdbChoiceButton(
                    label = label,
                    selected = sortBy == value,
                    onClick = { onSortChange(value) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
            contentColor = if (selected) NuvioColors.Secondary else NuvioColors.TextSecondary,
            focusedContainerColor = NuvioColors.FocusBackground,
            focusedContentColor = NuvioColors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Text(label)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TmdbPickerCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = NuvioColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NuvioColors.TextTertiary)
        }
    }
}

private fun tmdbModeLabel(mode: TmdbBuilderMode): String {
    return when (mode) {
        TmdbBuilderMode.PRESETS -> "Presets"
        TmdbBuilderMode.LIST -> "Public List"
        TmdbBuilderMode.PRODUCTION -> "Production"
        TmdbBuilderMode.NETWORK -> "Network"
        TmdbBuilderMode.COLLECTION -> "Collection"
        TmdbBuilderMode.DISCOVER -> "Custom"
    }
}
