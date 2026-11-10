package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.SubtitleDiagnosticsCapture
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.ui.theme.NuvioTheme
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

/** Fork: Playback → AI subtitles → diagnostics toggle and clear button (docs/adr/0002). */
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

internal fun LazyListScope.subtitleDiagnosticsItems(
    enabled: Boolean,
    onItemFocused: () -> Unit,
) {
    item(key = "subtitle_ai_diagnostics_toggle") {
        SubtitleDiagnosticsToggle(enabled = enabled, onItemFocused = onItemFocused)
    }
    item(key = "subtitle_ai_diagnostics_clear") {
        SubtitleDiagnosticsClearButton(enabled = enabled)
    }
}

@Composable
private fun SubtitleDiagnosticsToggle(
    enabled: Boolean,
    onItemFocused: () -> Unit,
    viewModel: SubtitleDiagnosticsViewModel = hiltViewModel(),
) {
    val isOn by viewModel.enabled.collectAsStateWithLifecycle()
    val sizeBytes by viewModel.sizeBytes.collectAsStateWithLifecycle()
    val sizeMb = "%.1f".format(sizeBytes / 1_048_576.0)
    ToggleSettingsItem(
        icon = Icons.Default.BugReport,
        title = stringResource(R.string.sub_diag_title),
        subtitle = stringResource(R.string.sub_diag_desc, sizeMb, viewModel.directoryPath),
        isChecked = isOn,
        onCheckedChange = viewModel::setEnabled,
        onFocused = onItemFocused,
        enabled = enabled,
        expandSubtitleOnFocus = true,
    )
}

@Composable
private fun SubtitleDiagnosticsClearButton(
    enabled: Boolean,
    viewModel: SubtitleDiagnosticsViewModel = hiltViewModel(),
) {
    Button(
        onClick = viewModel::clear,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Text(
            text = stringResource(R.string.sub_diag_clear),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary
        )
    }
}
