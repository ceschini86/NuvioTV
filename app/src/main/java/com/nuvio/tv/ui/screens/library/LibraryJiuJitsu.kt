package com.nuvio.tv.ui.screens.library

// Fork: the "Jiu Jitsu" tab of the Library. It pins the catalog of the video-translation addon
// (one series per collection, the volumes as episodes) so the collections open from the Library
// without going through the home rows.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The manifest id and the catalog id that `video-translation/worker/index.js` serves. */
internal const val JIU_JITSU_ADDON_ID = "app.video-translation.subtitles"
internal const val JIU_JITSU_CATALOG_ID = "vt-collections"

internal sealed interface JiuJitsuState {
    data object Loading : JiuJitsuState
    data object AddonMissing : JiuJitsuState
    data class Failed(val message: String?) : JiuJitsuState
    data class Loaded(val row: CatalogRow) : JiuJitsuState
}

/** The installed addon that serves the collections: by manifest id, else by the catalog id. */
internal fun List<Addon>.jiuJitsuAddon(): Addon? =
    firstOrNull { it.id == JIU_JITSU_ADDON_ID }
        ?: firstOrNull { addon -> addon.catalogs.any { it.id == JIU_JITSU_CATALOG_ID } }

@HiltViewModel
class LibraryJiuJitsuViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {
    private val _state = MutableStateFlow<JiuJitsuState>(JiuJitsuState.Loading)
    internal val state: StateFlow<JiuJitsuState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** Loads the catalog once; [force] loads it again (a new volume after `vt addon publish`). */
    fun load(force: Boolean = false) {
        if (!force && (_state.value is JiuJitsuState.Loaded || loadJob?.isActive == true)) return
        loadJob?.cancel()
        _state.value = JiuJitsuState.Loading
        loadJob = viewModelScope.launch {
            val addon = addonRepository.getInstalledAddons().first().enabledAddons().jiuJitsuAddon()
            if (addon == null) {
                _state.value = JiuJitsuState.AddonMissing
                return@launch
            }
            val catalog = addon.catalogs.firstOrNull { it.id == JIU_JITSU_CATALOG_ID }
            val result = catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = JIU_JITSU_CATALOG_ID,
                catalogName = catalog?.name ?: addon.displayName,
                type = catalog?.rawType ?: "series"
            ).first { it !is NetworkResult.Loading }
            _state.value = when (result) {
                is NetworkResult.Success -> JiuJitsuState.Loaded(result.data)
                is NetworkResult.Error -> JiuJitsuState.Failed(result.message)
                NetworkResult.Loading -> JiuJitsuState.Loading
            }
        }
    }
}

internal fun LazyGridScope.libraryJiuJitsuContent(
    state: JiuJitsuState,
    posterCardStyle: PosterCardStyle,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (id: String, type: String, addonBaseUrl: String) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    when (state) {
        JiuJitsuState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        JiuJitsuState.AddonMissing -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_jiu_jitsu_missing_title),
                subtitle = stringResource(R.string.library_jiu_jitsu_missing_message),
                icon = Icons.Default.SportsMartialArts,
                height = 260.dp
            )
        }

        is JiuJitsuState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_jiu_jitsu_failed_title),
                subtitle = state.message ?: stringResource(R.string.library_jiu_jitsu_failed_message),
                icon = Icons.Default.SportsMartialArts,
                height = 260.dp
            )
        }

        is JiuJitsuState.Loaded -> {
            val row = state.row
            if (row.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.library_jiu_jitsu_empty_title),
                        subtitle = stringResource(R.string.library_jiu_jitsu_empty_message),
                        icon = Icons.Default.SportsMartialArts,
                        height = 260.dp
                    )
                }
            }
            items(row.items, key = { "jiu_jitsu:${it.id}" }) { meta ->
                val preview = meta.copy(posterShape = PosterShape.POSTER)
                GridContentCard(
                    item = preview,
                    posterCardStyle = posterCardStyle,
                    showLabel = true,
                    isWatched = isWatched(meta),
                    onClick = { onItemClick(meta.id, meta.rawType, row.addonBaseUrl) },
                    onLongPress = { onItemLongPress(preview, row.addonBaseUrl) }
                )
            }
        }
    }
}
