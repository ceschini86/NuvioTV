package com.nuvio.tv.ui.components.posteroptions

import android.util.Log
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable controller for the long-press / hold-down poster options menu.
 *
 * Inject into a ViewModel via Hilt and call [bind] from the VM's init with
 * `viewModelScope`. The screen renders [PosterOptionsHost] with [state] and
 * wires [show] to each card's `onLongPress`.
 */
class PosterOptionsController @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository
) {
    private val _state = MutableStateFlow(PosterOptionsState())
    val state: StateFlow<PosterOptionsState> = _state.asStateFlow()

    private val targetFlow = MutableStateFlow<MetaPreview?>(null)

    private var scope: CoroutineScope? = null
    private var bound = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bind(scope: CoroutineScope) {
        if (bound) return
        bound = true
        this.scope = scope

        libraryRepository.sourceMode
            .distinctUntilChanged()
            .onEach { mode ->
                _state.update { current ->
                    val resetPicker = mode != LibrarySourceMode.TRAKT
                    if (resetPicker) {
                        current.copy(
                            librarySourceMode = mode,
                            listPickerActive = false,
                            listPickerPending = false,
                            listPickerError = null,
                            listPickerTitle = null,
                            listPickerMembership = emptyMap()
                        )
                    } else {
                        current.copy(librarySourceMode = mode)
                    }
                }
            }
            .launchIn(scope)

        libraryRepository.listTabs
            .distinctUntilChanged()
            .onEach { tabs ->
                _state.update { current ->
                    current.copy(
                        libraryListTabs = tabs,
                        listPickerMembership = mergeMembershipWithTabs(
                            tabs = tabs,
                            membership = current.listPickerMembership
                        )
                    )
                }
            }
            .launchIn(scope)

        targetFlow
            .flatMapLatest { item ->
                if (item == null) {
                    flowOf(false to false)
                } else {
                    combine(
                        libraryRepository.isInLibrary(item.id, item.apiType),
                        watchProgressRepository.isWatched(item.id, videoId = item.imdbId)
                    ) { lib, watched -> lib to watched }
                }
            }
            .onEach { (isInLibrary, isWatched) ->
                _state.update { current ->
                    current.copy(
                        isInLibrary = isInLibrary,
                        isWatched = isWatched
                    )
                }
            }
            .launchIn(scope)
    }

    fun show(item: MetaPreview, addonBaseUrl: String?) {
        _state.update { current ->
            current.copy(
                target = item,
                addonBaseUrl = addonBaseUrl.orEmpty(),
                isInLibrary = false,
                isWatched = false,
                isLibraryPending = false,
                isWatchedPending = false
            )
        }
        targetFlow.value = item
    }

    fun dismiss() {
        targetFlow.value = null
        _state.update { it.copy(target = null) }
    }

    fun toggleLibrary() {
        val state = _state.value
        val item = state.target ?: return
        if (state.isLibraryPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isLibraryPending = true) }
        scope.launch {
            runCatching {
                libraryRepository.toggleDefault(
                    item.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle library for ${item.id}: ${error.message}")
            }
            _state.update { it.copy(isLibraryPending = false) }
        }
    }

    fun openListPicker() {
        val state = _state.value
        val item = state.target ?: return
        if (state.librarySourceMode != LibrarySourceMode.TRAKT) {
            toggleLibrary()
            dismiss()
            return
        }
        val scope = this.scope ?: return
        val input = item.toLibraryEntryInput(state.addonBaseUrl.takeIf { it.isNotBlank() })

        _state.update { current ->
            current.copy(
                target = null,
                listPickerActive = true,
                listPickerTitle = item.name,
                listPickerPending = true,
                listPickerError = null,
                listPickerMembership = mergeMembershipWithTabs(
                    tabs = current.libraryListTabs,
                    membership = emptyMap()
                )
            )
        }
        targetFlow.value = null
        activeListPickerInput = input

        scope.launch {
            runCatching {
                libraryRepository.getMembershipSnapshot(input)
            }.onSuccess { snapshot ->
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = null,
                        listPickerMembership = mergeMembershipWithTabs(
                            tabs = current.libraryListTabs,
                            membership = snapshot.listMembership
                        )
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to load list picker for ${item.id}: ${error.message}")
                _state.update { current ->
                    current.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: "Failed to load lists"
                    )
                }
            }
        }
    }

    fun toggleListMembership(listKey: String) {
        _state.update { current ->
            val nextMembership = current.listPickerMembership.toMutableMap().apply {
                this[listKey] = !(this[listKey] == true)
            }
            current.copy(
                listPickerMembership = nextMembership,
                listPickerError = null
            )
        }
    }

    fun saveListPicker() {
        val state = _state.value
        if (state.listPickerPending) return
        if (state.librarySourceMode != LibrarySourceMode.TRAKT) return
        val input = activeListPickerInput ?: return
        val scope = this.scope ?: return

        _state.update { it.copy(listPickerPending = true, listPickerError = null) }
        scope.launch {
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = input,
                    changes = ListMembershipChanges(
                        desiredMembership = _state.value.listPickerMembership
                    )
                )
            }.onSuccess {
                activeListPickerInput = null
                _state.update {
                    it.copy(
                        listPickerActive = false,
                        listPickerPending = false,
                        listPickerError = null,
                        listPickerTitle = null
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to save list picker: ${error.message}")
                _state.update {
                    it.copy(
                        listPickerPending = false,
                        listPickerError = error.message ?: "Failed to update lists"
                    )
                }
            }
        }
    }

    fun dismissListPicker() {
        activeListPickerInput = null
        _state.update {
            it.copy(
                listPickerActive = false,
                listPickerPending = false,
                listPickerError = null,
                listPickerTitle = null
            )
        }
    }

    fun toggleMovieWatched() {
        val state = _state.value
        val item = state.target ?: return
        if (!item.apiType.equals("movie", ignoreCase = true)) return
        if (state.isWatchedPending) return
        val scope = this.scope ?: return

        _state.update { it.copy(isWatchedPending = true) }
        scope.launch {
            val currentlyWatched = _state.value.isWatched
            runCatching {
                if (currentlyWatched) {
                    watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to toggle watched for ${item.id}: ${error.message}")
            }
            _state.update { it.copy(isWatchedPending = false) }
        }
    }

    private var activeListPickerInput: LibraryEntryInput? = null

    companion object {
        private const val TAG = "PosterOptionsCtrl"
    }
}

private fun mergeMembershipWithTabs(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>
): Map<String, Boolean> {
    return if (tabs.isEmpty()) {
        membership
    } else {
        tabs.associate { tab -> tab.key to (membership[tab.key] == true) }
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
    val year = Regex("(\\d{4})").find(releaseInfo ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val parsedIds = parseContentIds(id)
    return LibraryEntryInput(
        itemId = id,
        itemType = apiType,
        title = name,
        year = year,
        traktId = parsedIds.trakt,
        imdbId = parsedIds.imdb,
        tmdbId = parsedIds.tmdb,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}
