package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Collection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionManagementUiState(
    val collections: List<Collection> = emptyList(),
    val isLoading: Boolean = true,
    val showImportDialog: Boolean = false,
    val importText: String = "",
    val importError: String? = null,
    val exportedJson: String? = null
)

@HiltViewModel
class CollectionManagementViewModel @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionManagementUiState())
    val uiState: StateFlow<CollectionManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collectionsDataStore.collections.collectLatest { collections ->
                _uiState.update {
                    it.copy(collections = collections, isLoading = false)
                }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionsDataStore.removeCollection(collectionId)
        }
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val item = current.removeAt(index)
            current.add(index - 1, item)
            collectionsDataStore.setCollections(current)
        }
    }

    fun moveDown(index: Int) {
        val current = _uiState.value.collections
        if (index >= current.size - 1) return
        viewModelScope.launch {
            val mutableList = current.toMutableList()
            val item = mutableList.removeAt(index)
            mutableList.add(index + 1, item)
            collectionsDataStore.setCollections(mutableList)
        }
    }

    fun exportCollections(): String {
        val json = collectionsDataStore.exportToJson(_uiState.value.collections)
        _uiState.update { it.copy(exportedJson = json) }
        return json
    }

    fun clearExported() {
        _uiState.update { it.copy(exportedJson = null) }
    }

    fun showImportDialog() {
        _uiState.update { it.copy(showImportDialog = true, importText = "", importError = null) }
    }

    fun hideImportDialog() {
        _uiState.update { it.copy(showImportDialog = false, importText = "", importError = null) }
    }

    fun updateImportText(text: String) {
        _uiState.update { it.copy(importText = text, importError = null) }
    }

    fun importCollections() {
        val json = _uiState.value.importText.trim()
        if (json.isBlank()) {
            _uiState.update { it.copy(importError = "Paste a collections JSON to import") }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = "Invalid format or empty collections") }
            return
        }
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val existingIds = current.map { it.id }.toSet()
            for (collection in imported) {
                if (collection.id in existingIds) {
                    val index = current.indexOfFirst { it.id == collection.id }
                    if (index >= 0) current[index] = collection
                } else {
                    current.add(collection)
                }
            }
            collectionsDataStore.setCollections(current)
            _uiState.update { it.copy(showImportDialog = false, importText = "", importError = null) }
        }
    }
}
