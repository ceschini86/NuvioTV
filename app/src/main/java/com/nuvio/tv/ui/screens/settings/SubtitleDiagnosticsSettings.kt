package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.SubtitleDiagnosticsCapture
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fork: Playback → AI subtitles → diagnostics toggle and clear action (docs/adr/0002). */
@HiltViewModel
class SubtitleDiagnosticsViewModel @Inject constructor(
    private val preferences: DeviceLocalPlayerPreferences,
    private val capture: SubtitleDiagnosticsCapture,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = preferences.subtitleDiagnosticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sizeBytes: StateFlow<Long> = flow {
        while (true) {
            emit(capture.sizeBytes())
            delay(SIZE_REFRESH_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val directoryPath: String get() = capture.directory.absolutePath

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSubtitleDiagnosticsEnabled(enabled) }
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) { capture.clear() }
    }

    private companion object {
        const val SIZE_REFRESH_MS = 5_000L
    }
}

/**
 * Fork: diagnostics toggle and clear action, built from the shared settings rows.
 * Placed by the AI subtitles section in Playback settings.
 */
@Composable
internal fun SubtitleDiagnosticsItems(
    enabled: Boolean,
    onItemFocused: () -> Unit = {},
    viewModel: SubtitleDiagnosticsViewModel = hiltViewModel(),
) {
    val isOn by viewModel.enabled.collectAsStateWithLifecycle()
    val sizeBytes by viewModel.sizeBytes.collectAsStateWithLifecycle()
    val sizeMb = "%.1f".format(sizeBytes / 1_048_576.0)
    SettingsToggleRow(
        title = stringResource(R.string.sub_diag_title),
        subtitle = stringResource(R.string.sub_diag_desc, sizeMb, viewModel.directoryPath),
        checked = isOn,
        onToggle = { viewModel.setEnabled(!isOn) },
        onFocused = onItemFocused,
        enabled = enabled,
        expandSubtitleOnFocus = true,
    )
    SettingsActionRow(
        title = stringResource(R.string.sub_diag_clear),
        subtitle = null,
        onClick = viewModel::clear,
        onFocused = onItemFocused,
        enabled = enabled,
        leadingIcon = Icons.Default.BugReport,
    )
}
