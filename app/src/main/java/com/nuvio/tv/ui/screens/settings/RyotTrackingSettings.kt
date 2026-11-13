@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.ryot.RyotAuthError
import com.nuvio.tv.data.ryot.RyotAuthException
import com.nuvio.tv.data.ryot.RyotAuthRepository
import com.nuvio.tv.data.ryot.RyotAuthStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Fork: the Ryot row of Settings > Tracking and its login dialog. TrackingSettingsScreen only calls
// RyotTrackingAccountRow().

data class RyotTrackerUiState(
    val isConnected: Boolean = false,
    val userName: String? = null,
    val serverUrl: String? = null,
    val isWorking: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class RyotTrackerViewModel @Inject constructor(
    private val auth: RyotAuthRepository,
    store: RyotAuthStore,
    profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)
    private var job: Job? = null

    val uiState = combine(store.state, profiles.activeProfileId, working, actionError) { state, profileId, busy, error ->
        val current = state.scope.profileId == profileId
        val auth = state.auth.takeIf { current }
        RyotTrackerUiState(
            isConnected = auth != null,
            userName = auth?.userName,
            serverUrl = auth?.baseUrl,
            isWorking = busy,
            errorMessage = error ?: state.error?.takeIf { current }?.let(::message)
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RyotTrackerUiState())

    fun connect(serverUrl: String, userName: String, password: String) {
        if (job?.isActive == true) return
        actionError.value = null
        job = viewModelScope.launch {
            working.value = true
            try {
                auth.connect(serverUrl, userName, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RyotAuthException) {
                actionError.value = message(error.error)
            } catch (error: Exception) {
                actionError.value = message(RyotAuthError.UNREACHABLE)
            } finally {
                working.value = false
            }
        }
    }

    fun disconnect() {
        if (job?.isActive == true) return
        actionError.value = null
        job = viewModelScope.launch {
            working.value = true
            try {
                auth.disconnect()
            } finally {
                working.value = false
            }
        }
    }

    fun clearError() {
        actionError.value = null
    }

    private fun message(error: RyotAuthError): String = context.getString(
        when (error) {
            RyotAuthError.INVALID_URL -> R.string.ryot_error_invalid_url
            RyotAuthError.UNREACHABLE -> R.string.ryot_error_unreachable
            RyotAuthError.NOT_RYOT -> R.string.ryot_error_not_ryot
            RyotAuthError.WRONG_CREDENTIALS -> R.string.ryot_error_wrong_credentials
            RyotAuthError.TWO_FACTOR_REQUIRED -> R.string.ryot_error_two_factor
            RyotAuthError.ACCOUNT_DISABLED -> R.string.ryot_error_account_disabled
            RyotAuthError.SESSION_EXPIRED -> R.string.ryot_error_session_expired
            RyotAuthError.INTEGRATION_FAILED -> R.string.ryot_error_integration
        }
    )
}

@Composable
internal fun RyotTrackingAccountRow(viewModel: RyotTrackerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    val rowFocusRequester = remember { FocusRequester() }

    SettingsActionRow(
        title = stringResource(R.string.ryot_name),
        subtitle = when {
            state.isConnected -> stringResource(R.string.ryot_connected_as, state.userName.orEmpty())
            else -> state.errorMessage ?: stringResource(R.string.ryot_tracking_description)
        },
        value = stringResource(
            if (state.isConnected) R.string.tracking_status_connected else R.string.tracking_status_disconnected
        ),
        valueColor = if (state.isConnected) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
        leadingIcon = Icons.Default.Dns,
        leadingArtworkSize = 40.dp,
        onClick = {
            viewModel.clearError()
            showDialog = true
        },
        modifier = Modifier.focusRequester(rowFocusRequester)
    )

    if (showDialog) {
        val close = {
            showDialog = false
            runCatching { rowFocusRequester.requestFocus() }
            Unit
        }
        if (state.isConnected) {
            RyotConnectedDialog(state, onDisconnect = viewModel::disconnect, onDismiss = close)
        } else {
            RyotLoginDialog(state, onConnect = viewModel::connect, onDismiss = close)
        }
    }
}

@Composable
private fun RyotLoginDialog(
    state: RyotTrackerUiState,
    onConnect: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(state.serverUrl.orEmpty()) }
    var userName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val urlFocusRequester = remember { FocusRequester() }
    val canConnect = serverUrl.isNotBlank() && userName.isNotBlank() && password.isNotEmpty() && !state.isWorking

    LaunchedEffect(Unit) { runCatching { urlFocusRequester.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.ryot_login_title),
        subtitle = stringResource(R.string.ryot_login_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), modifier = Modifier.fillMaxWidth()) {
            InputField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                placeholder = stringResource(R.string.ryot_server_url_placeholder),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().focusRequester(urlFocusRequester)
            )
            InputField(
                value = userName,
                onValueChange = { userName = it },
                placeholder = stringResource(R.string.ryot_username_placeholder),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.ryot_password_placeholder),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = { if (canConnect) onConnect(serverUrl, userName, password) },
                modifier = Modifier.fillMaxWidth()
            )
            state.errorMessage?.let {
                Text(text = it, color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(if (state.isWorking) R.string.tracking_status_connecting else R.string.ryot_connect),
                onClick = { onConnect(serverUrl, userName, password) },
                primary = true,
                enabled = canConnect
            )
        }
    }
}

@Composable
private fun RyotConnectedDialog(
    state: RyotTrackerUiState,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(if (confirmDisconnect) R.string.ryot_disconnect_title else R.string.ryot_name),
        subtitle = if (confirmDisconnect) {
            stringResource(R.string.ryot_disconnect_subtitle)
        } else {
            stringResource(R.string.ryot_connected_details, state.userName.orEmpty(), state.serverUrl.orEmpty())
        },
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        state.errorMessage?.let {
            Text(text = it, color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodyMedium)
        }
        SettingsDialogActionRow {
            if (confirmDisconnect) {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { confirmDisconnect = false })
                SettingsDialogActionButton(
                    text = stringResource(R.string.trakt_disconnect),
                    onClick = {
                        onDisconnect()
                        onDismiss()
                    },
                    primary = true,
                    enabled = !state.isWorking
                )
            } else {
                SettingsDialogActionButton(
                    text = stringResource(R.string.trakt_disconnect),
                    onClick = { confirmDisconnect = true },
                    enabled = !state.isWorking
                )
                SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onDismiss, primary = true)
            }
        }
    }
}
