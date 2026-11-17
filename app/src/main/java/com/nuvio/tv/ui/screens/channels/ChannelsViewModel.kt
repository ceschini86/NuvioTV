package com.nuvio.tv.ui.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.channelCatalogs
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import com.nuvio.tv.ui.screens.CatalogRowSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

// fork: the Channels section (docs/plan-channels.md).

data class ChannelsUiState(
    /** Rows with at least one item, in addon and manifest order. */
    val rows: List<CatalogRow> = emptyList(),
    /** True until every channel catalog answered once. */
    val isLoading: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    val posterOptions: PosterOptionsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    /** Channel catalogs by row key, in display order. */
    private var catalogs: Map<String, Pair<Addon, CatalogDescriptor>> = emptyMap()

    /** Loaded rows by row key. Rows that failed are absent. */
    private val rowsByKey = MutableStateFlow<Map<String, CatalogRow>>(emptyMap())

    /** Keys whose first page failed or came back empty. */
    private val failedKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the current channel catalogs, or null before the addon list first arrives. */
    private val catalogKeys = MutableStateFlow<Set<String>?>(null)

    /** Bumped on every reload, so a page requested before it is dropped. */
    private var generation = 0L

    private val rowSources = mutableMapOf<String, CatalogRowSource>()

    init {
        posterOptions.bind(viewModelScope)
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled
            ) { width, radius, labels, addonName ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = width,
                        posterCardCornerRadiusDp = radius,
                        posterLabelsEnabled = labels,
                        catalogAddonNameEnabled = addonName
                    )
                }
            }.collect {}
        }
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .map { addons -> addons.channelCatalogs() }
                .distinctUntilChanged { old, new -> old.rowKeys() == new.rowKeys() }
                .collectLatest { entries -> loadAll(entries) }
        }
    }

    /** A source for [com.nuvio.tv.ui.screens.CatalogSeeAllScreen] that shares this screen's rows. */
    fun rowSource(addonId: String, type: String, catalogId: String): CatalogRowSource {
        val key = catalogRowLegacyKey(addonId, type, catalogId)
        return rowSources.getOrPut(key) {
            object : CatalogRowSource {
                override val row: StateFlow<CatalogRow?> = rowsByKey
                    .map { it[key] }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), rowsByKey.value[key])

                // A key missing from the loaded catalogs (addon removed, restored route) fails
                // instead of leaving the See All grid on its spinner.
                override val failed: StateFlow<Boolean> = combine(failedKeys, catalogKeys) { failed, known ->
                    key in failed || (known != null && key !in known)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

                override fun loadMore() = loadMore(key)
            }
        }
    }

    private suspend fun loadAll(entries: List<Pair<Addon, CatalogDescriptor>>) {
        generation++
        catalogs = entries.associateBy { (addon, catalog) -> catalog.rowKey(addon) }
        rowsByKey.value = emptyMap()
        failedKeys.value = emptySet()
        catalogKeys.value = catalogs.keys
        _uiState.update { it.copy(rows = emptyList(), isLoading = entries.isNotEmpty()) }
        coroutineScope {
            catalogs.forEach { (key, entry) -> launch { loadFirstPage(key, entry.first, entry.second) } }
        }
        _uiState.update { it.copy(isLoading = false) }
    }

    private suspend fun loadFirstPage(key: String, addon: Addon, catalog: CatalogDescriptor) {
        fetch(addon, catalog, skip = 0, skipStep = catalog.skipStep(), supportsSkip = catalog.supportsExtra("skip"))
            .collect { result ->
                when (result) {
                    is NetworkResult.Success -> putRow(key, result.data)
                    is NetworkResult.Error -> failedKeys.update { it + key }
                    NetworkResult.Loading -> Unit
                }
            }
    }

    private fun loadMore(key: String) {
        val current = rowsByKey.value[key] ?: return
        val (addon, catalog) = catalogs[key] ?: return
        if (current.isLoading || !current.hasMore) return
        putRow(key, current.copy(isLoading = true))
        val requestGeneration = generation
        viewModelScope.launch {
            fetch(addon, catalog, current.nextCatalogSkip(), current.skipStep, current.supportsSkip).collect { result ->
                // A reload since this request replaced the row; this page belongs to the old one.
                if (requestGeneration != generation) return@collect
                val latest = rowsByKey.value[key] ?: return@collect
                when (result) {
                    is NetworkResult.Success -> putRow(key, latest.mergeCatalogPage(result.data).copy(isLoading = false))
                    is NetworkResult.Error -> putRow(key, latest.copy(isLoading = false, hasMore = false))
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun putRow(key: String, row: CatalogRow) {
        val rows = rowsByKey.updateAndGet { it + (key to row) }
        if (row.items.isEmpty() && !row.isLoading) failedKeys.update { it + key }
        _uiState.update { state ->
            state.copy(rows = catalogs.keys.mapNotNull { rows[it] }.filter { it.items.isNotEmpty() })
        }
    }

    private fun fetch(
        addon: Addon,
        catalog: CatalogDescriptor,
        skip: Int,
        skipStep: Int,
        supportsSkip: Boolean
    ) = catalogRepository.getCatalog(
        addonBaseUrl = addon.baseUrl,
        addonId = addon.id,
        addonName = addon.displayName,
        catalogId = catalog.id,
        catalogName = catalog.name,
        type = catalog.apiType,
        skip = skip,
        skipStep = skipStep,
        supportsSkip = supportsSkip,
        posterScreen = CustomPosterScreen.HOME
    )

    private fun CatalogDescriptor.rowKey(addon: Addon): String = catalogRowLegacyKey(addon.id, apiType, id)

    private fun List<Pair<Addon, CatalogDescriptor>>.rowKeys(): List<String> =
        map { (addon, catalog) -> "${addon.baseUrl}|${catalog.rowKey(addon)}" }
}
