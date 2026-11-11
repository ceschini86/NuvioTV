package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiAdvancedSettings
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiCredentials
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiPingResult
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiProviderCredentials
import com.nuvio.tv.ui.screens.player.subtitles.maskApiKey
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * Fork: Playback → AI subtitles section (providers, keys, profile sync, advanced tuning,
 * subtitle diagnostics). Lives in its own file so upstream settings files only carry the section
 * enum entry and the `when` branch (docs/adr/0001).
 */
@Composable
internal fun PlaybackAiSubtitlesSection(
    playerSettings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    viewModel: PlaybackSettingsViewModel = hiltViewModel()
) {
    val credentials by viewModel.subtitleAiCredentials.collectAsStateWithLifecycle(
        initialValue = SubtitleAiCredentials()
    )
    val advanced by viewModel.subtitleAiAdvancedSettings.collectAsStateWithLifecycle(
        initialValue = SubtitleAiAdvancedSettings.DEFAULT
    )
    val pingResults by viewModel.subtitleAiPingResults.collectAsStateWithLifecycle()
    var keysDialogModel by remember { mutableStateOf<SubtitleAiModel?>(null) }

    val style = playerSettings.subtitleStyle
    val enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL
    val aiOn = enabled && style.aiEnabled

    SettingsToggleRow(
        title = stringResource(R.string.sub_ai_enabled),
        subtitle = stringResource(R.string.sub_ai_enabled_desc),
        checked = style.aiEnabled,
        onToggle = { onUpdate { setSubtitleAiEnabled(!style.aiEnabled) } },
        enabled = enabled
    )
    SettingsToggleRow(
        title = stringResource(R.string.sub_ai_sync_with_profile),
        subtitle = stringResource(R.string.sub_ai_sync_with_profile_desc),
        checked = style.aiSyncWithProfile,
        onToggle = { onUpdate { setSubtitleAiSyncWithProfile(!style.aiSyncWithProfile) } },
        enabled = enabled
    )
    SettingsActionRow(
        title = stringResource(R.string.sub_ai_model),
        subtitle = stringResource(R.string.sub_ai_model_preferred_desc),
        value = subtitleAiModelLabel(preferredModel(style.aiModel)),
        onClick = { onUpdate { setSubtitleAiModel(nextPreferredModel(style.aiModel).name) } },
        enabled = aiOn
    )

    SubtitleAiModel.entries.forEach { model ->
        val provider = credentials.provider(model)
        val hasKeys = provider.usableKeys.isNotEmpty()
        val label = subtitleAiModelLabel(model)
        SettingsSectionLabel(text = label)
        SettingsToggleRow(
            title = stringResource(R.string.sub_ai_provider_enabled),
            subtitle = stringResource(R.string.sub_ai_provider_enabled_desc),
            checked = provider.enabled && hasKeys,
            onToggle = { onUpdate { setSubtitleAiProviderEnabled(model, !(provider.enabled && hasKeys)) } },
            enabled = aiOn && hasKeys
        )
        SettingsActionRow(
            title = stringResource(R.string.sub_ai_provider_keys),
            subtitle = null,
            value = if (hasKeys) {
                stringResource(R.string.sub_ai_provider_keys_count, provider.usableKeys.size) +
                    " · " + maskApiKey(provider.usableKeys.first())
            } else {
                stringResource(R.string.sub_ai_provider_keys_none)
            },
            onClick = { keysDialogModel = model },
            enabled = enabled
        )
    }

    SettingsSectionLabel(
        text = stringResource(R.string.sub_ai_advanced_section),
        description = stringResource(R.string.sub_ai_advanced_section_desc)
    )
    SliderSettingsItem(
        title = stringResource(R.string.sub_ai_max_batch_size),
        value = advanced.maxBatchSize,
        valueText = advanced.maxBatchSize.toString(),
        minValue = 5,
        maxValue = 80,
        step = 5,
        onValueChange = { value -> onUpdate { setSubtitleAiAdvancedSettings(advanced.copy(maxBatchSize = value)) } },
        enabled = aiOn
    )
    SliderSettingsItem(
        title = stringResource(R.string.sub_ai_batch_window),
        value = advanced.batchWindowMs,
        valueText = "${advanced.batchWindowMs} ms",
        minValue = 50,
        maxValue = 2000,
        step = 50,
        onValueChange = { value -> onUpdate { setSubtitleAiAdvancedSettings(advanced.copy(batchWindowMs = value)) } },
        enabled = aiOn
    )
    SliderSettingsItem(
        title = stringResource(R.string.sub_ai_gemini_batch_window),
        value = advanced.geminiBatchWindowMs,
        valueText = "${advanced.geminiBatchWindowMs} ms",
        minValue = 500,
        maxValue = 10000,
        step = 100,
        onValueChange = { value ->
            onUpdate { setSubtitleAiAdvancedSettings(advanced.copy(geminiBatchWindowMs = value)) }
        },
        enabled = aiOn
    )
    val cooldownSeconds = advanced.rateLimitCooldownMs / 1000
    SliderSettingsItem(
        title = stringResource(R.string.sub_ai_rate_limit_cooldown),
        value = cooldownSeconds,
        valueText = "${cooldownSeconds}s",
        minValue = 5,
        maxValue = 300,
        step = 5,
        onValueChange = { value ->
            onUpdate { setSubtitleAiAdvancedSettings(advanced.copy(rateLimitCooldownMs = value * 1000)) }
        },
        enabled = aiOn
    )
    SliderSettingsItem(
        title = stringResource(R.string.sub_ai_gemini_min_interval),
        value = advanced.geminiMinIntervalMs,
        valueText = "${advanced.geminiMinIntervalMs} ms",
        minValue = 1000,
        maxValue = 15000,
        step = 200,
        onValueChange = { value ->
            onUpdate { setSubtitleAiAdvancedSettings(advanced.copy(geminiMinIntervalMs = value)) }
        },
        enabled = aiOn
    )
    SettingsActionRow(
        title = stringResource(R.string.sub_ai_advanced_reset),
        subtitle = null,
        onClick = { onUpdate { resetSubtitleAiAdvancedSettings() } },
        enabled = aiOn
    )

    SubtitleDiagnosticsItems(enabled = enabled)

    keysDialogModel?.let { model ->
        SubtitleAiProviderKeysDialog(
            model = model,
            provider = credentials.provider(model),
            pingResults = pingResults,
            onToggleEnabled = { on -> onUpdate { setSubtitleAiProviderEnabled(model, on) } },
            onAddKey = { key -> viewModel.addSubtitleAiKey(model, key) },
            onRemoveKey = { key -> onUpdate { removeSubtitleAiKey(model, key) } },
            onPingKey = { key -> viewModel.pingSubtitleAiKey(model, key) },
            onDismiss = { keysDialogModel = null }
        )
    }
}

private fun preferredModel(stored: String): SubtitleAiModel =
    SubtitleAiModel.entries.firstOrNull { it.name == stored } ?: SubtitleAiModel.GROQ_LLAMA_70B

private fun nextPreferredModel(stored: String): SubtitleAiModel {
    val entries = SubtitleAiModel.entries
    return entries[(entries.indexOf(preferredModel(stored)) + 1) % entries.size]
}

@Composable
private fun subtitleAiModelLabel(model: SubtitleAiModel): String = when (model) {
    SubtitleAiModel.GEMINI_FLASH_25 -> stringResource(R.string.sub_ai_model_gemini)
    SubtitleAiModel.CLAUDE_HAIKU -> stringResource(R.string.sub_ai_model_claude)
    SubtitleAiModel.GROQ_LLAMA_70B -> stringResource(R.string.sub_ai_model_groq)
}

@Composable
private fun SubtitleAiProviderKeysDialog(
    model: SubtitleAiModel,
    provider: SubtitleAiProviderCredentials,
    pingResults: Map<String, SubtitleAiPingResult>,
    onToggleEnabled: (Boolean) -> Unit,
    /** Returns a string resource id on format error, or null once persisted. */
    onAddKey: suspend (String) -> Int?,
    onRemoveKey: (String) -> Unit,
    onPingKey: suspend (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newKey by remember { mutableStateOf("") }
    var pingingKey by remember { mutableStateOf<String?>(null) }
    var formatErrorRes by remember { mutableStateOf<Int?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    var textFieldEditable by remember { mutableStateOf(false) }
    var pendingFocusAfterAdd by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val inputCardFocusRequester = remember { FocusRequester() }
    val firstTestKeyFocusRequester = remember { FocusRequester() }
    val hasKeys = provider.usableKeys.isNotEmpty()
    val modelLabel = subtitleAiModelLabel(model)

    fun exitKeyEditing() {
        textFieldEditable = false
        isInputFocused = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    LaunchedEffect(hasKeys, provider.usableKeys.firstOrNull()) {
        if (pendingFocusAfterAdd != null) return@LaunchedEffect
        if (hasKeys) {
            firstTestKeyFocusRequester.requestFocusAfterFrames()
        } else {
            inputCardFocusRequester.requestFocusAfterFrames()
        }
    }

    LaunchedEffect(pendingFocusAfterAdd, provider.usableKeys) {
        val target = pendingFocusAfterAdd ?: return@LaunchedEffect
        if (provider.usableKeys.any { it == target }) {
            firstTestKeyFocusRequester.requestFocusAfterFrames()
            pendingFocusAfterAdd = null
        }
    }

    BackHandler(enabled = isInputFocused || textFieldEditable) {
        exitKeyEditing()
    }

    NuvioDialog(
        onDismiss = {
            if (isInputFocused || textFieldEditable) exitKeyEditing() else onDismiss()
        },
        title = stringResource(R.string.sub_ai_provider_keys) + " — " + modelLabel,
        subtitle = stringResource(R.string.sub_ai_api_key_desc),
        width = 760.dp
    ) {
        if (model == SubtitleAiModel.GEMINI_FLASH_25) {
            Text(
                text = stringResource(R.string.sub_ai_gemini_multi_key_warning),
                color = Color(0xFFFFB74D),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sub_ai_provider_enabled),
                    color = NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = provider.enabled && hasKeys,
                    onCheckedChange = onToggleEnabled,
                    enabled = hasKeys
                )
            }
            if (!hasKeys) {
                Text(
                    text = stringResource(R.string.sub_ai_provider_add_key_first),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        provider.usableKeys.forEachIndexed { index, key ->
            val slot = model.name + ":" + key.trim().takeLast(4)
            val ping = pingResults[slot]
            val status = when {
                pingingKey == key -> stringResource(R.string.sub_ai_ping_testing)
                ping == null -> ""
                ping.success -> stringResource(R.string.sub_ai_ping_ok)
                ping.message == "RATE_LIMITED" ||
                    ping.message.contains("429") ||
                    ping.message.contains("rate limit", ignoreCase = true) ->
                    stringResource(R.string.sub_ai_ping_rate_limited)
                else -> stringResource(R.string.sub_ai_ping_fail) + " · " + ping.message
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = maskApiKey(key),
                        color = NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (status.isNotBlank()) {
                        Text(
                            text = status,
                            color = if (ping?.success == true) Color(0xFF7CFFB2) else NuvioTheme.colors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(
                    onClick = {
                        pingingKey = key
                        coroutineScope.launch {
                            onPingKey(key)
                            pingingKey = null
                        }
                    },
                    enabled = pingingKey == null,
                    modifier = if (index == 0) Modifier.focusRequester(firstTestKeyFocusRequester) else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(text = stringResource(R.string.sub_ai_ping))
                }
                Button(
                    onClick = { onRemoveKey(key) },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(text = stringResource(R.string.sub_ai_remove_key))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sub_ai_add_key),
            color = NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Card(
            onClick = {
                textFieldEditable = true
                inputFocusRequester.requestFocus()
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputCardFocusRequester)
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = newKey,
                    onValueChange = {
                        newKey = it
                        formatErrorRes = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .focusProperties { canFocus = textFieldEditable }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                isInputFocused = true
                                textFieldEditable = true
                            } else if (!state.hasFocus) {
                                textFieldEditable = false
                            }
                        }
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when {
                                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    native.action == KeyEvent.ACTION_DOWN -> true
                                (native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                    native.action == KeyEvent.ACTION_DOWN -> {
                                    keyboardController?.hide()
                                    true
                                }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused && textFieldEditable) NuvioTheme.colors.Primary else Color.Transparent
                    ),
                    decorationBox = { inner ->
                        if (newKey.isBlank()) {
                            Text(
                                text = stringResource(R.string.sub_ai_api_key_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        inner()
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = {
                    val key = newKey.trim()
                    if (key.isBlank()) return@Button
                    exitKeyEditing()
                    coroutineScope.launch {
                        val errorRes = onAddKey(key)
                        if (errorRes != null) {
                            formatErrorRes = errorRes
                            return@launch
                        }
                        formatErrorRes = null
                        pendingFocusAfterAdd = key
                        pingingKey = key
                        newKey = ""
                        onPingKey(key)
                        pingingKey = null
                    }
                },
                enabled = newKey.isNotBlank(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(text = stringResource(R.string.sub_ai_add_key))
            }
        }
        formatErrorRes?.let { resId ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(resId),
                color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
