package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.tv.ui.util.keepLtr
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import androidx.tv.material3.Card
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings

/*
 * Fork: the Style tab of the subtitle overlay's third column (Info | Style).
 * Content is upstream 1.1.0's subtitle style rail (SubtitleSelectionOverlay.kt), moved here so the
 * fork overlay stays readable. Keep it in step with upstream's style rail on each sync.
 */

@Composable
internal fun SubtitleOverlayStyleRail(
    subtitleStyle: SubtitleStyleSettings,
    isEditingHdrStyle: Boolean,
    subtitleDelayMs: Int,
    listState: LazyListState,
    onMoveLeft: () -> Unit,
    focusRequesters: Map<String, FocusRequester>,
    onStyleFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    isStyleDisabledByLibass: Boolean = false,
    /** Fork: Up on the first item goes back to the Style tab. */
    onMoveUpFromTop: (() -> Unit)? = null
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    val dispatchStyleEvent: (PlayerEvent) -> Unit = { event ->
        if (!isStyleDisabledByLibass) {
            onEvent(event)
        }
    }
    val styleCardModifier = if (isStyleDisabledByLibass) Modifier.alpha(0.35f) else Modifier
    val styleRailModifier = if (isStyleDisabledByLibass) Modifier.focusProperties { canFocus = false } else Modifier
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(styleRailModifier)
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
            modifier = Modifier
                .heightIn(max = 720.dp)
        ) {
            item {
                Card(
                    onClick = { dispatchStyleEvent(PlayerEvent.OnShowSubtitleDelayOverlay) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(styleCardModifier)
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.DelaySet]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    val moveUp = onMoveUpFromTop ?: return@onPreviewKeyEvent false
                                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) moveUp()
                                    true
                                }
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.DelaySet) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_tab_delay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = formatSubtitleDelay(subtitleDelayMs).keepLtr(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            item {
                // Fork: per-title "Top + black background" (SubtitleTopBox.kt).
                val topBoxOn by SubtitleTopBox.enabled.collectAsState()
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_top_box),
                    modifier = styleCardModifier
                ) {
                    ToggleChip(
                        label = if (topBoxOn) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                        isEnabled = topBoxOn,
                        onMoveLeft = onMoveLeft,
                        focusRequester = focusRequesters[StyleFocusKey.TopBox],
                        focusKey = StyleFocusKey.TopBox,
                        onFocused = onStyleFocused,
                        onClick = { dispatchStyleEvent(PlayerEvent.OnToggleSubtitleTopBox) }
                    )
                    Text(
                        text = stringResource(R.string.subtitle_top_box_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_style_font_size),
                    modifier = styleCardModifier
                ) {
                    StepperRow(
                        value = "${subtitleStyle.size}%",
                        onDecrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) },
                        onIncrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeIncrease],
                        decrementFocusKey = StyleFocusKey.FontSizeDecrease,
                        incrementFocusKey = StyleFocusKey.FontSizeIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_style_bold),
                    modifier = styleCardModifier
                ) {
                    ToggleChip(
                        label = if (subtitleStyle.bold) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                        isEnabled = subtitleStyle.bold,
                        onMoveLeft = onMoveLeft,
                        focusRequester = focusRequesters[StyleFocusKey.Bold],
                        focusKey = StyleFocusKey.Bold,
                        onFocused = onStyleFocused,
                        onClick = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                    )
                }
            }
            item {
                OverlaySectionCard(
                    title = if (isEditingHdrStyle) {
                        stringResource(R.string.subtitle_style_text_color_hdr)
                    } else {
                        stringResource(R.string.subtitle_style_text_color)
                    },
                    modifier = styleCardModifier
                ) {
                    ColorChipRow(
                        colors = SubtitleTextColorPresets,
                        selectedColor = subtitleStyle.textColor,
                        onMoveLeft = onMoveLeft,
                        focusRequesters = focusRequesters,
                        focusKeyPrefix = StyleFocusKey.TextColorPrefix,
                        onFocused = onStyleFocused,
                        onColorSelected = { color -> dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(color)) }
                    )
                }
            }
            item {
                OverlaySectionCard(
                    title = if (isEditingHdrStyle) {
                        stringResource(R.string.subtitle_style_text_opacity_hdr)
                    } else {
                        stringResource(R.string.subtitle_style_text_opacity)
                    },
                    modifier = styleCardModifier
                ) {
                    val currentColor = Color(subtitleStyle.textColor)
                    val currentAlphaPercent = (currentColor.alpha * 100f).roundToInt().coerceIn(0, 100)
                    StepperRow(
                        value = "$currentAlphaPercent%",
                        onDecrease = {
                            val newAlpha = (currentAlphaPercent - 10).coerceAtLeast(0) / 100f
                            dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                        },
                        onIncrease = {
                            val newAlpha = (currentAlphaPercent + 10).coerceAtMost(100) / 100f
                            dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                        },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.OpacityDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.OpacityIncrease],
                        decrementFocusKey = StyleFocusKey.OpacityDecrease,
                        incrementFocusKey = StyleFocusKey.OpacityIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            if (isEditingHdrStyle) {
                item {
                    OverlaySectionCard(
                        title = stringResource(R.string.subtitle_style_brightness_hdr),
                        modifier = styleCardModifier
                    ) {
                        StepperRow(
                            value = "${subtitleStyle.hdrBrightness}%",
                            onDecrease = {
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleHdrBrightness(subtitleStyle.hdrBrightness - 10))
                            },
                            onIncrease = {
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleHdrBrightness(subtitleStyle.hdrBrightness + 10))
                            },
                            onMoveLeft = onMoveLeft,
                            decrementFocusRequester = focusRequesters[StyleFocusKey.HdrBrightnessDecrease],
                            incrementFocusRequester = focusRequesters[StyleFocusKey.HdrBrightnessIncrease],
                            decrementFocusKey = StyleFocusKey.HdrBrightnessDecrease,
                            incrementFocusKey = StyleFocusKey.HdrBrightnessIncrease,
                            onFocusChanged = onStyleFocused
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_style_outline),
                    modifier = styleCardModifier
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                        ToggleChip(
                            label = if (subtitleStyle.outlineEnabled) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                            isEnabled = subtitleStyle.outlineEnabled,
                            onMoveLeft = onMoveLeft,
                            focusRequester = focusRequesters[StyleFocusKey.OutlineToggle],
                            focusKey = StyleFocusKey.OutlineToggle,
                            onFocused = onStyleFocused,
                            onClick = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                        )
                        ColorChipRow(
                            colors = SubtitleOutlineColorPresets,
                            selectedColor = subtitleStyle.outlineColor,
                            enabled = subtitleStyle.outlineEnabled,
                            onMoveLeft = onMoveLeft,
                            focusRequesters = focusRequesters,
                            focusKeyPrefix = StyleFocusKey.OutlineColorPrefix,
                            onFocused = onStyleFocused,
                            onColorSelected = { color ->
                                if (!subtitleStyle.outlineEnabled) {
                                    dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                                }
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineColor(color))
                            }
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_style_bottom_offset),
                    modifier = styleCardModifier
                ) {
                    StepperRow(
                        value = subtitleStyle.verticalOffset.toString(),
                        onDecrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) },
                        onIncrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.OffsetDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.OffsetIncrease],
                        decrementFocusKey = StyleFocusKey.OffsetDecrease,
                        incrementFocusKey = StyleFocusKey.OffsetIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                Card(
                    onClick = { dispatchStyleEvent(PlayerEvent.OnResetSubtitleDefaults) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    modifier = Modifier
                        .then(styleCardModifier)
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.Reset]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.Reset) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_reset_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun OverlaySectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        content()
    }
}

@Composable
private fun StepperRow(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    valueWidth: Dp = 84.dp,
    onMoveLeft: (() -> Unit)? = null,
    decrementFocusRequester: FocusRequester? = null,
    incrementFocusRequester: FocusRequester? = null,
    decrementFocusKey: String? = null,
    incrementFocusKey: String? = null,
    onFocusChanged: ((String) -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            contentDescription = stringResource(R.string.cd_decrease),
            onClick = onDecrease,
            onMoveLeft = onMoveLeft,
            focusRequester = decrementFocusRequester,
            focusKey = decrementFocusKey,
            onFocused = onFocusChanged
        )
        Box(
            modifier = Modifier
                .width(valueWidth)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(NuvioTheme.radii.md))
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_increase),
            onClick = onIncrease,
            focusRequester = incrementFocusRequester,
            focusKey = incrementFocusKey,
            onFocused = onFocusChanged
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                if (isFocused) {
                    Modifier.border(
                        NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        RoundedCornerShape(NuvioTheme.radii.md)
                    )
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
        scale = IconButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isEnabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    val switchSemantics = Modifier.semantics {
        role = Role.Switch
        toggleableState = if (isEnabled) ToggleableState.On else ToggleableState.Off
    }
    Card(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
                .then(switchSemantics)
        } else {
            Modifier
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
                .then(switchSemantics)
        },
        colors = overlayCardColors(selected = isEnabled),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) NuvioTheme.colors.OnSecondary else Color.White,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        )
    }
}

@Composable
private fun ColorChipRow(
    colors: List<Color>,
    selectedColor: Int,
    enabled: Boolean = true,
    onMoveLeft: (() -> Unit)? = null,
    focusRequesters: Map<String, FocusRequester>,
    focusKeyPrefix: String,
    onFocused: ((String) -> Unit)? = null,
    onColorSelected: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(colors) { color ->
            val focusKey = "$focusKeyPrefix:${color.toArgb()}"
            ColorChip(
                color = if (enabled) color else color.copy(alpha = 0.35f),
                isSelected = color.toArgb() == selectedColor,
                enabled = enabled,
                onMoveLeft = if (color == colors.firstOrNull()) onMoveLeft else null,
                focusRequester = focusRequesters[focusKey],
                focusKey = focusKey,
                onFocused = onFocused,
                onClick = { onColorSelected(color.toArgb()) }
            )
        }
    }
}

@Composable
private fun ColorChip(
    color: Color,
    isSelected: Boolean,
    enabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    val swatchLabel = stringResource(R.string.cd_color_swatch, overlaySwatchColorLabel(color))
    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        modifier = Modifier
            .size(NuvioTheme.spacing.xxl)
            .clearAndSetSemantics {
                contentDescription = swatchLabel
                role = Role.RadioButton
                this.selected = isSelected
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                when {
                    isSelected -> Modifier.border(NuvioTheme.spacing.xxs, Color.White, CircleShape)
                    isFocused -> Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), CircleShape)
                    else -> Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        shape = CardDefaults.shape(CircleShape)
        ,
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {}
}

@Composable
private fun overlayCardColors(selected: Boolean) = CardDefaults.colors(
    containerColor = if (selected) NuvioTheme.colors.Secondary else Color.Transparent,
    focusedContainerColor = if (selected) NuvioTheme.colors.Secondary else Color.Transparent
)

@Composable
private fun overlayCardBorder() = CardDefaults.border(
    border = Border(
        border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ),
    focusedBorder = Border(
        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    )
)

/** Accessible name for a colour swatch: its own RGB value, as that is the only colour
 *  description available on this screen (there are no per-colour name resources). */
private fun overlaySwatchColorLabel(color: Color): String {
    val rgb = color.toArgb() and 0xFFFFFF
    return "#" + rgb.toString(16).padStart(6, '0').uppercase()
}


internal object StyleFocusKey {
    const val FontSizeDecrease = "font_size_decrease"
    const val FontSizeIncrease = "font_size_increase"
    const val Bold = "bold"
    const val OutlineToggle = "outline_toggle"
    const val OffsetDecrease = "offset_decrease"
    const val OffsetIncrease = "offset_increase"
    const val DelaySet = "delay_set"
    const val TopBox = "top_box"
    const val Reset = "reset"
    const val TextColorPrefix = "text_color"
    const val OpacityDecrease = "opacity_decrease"
    const val OpacityIncrease = "opacity_increase"
    const val HdrBrightnessDecrease = "hdr_brightness_decrease"
    const val HdrBrightnessIncrease = "hdr_brightness_increase"
    const val OutlineColorPrefix = "outline_color"
}


internal fun String.isHdrBrightnessFocusKey(): Boolean =
    this == StyleFocusKey.HdrBrightnessDecrease || this == StyleFocusKey.HdrBrightnessIncrease

/** The HDR brightness item sits right after opacity, shifting every later item down by one. */
internal fun styleListIndexForFocusKey(focusKey: String, hasHdrBrightnessItem: Boolean): Int {
    if (focusKey.isHdrBrightnessFocusKey()) return 6
    val index = styleListIndexForFocusKey(focusKey)
    return if (hasHdrBrightnessItem && index >= 6) index + 1 else index
}

internal fun styleListIndexForFocusKey(focusKey: String): Int {
    return when {
        focusKey == StyleFocusKey.DelaySet -> 0
        focusKey == StyleFocusKey.TopBox -> 1
        focusKey == StyleFocusKey.FontSizeDecrease || focusKey == StyleFocusKey.FontSizeIncrease -> 2
        focusKey == StyleFocusKey.Bold -> 3
        focusKey.startsWith("${StyleFocusKey.TextColorPrefix}:") -> 4
        focusKey == StyleFocusKey.OpacityDecrease || focusKey == StyleFocusKey.OpacityIncrease -> 5
        focusKey == StyleFocusKey.OutlineToggle || focusKey.startsWith("${StyleFocusKey.OutlineColorPrefix}:") -> 6
        focusKey == StyleFocusKey.OffsetDecrease || focusKey == StyleFocusKey.OffsetIncrease -> 7
        focusKey == StyleFocusKey.Reset -> 8
        else -> 0
    }
}


@Composable
internal fun rememberStyleFocusRequesters(): Map<String, FocusRequester> {
    return remember {
        listOf(
            StyleFocusKey.FontSizeDecrease,
            StyleFocusKey.FontSizeIncrease,
            StyleFocusKey.Bold,
            StyleFocusKey.OpacityDecrease,
            StyleFocusKey.OpacityIncrease,
            StyleFocusKey.HdrBrightnessDecrease,
            StyleFocusKey.HdrBrightnessIncrease,
            StyleFocusKey.OutlineToggle,
            StyleFocusKey.OffsetDecrease,
            StyleFocusKey.OffsetIncrease,
            StyleFocusKey.DelaySet,
            StyleFocusKey.TopBox,
            StyleFocusKey.Reset
        ).associateWith { FocusRequester() } +
            SubtitleTextColorPresets.associate { color ->
                "${StyleFocusKey.TextColorPrefix}:${color.toArgb()}" to FocusRequester()
            } +
            SubtitleOutlineColorPresets.associate { color ->
                "${StyleFocusKey.OutlineColorPrefix}:${color.toArgb()}" to FocusRequester()
            }
    }
}



private fun formatSubtitleDelay(delayMs: Int): String {
    return when {
        delayMs > 0 -> "+${delayMs}ms"
        delayMs < 0 -> "${delayMs}ms"
        else -> "0ms"
    }
}
