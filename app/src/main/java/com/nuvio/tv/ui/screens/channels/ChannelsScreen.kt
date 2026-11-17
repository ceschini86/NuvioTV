package com.nuvio.tv.ui.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

// fork: the Channels section (docs/plan-channels.md).

@Composable
fun ChannelsScreen(
    viewModel: ChannelsViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = (uiState.posterCardWidthDp * 1.5f).roundToInt().dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val openDetail: (String, String, String) -> Unit = { id, type, addonBaseUrl ->
        val item = uiState.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.id == id } }
        HeroBackdropState.update(item?.backdropUrl)
        onNavigateToDetail(id, type, addonBaseUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when {
            uiState.rows.isEmpty() && uiState.isLoading -> {
                LoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
            uiState.rows.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.channels_empty_title),
                    subtitle = stringResource(R.string.channels_empty_subtitle),
                    icon = Icons.Default.LiveTv,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.xxl)
                ) {
                    item(key = "channels_header") {
                        Text(
                            text = stringResource(R.string.nav_channels),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(
                                start = NuvioTheme.spacing.xxxl,
                                bottom = NuvioTheme.spacing.lg
                            )
                        )
                    }
                    items(uiState.rows, key = { it.stableKey() }) { row ->
                        CatalogRowSection(
                            catalogRow = row,
                            posterCardStyle = posterCardStyle,
                            showPosterLabels = uiState.posterLabelsEnabled,
                            showAddonName = uiState.catalogAddonNameEnabled,
                            showCatalogTypeSuffix = false,
                            onItemClick = openDetail,
                            onItemLongPress = { item, addonBaseUrl -> viewModel.posterOptions.show(item, addonBaseUrl) },
                            onSeeAll = { onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType) }
                        )
                    }
                }
            }
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = openDetail
        )
    }
}
