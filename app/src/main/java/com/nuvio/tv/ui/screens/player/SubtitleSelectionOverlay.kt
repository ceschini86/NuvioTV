@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
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
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"
private const val SubtitleFocusTag = "SubtitleFocus"

private val OverlayTextColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

private val OverlayOutlineColors = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

private const val RailFadeDurationMs = 120

@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val languageItems = remember(
        internalTracks,
        addonSubtitles,
        subtitleStyle.preferredLanguage,
        subtitleStyle.secondaryPreferredLanguage
    ) {
        buildSubtitleLanguageRailItems(
            internalTracks = internalTracks,
            addonSubtitles = addonSubtitles,
            preferredLanguage = subtitleStyle.preferredLanguage,
            secondaryPreferredLanguage = subtitleStyle.secondaryPreferredLanguage,
            noneLabel = noneLabel
        )
    }
    val initialLanguageKey = remember(languageItems, selectedInternalIndex, selectedAddonSubtitle, internalTracks) {
        selectedSubtitleLanguageKey(
            languageItems = languageItems,
            internalTracks = internalTracks,
            selectedInternalIndex = selectedInternalIndex,
            selectedAddonSubtitle = selectedAddonSubtitle
        )
    }
    var selectedLanguageKey by rememberSaveable { mutableStateOf(initialLanguageKey) }
    var lastFocusedLanguageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocusedOptionId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocusedStyleKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showOptionRail by remember(visible) { mutableStateOf(false) }
    var showStyleRail by remember(visible) { mutableStateOf(false) }
    var languageRailFocusToken by remember(visible) { mutableStateOf(0) }
    var languageEnsureVisibleToken by remember(visible) { mutableStateOf(0) }
    var optionRailFocusToken by remember(visible) { mutableStateOf(0) }
    var styleRailFocusToken by remember(visible) { mutableStateOf(0) }
    val languageRailFocusRequester = remember { FocusRequester() }
    val optionRailFocusRequester = remember { FocusRequester() }
    val styleRailFocusRequester = remember { FocusRequester() }
    val languageItemRequesters = rememberFocusRequesterMap(languageItems.map { it.key })
    val initialSubtitleOptions = remember(
        internalTracks,
        selectedInternalIndex,
        addonSubtitles,
        selectedAddonSubtitle,
        installedSubtitleAddonOrder,
        initialLanguageKey
    ) {
        buildSubtitleOptionRailItems(
            selectedLanguageKey = initialLanguageKey,
            internalTracks = internalTracks,
            selectedInternalIndex = selectedInternalIndex,
            addonSubtitles = addonSubtitles,
            selectedAddonSubtitle = selectedAddonSubtitle,
            installedAddonOrder = installedSubtitleAddonOrder
        )
    }
    val subtitleOptions = remember(
        internalTracks,
        selectedInternalIndex,
        addonSubtitles,
        selectedAddonSubtitle,
        installedSubtitleAddonOrder,
        selectedLanguageKey
    ) {
        buildSubtitleOptionRailItems(
            selectedLanguageKey = selectedLanguageKey,
            internalTracks = internalTracks,
            selectedInternalIndex = selectedInternalIndex,
            addonSubtitles = addonSubtitles,
            selectedAddonSubtitle = selectedAddonSubtitle,
            installedAddonOrder = installedSubtitleAddonOrder
        )
    }
    val optionItemRequesters = rememberFocusRequesterMap(subtitleOptions.map { it.id })
    val styleRequesters = rememberStyleFocusRequesters()
    var preferInitialLanguageFocus by remember(visible) { mutableStateOf(true) }
    var restoreCustomStyleFocus by remember(visible) { mutableStateOf(false) }
    val selectedOptionId = remember(subtitleOptions) {
        subtitleOptions.firstOrNull { it.isSelected }?.id
    }
    val initialSelectedOptionId = remember(initialSubtitleOptions) {
        initialSubtitleOptions.firstOrNull { it.isSelected }?.id
    }
    val shouldOpenOptionRail = remember(initialLanguageKey) {
        initialLanguageKey != SubtitleOffLanguageKey
    }
    val shouldOpenStyleRail = remember(initialSelectedOptionId) {
        initialSelectedOptionId != null
    }
    var preferSelectedOptionFocus by remember(visible) { mutableStateOf(shouldOpenOptionRail) }
    val languageRestoreKey = remember(
        languageItems,
        selectedLanguageKey,
        lastFocusedLanguageKey,
        preferInitialLanguageFocus
    ) {
        if (preferInitialLanguageFocus) {
            selectedLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
                ?: languageItems.firstOrNull()?.key
        } else {
            lastFocusedLanguageKey
                ?.takeIf { key -> languageItems.any { it.key == key } }
                ?: selectedLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
                ?: languageItems.firstOrNull()?.key
        }
    }
    val optionRestoreId = remember(
        subtitleOptions,
        lastFocusedOptionId,
        selectedOptionId,
        preferSelectedOptionFocus
    ) {
        if (preferSelectedOptionFocus) {
            selectedOptionId ?: subtitleOptions.firstOrNull()?.id
        } else {
            lastFocusedOptionId
                ?.takeIf { id -> subtitleOptions.any { it.id == id } }
                ?: selectedOptionId
                ?: subtitleOptions.firstOrNull()?.id
        }
    }
    val styleRestoreKey = remember(lastFocusedStyleKey, restoreCustomStyleFocus) {
        if (restoreCustomStyleFocus) {
            lastFocusedStyleKey ?: StyleFocusKey.DelaySet
        } else {
            StyleFocusKey.DelaySet
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 76.dp)
    ) {
        LaunchedEffect(visible) {
            if (visible) {
                selectedLanguageKey = initialLanguageKey
                showOptionRail = shouldOpenOptionRail
                showStyleRail = shouldOpenStyleRail
                preferInitialLanguageFocus = true
                preferSelectedOptionFocus = shouldOpenOptionRail
                restoreCustomStyleFocus = false
                if (shouldOpenOptionRail && initialSelectedOptionId != null) {
                    Log.d(
                        SubtitleFocusTag,
                        "overlay_open focus=option selectedLanguage=$initialLanguageKey selectedOption=$initialSelectedOptionId showStyle=$shouldOpenStyleRail"
                    )
                    languageEnsureVisibleToken += 1
                    optionItemRequesters[initialSelectedOptionId]?.requestFocusAfterFrames()
                        ?: optionRailFocusRequester.requestFocusAfterFrames()
                } else {
                    Log.d(
                        SubtitleFocusTag,
                        "overlay_open focus=language selectedLanguage=$initialLanguageKey showOption=$shouldOpenOptionRail showStyle=$shouldOpenStyleRail"
                    )
                    languageRailFocusRequester.requestFocusAfterFrames()
                }
            }
        }

        LaunchedEffect(visible, selectedLanguageKey, selectedOptionId) {
            if (!visible) return@LaunchedEffect
            showStyleRail = when {
                selectedLanguageKey == SubtitleOffLanguageKey -> false
                selectedOptionId != null -> true
                else -> showStyleRail
            }
        }

        LaunchedEffect(visible, showOptionRail, optionRailFocusToken, optionRestoreId) {
            if (visible && showOptionRail && optionRailFocusToken > 0) {
                optionRestoreId
                    ?.let(optionItemRequesters::get)
                    ?.requestFocusAfterFrames()
                    ?: optionRailFocusRequester.requestFocusAfterFrames()
            }
        }

        LaunchedEffect(visible, showStyleRail, styleRailFocusToken, styleRestoreKey) {
            if (visible && showStyleRail && styleRailFocusToken > 0) {
                styleRequesters[styleRestoreKey]?.requestFocusAfterFrames()
                    ?: styleRailFocusRequester.requestFocusAfterFrames()
            }
        }

        Column(verticalArrangement = Arrangement.Bottom) {
            Text(
                text = stringResource(R.string.subtitle_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SubtitleLanguageRail(
                    items = languageItems,
                    selectedLanguageKey = selectedLanguageKey,
                    railFocusRequester = languageRailFocusRequester,
                    itemFocusRequesters = languageItemRequesters,
                    restoreLanguageKey = languageRestoreKey,
                    restoreFocusToken = languageRailFocusToken,
                    ensureVisibleKey = selectedLanguageKey,
                    ensureVisibleToken = languageEnsureVisibleToken,
                    rightFocusRequester = if (showOptionRail && subtitleOptions.isNotEmpty()) {
                        optionRestoreId?.let(optionItemRequesters::get) ?: optionRailFocusRequester
                    } else {
                        FocusRequester.Default
                    },
                    onLanguageSelected = { languageKey ->
                        Log.d(
                            SubtitleFocusTag,
                            "language_select key=$languageKey previous=$selectedLanguageKey"
                        )
                        selectedLanguageKey = languageKey
                        preferInitialLanguageFocus = false
                        if (languageKey == SubtitleOffLanguageKey) {
                            showOptionRail = false
                            showStyleRail = false
                            preferSelectedOptionFocus = false
                            onDisableSubtitles()
                        } else {
                            showOptionRail = true
                            showStyleRail = languageKey == selectedSubtitleLanguageKey(
                                languageItems = languageItems,
                                internalTracks = internalTracks,
                                selectedInternalIndex = selectedInternalIndex,
                                selectedAddonSubtitle = selectedAddonSubtitle
                            )
                            preferSelectedOptionFocus = true
                            optionRailFocusToken += 1
                        }
                    },
                    onLanguageFocused = {
                        preferInitialLanguageFocus = false
                        lastFocusedLanguageKey = it
                    }
                )

                RailFadeIn(visible = showOptionRail) {
                    SubtitleOptionsRail(
                        selectedLanguageKey = selectedLanguageKey,
                        options = subtitleOptions,
                        isLoadingAddons = isLoadingAddons,
                        railFocusRequester = optionRailFocusRequester,
                        railLeftFocusRequester = languageRestoreKey
                            ?.let(languageItemRequesters::get)
                            ?: languageRailFocusRequester,
                        railRightFocusRequester = if (showStyleRail) {
                            styleRequesters[styleRestoreKey] ?: styleRailFocusRequester
                        } else {
                            FocusRequester.Default
                        },
                        itemFocusRequesters = optionItemRequesters,
                        restoreOptionId = optionRestoreId,
                        onOptionFocused = {
                            preferSelectedOptionFocus = false
                            lastFocusedOptionId = it
                        },
                        onMoveLeft = {
                            Log.d(
                                SubtitleFocusTag,
                                "option_move_left restoreLanguage=$languageRestoreKey currentOption=$optionRestoreId"
                            )
                            languageRailFocusToken += 1
                        },
                        onMoveRight = {
                            showStyleRail = true
                            styleRailFocusToken += 1
                        },
                        onInternalTrackSelected = {
                            onInternalTrackSelected(it)
                            showStyleRail = true
                        },
                        onAddonSubtitleSelected = {
                            onAddonSubtitleSelected(it)
                            showStyleRail = true
                        }
                    )
                }

                RailFadeIn(visible = showStyleRail) {
                    SubtitleStyleRail(
                        subtitleStyle = subtitleStyle,
                        subtitleDelayMs = subtitleDelayMs,
                        railFocusRequester = styleRailFocusRequester,
                        railLeftFocusRequester = optionRestoreId
                            ?.let(optionItemRequesters::get)
                            ?: if (subtitleOptions.isNotEmpty()) {
                                optionRailFocusRequester
                            } else {
                                languageRailFocusRequester
                            },
                        focusRequesters = styleRequesters,
                        restoreStyleKey = styleRestoreKey,
                        onStyleFocused = {
                            lastFocusedStyleKey = it
                            if (it != StyleFocusKey.DelaySet) {
                                restoreCustomStyleFocus = true
                            }
                        },
                        onEvent = onEvent
                    )
                }
            }
        }
    }
}

@Composable
private fun RailFadeIn(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    if (!visible) return

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = RailFadeDurationMs,
                easing = FastOutLinearInEasing
            )
        )
    }

    Box(modifier = Modifier.graphicsLayer(alpha = alpha.value)) {
        content()
    }
}

@Composable
private fun SubtitleLanguageRail(
    items: List<SubtitleLanguageRailItem>,
    selectedLanguageKey: String,
    railFocusRequester: FocusRequester,
    itemFocusRequesters: Map<String, FocusRequester>,
    restoreLanguageKey: String?,
    restoreFocusToken: Int,
    ensureVisibleKey: String?,
    ensureVisibleToken: Int,
    rightFocusRequester: FocusRequester,
    onLanguageSelected: (String) -> Unit,
    onLanguageFocused: (String) -> Unit
) {
    var railHadFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var pendingRestoreKey by remember(items) { mutableStateOf<String?>(null) }

    LaunchedEffect(items, pendingRestoreKey) {
        val targetKey = pendingRestoreKey ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.key == targetKey }.takeIf { it >= 0 } ?: return@LaunchedEffect
        Log.d(
            SubtitleFocusTag,
            "language_restore_request key=$targetKey index=$targetIndex selected=$selectedLanguageKey firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
        delay(40)
        itemFocusRequesters[targetKey]?.let { requester ->
            runCatching { requester.requestFocusAfterFrames() }
        }
        Log.d(
            SubtitleFocusTag,
            "language_restore_complete key=$targetKey index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        pendingRestoreKey = null
    }

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken > 0) {
            Log.d(
                SubtitleFocusTag,
                "language_restore_token token=$restoreFocusToken key=$restoreLanguageKey"
            )
            pendingRestoreKey = restoreLanguageKey ?: items.firstOrNull()?.key
        }
    }

    LaunchedEffect(ensureVisibleToken) {
        if (ensureVisibleToken <= 0) return@LaunchedEffect
        val targetKey = ensureVisibleKey ?: return@LaunchedEffect
        val targetIndex = items.indexOfFirst { it.key == targetKey }
            .takeIf { it >= 0 }
            ?: return@LaunchedEffect
        delay(180)
        Log.d(
            SubtitleFocusTag,
            "language_ensure_visible token=$ensureVisibleToken key=$targetKey index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollItemIntoView(targetIndex)
    }

    RailColumn(width = 200.dp, title = stringResource(R.string.subtitle_tab_languages)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            modifier = Modifier
                .heightIn(max = 720.dp)
                .focusRequester(railFocusRequester)
                .onFocusChanged { state ->
                    val justGainedFocus = !railHadFocus && state.hasFocus
                    val justLostFocus = railHadFocus && !state.hasFocus
                    railHadFocus = state.hasFocus
                    if (justGainedFocus) {
                        Log.d(
                            SubtitleFocusTag,
                            "language_rail_focus gained restoreKey=$restoreLanguageKey pending=$pendingRestoreKey firstVisible=${listState.firstVisibleItemIndex}"
                        )
                    }
                    if (justLostFocus) {
                        Log.d(
                            SubtitleFocusTag,
                            "language_rail_focus lost lastFocusedVisible=${listState.firstVisibleItemIndex}"
                        )
                    }
                    if (justGainedFocus && pendingRestoreKey == null) {
                        pendingRestoreKey = restoreLanguageKey ?: items.firstOrNull()?.key
                    }
                }
        ) {
            items(items = items, key = { item -> item.key }) { item ->
                SubtitleLanguageCard(
                    item = item,
                    isSelected = item.key == selectedLanguageKey,
                    onClick = { onLanguageSelected(item.key) },
                    focusRequester = itemFocusRequesters[item.key],
                    rightFocusRequester = rightFocusRequester,
                    onFocused = { onLanguageFocused(item.key) }
                )
            }
        }
    }
}

@Composable
private fun SubtitleOptionsRail(
    selectedLanguageKey: String,
    options: List<SubtitleOptionRailItem>,
    isLoadingAddons: Boolean,
    railFocusRequester: FocusRequester,
    railLeftFocusRequester: FocusRequester,
    railRightFocusRequester: FocusRequester,
    itemFocusRequesters: Map<String, FocusRequester>,
    restoreOptionId: String?,
    onOptionFocused: (String) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit
) {
    var railHadFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var pendingRestoreId by remember(options) { mutableStateOf<String?>(null) }

    LaunchedEffect(options, pendingRestoreId) {
        val targetId = pendingRestoreId ?: return@LaunchedEffect
        val targetIndex = options.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        Log.d(
            SubtitleFocusTag,
            "option_restore_request id=$targetId index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        listState.scrollToItem(targetIndex)
        delay(40)
        itemFocusRequesters[targetId]?.let { requester ->
            runCatching { requester.requestFocusAfterFrames() }
        }
        Log.d(
            SubtitleFocusTag,
            "option_restore_complete id=$targetId index=$targetIndex firstVisible=${listState.firstVisibleItemIndex}"
        )
        pendingRestoreId = null
    }

    RailColumn(width = 300.dp, title = stringResource(R.string.subtitle_dialog_title)) {
        when {
            selectedLanguageKey == SubtitleOffLanguageKey -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_none))
            }

            options.isEmpty() && isLoadingAddons -> {
                OverlayLoadingCard(text = stringResource(R.string.subtitle_loading_addon))
            }

            options.isEmpty() -> {
                OverlayEmptyCard(text = stringResource(R.string.subtitle_no_addon))
            }

            else -> {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    modifier = Modifier
                        .heightIn(max = 720.dp)
                        .focusRequester(railFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                return@onPreviewKeyEvent false
                            }
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    Log.d(
                                        SubtitleFocusTag,
                                        "option_rail_key_left restoreOption=$restoreOptionId selectedLanguage=$selectedLanguageKey firstVisible=${listState.firstVisibleItemIndex}"
                                    )
                                    onMoveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }
                        .onFocusChanged { state ->
                            val justGainedFocus = !railHadFocus && state.hasFocus
                            val justLostFocus = railHadFocus && !state.hasFocus
                            railHadFocus = state.hasFocus
                            if (justGainedFocus) {
                                Log.d(
                                    SubtitleFocusTag,
                                    "option_rail_focus gained restoreOption=$restoreOptionId pending=$pendingRestoreId firstVisible=${listState.firstVisibleItemIndex}"
                                )
                                pendingRestoreId = restoreOptionId ?: options.firstOrNull()?.id
                            }
                            if (justLostFocus) {
                                Log.d(
                                    SubtitleFocusTag,
                                    "option_rail_focus lost lastVisible=${listState.firstVisibleItemIndex}"
                                )
                            }
                        }
                ) {
                    items(items = options, key = { option -> option.id }) { option ->
                        SubtitleOptionCard(
                            item = option,
                            focusRequester = itemFocusRequesters[option.id],
                            leftFocusRequester = railLeftFocusRequester,
                            rightFocusRequester = railRightFocusRequester,
                            onFocused = { onOptionFocused(option.id) },
                            onMoveRight = onMoveRight,
                            onClick = {
                                when (option.kind) {
                                    SubtitleOptionKind.INTERNAL -> {
                                        option.internalTrackIndex?.let(onInternalTrackSelected)
                                    }

                                    SubtitleOptionKind.ADDON -> {
                                        option.addonSubtitle?.let(onAddonSubtitleSelected)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleStyleRail(
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    railFocusRequester: FocusRequester,
    railLeftFocusRequester: FocusRequester,
    focusRequesters: Map<String, FocusRequester>,
    restoreStyleKey: String,
    onStyleFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit
) {
    var railHadFocus by remember { mutableStateOf(false) }

    RailColumn(width = 280.dp, title = stringResource(R.string.subtitle_style_title)) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier
                .heightIn(max = 720.dp)
                .focusRequester(railFocusRequester)
                .onFocusChanged { state ->
                    val justGainedFocus = !railHadFocus && state.hasFocus
                    railHadFocus = state.hasFocus
                    if (justGainedFocus) {
                        val target = focusRequesters[restoreStyleKey]
                            ?: focusRequesters[StyleFocusKey.FontSizeDecrease]
                        target?.let { requester -> runCatching { requester.requestFocus() } }
                    }
                }
        ) {
            item {
                Card(
                    onClick = { onEvent(PlayerEvent.OnShowSubtitleDelayOverlay) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.DelaySet]))
                        .focusProperties { left = railLeftFocusRequester }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.DelaySet) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_tab_delay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = formatSubtitleDelay(subtitleDelayMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_font_size)) {
                    StepperRow(
                        value = "${subtitleStyle.size}%",
                        onDecrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) },
                        onIncrease = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) },
                        rowLeftFocusRequester = railLeftFocusRequester,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeIncrease],
                        decrementFocusKey = StyleFocusKey.FontSizeDecrease,
                        incrementFocusKey = StyleFocusKey.FontSizeIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_bold)) {
                    ToggleChip(
                        label = if (subtitleStyle.bold) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                        isEnabled = subtitleStyle.bold,
                        leftFocusRequester = railLeftFocusRequester,
                        focusRequester = focusRequesters[StyleFocusKey.Bold],
                        focusKey = StyleFocusKey.Bold,
                        onFocused = onStyleFocused,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_text_color)) {
                    ColorChipRow(
                        colors = OverlayTextColors,
                        selectedColor = subtitleStyle.textColor,
                        rowLeftFocusRequester = railLeftFocusRequester,
                        focusRequesters = focusRequesters,
                        focusKeyPrefix = StyleFocusKey.TextColorPrefix,
                        onFocused = onStyleFocused,
                        onColorSelected = { color -> onEvent(PlayerEvent.OnSetSubtitleTextColor(color)) }
                    )
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_outline)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToggleChip(
                            label = if (subtitleStyle.outlineEnabled) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                            isEnabled = subtitleStyle.outlineEnabled,
                            leftFocusRequester = railLeftFocusRequester,
                            focusRequester = focusRequesters[StyleFocusKey.OutlineToggle],
                            focusKey = StyleFocusKey.OutlineToggle,
                            onFocused = onStyleFocused,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                        )
                        ColorChipRow(
                            colors = OverlayOutlineColors,
                            selectedColor = subtitleStyle.outlineColor,
                            enabled = subtitleStyle.outlineEnabled,
                            rowLeftFocusRequester = railLeftFocusRequester,
                            focusRequesters = focusRequesters,
                            focusKeyPrefix = StyleFocusKey.OutlineColorPrefix,
                            onFocused = onStyleFocused,
                            onColorSelected = { color ->
                                if (!subtitleStyle.outlineEnabled) {
                                    onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                                }
                                onEvent(PlayerEvent.OnSetSubtitleOutlineColor(color))
                            }
                        )
                    }
                }
            }
            item {
                OverlaySectionCard(title = stringResource(R.string.subtitle_style_bottom_offset)) {
                    StepperRow(
                        value = subtitleStyle.verticalOffset.toString(),
                        onDecrease = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) },
                        onIncrease = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) },
                        rowLeftFocusRequester = railLeftFocusRequester,
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
                    onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    modifier = Modifier
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.Reset]))
                        .focusProperties { left = railLeftFocusRequester }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.Reset) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_reset_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RailColumn(
    width: androidx.compose.ui.unit.Dp,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioColors.TextTertiary
        )
        content()
    }
}

@Composable
private fun SubtitleLanguageCard(
    item: SubtitleLanguageRailItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    rightFocusRequester: FocusRequester,
    onFocused: () -> Unit
) {
    val textColor = if (isSelected) NuvioColors.OnSecondary else Color.White

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
            .onFocusChanged {
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "language_focused key=${item.key} label=${item.label}"
                    )
                    onFocused()
                }
            },
        colors = overlayCardColors(selected = isSelected),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.count > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
                CountBadge(count = item.count, selected = isSelected)
            }
        }
    }
}

@Composable
private fun SubtitleOptionCard(
    item: SubtitleOptionRailItem,
    focusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onMoveRight: () -> Unit,
    onClick: () -> Unit
) {
    val titleColor = if (item.isSelected) NuvioColors.OnSecondary else Color.White
    val metaColor = if (item.isSelected) {
        NuvioColors.OnSecondary.copy(alpha = 0.72f)
    } else {
        NuvioColors.TextTertiary
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
            }
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (
                            rightFocusRequester == FocusRequester.Default &&
                            event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN
                        ) {
                            onMoveRight()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .onFocusChanged {
                if (it.isFocused) {
                    Log.d(
                        SubtitleFocusTag,
                        "option_focused id=${item.id} title=${item.title} selected=${item.isSelected}"
                    )
                    onFocused()
                }
            },
        colors = overlayCardColors(selected = item.isSelected),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = overlayCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourceChip(label = item.sourceLabel, selected = item.isSelected)
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor
                )
                if (!item.meta.isNullOrBlank()) {
                    Text(
                        text = item.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = metaColor
                    )
                }
            }
            if (item.isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioColors.OnSecondary
                )
            }
        }
    }
}

@Composable
private fun CountBadge(
    count: Int,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) {
                    Color.White.copy(alpha = 0.18f)
                } else {
                    NuvioColors.Secondary.copy(alpha = 0.85f)
                },
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NuvioColors.OnSecondary else NuvioColors.OnSecondary
        )
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (selected) {
                    NuvioColors.OnSecondary.copy(alpha = 0.14f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                RoundedCornerShape(999.dp)
            )
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = NuvioColors.OnSecondary.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(999.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                NuvioColors.OnSecondary.copy(alpha = 0.9f)
            } else {
                Color.White.copy(alpha = 0.78f)
            }
        )
    }
}

@Composable
private fun OverlayLoadingCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextTertiary
            )
        }
    }
}

@Composable
private fun OverlayEmptyCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextTertiary
        )
    }
}

@Composable
private fun OverlaySectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    rowLeftFocusRequester: FocusRequester? = null,
    decrementFocusRequester: FocusRequester? = null,
    incrementFocusRequester: FocusRequester? = null,
    decrementFocusKey: String? = null,
    incrementFocusKey: String? = null,
    onFocusChanged: ((String) -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            onClick = onDecrease,
            leftFocusRequester = rowLeftFocusRequester,
            focusRequester = decrementFocusRequester,
            focusKey = decrementFocusKey,
            onFocused = onFocusChanged
        )
        Box(
            modifier = Modifier
                .width(valueWidth)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
    onClick: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (leftFocusRequester != null) {
                    Modifier.focusProperties { left = leftFocusRequester }
                } else {
                    Modifier
                }
            )
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, NuvioColors.FocusRing, RoundedCornerShape(12.dp))
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
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = IconButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isEnabled: Boolean,
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            Modifier
                .focusRequester(focusRequester)
                .then(
                    if (leftFocusRequester != null) {
                        Modifier.focusProperties { left = leftFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        } else {
            Modifier
                .then(
                    if (leftFocusRequester != null) {
                        Modifier.focusProperties { left = leftFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        },
        colors = overlayCardColors(selected = isEnabled),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) NuvioColors.OnSecondary else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ColorChipRow(
    colors: List<Color>,
    selectedColor: Int,
    enabled: Boolean = true,
    rowLeftFocusRequester: FocusRequester? = null,
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
                leftFocusRequester = if (color == colors.firstOrNull()) rowLeftFocusRequester else null,
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
    leftFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        modifier = Modifier
            .size(32.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (leftFocusRequester != null) {
                    Modifier.focusProperties { left = leftFocusRequester }
                } else {
                    Modifier
                }
            )
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, Color.White, CircleShape)
                    isFocused -> Modifier.border(2.dp, NuvioColors.FocusRing, CircleShape)
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
    containerColor = if (selected) NuvioColors.Secondary else Color.Transparent,
    focusedContainerColor = if (selected) NuvioColors.Secondary else Color.Transparent
)

@Composable
private fun overlayCardBorder() = CardDefaults.border(
    border = Border(
        border = BorderStroke(2.dp, Color.Transparent),
        shape = RoundedCornerShape(12.dp)
    ),
    focusedBorder = Border(
        border = BorderStroke(2.dp, NuvioColors.FocusRing),
        shape = RoundedCornerShape(12.dp)
    )
)

private object StyleFocusKey {
    const val FontSizeDecrease = "font_size_decrease"
    const val FontSizeIncrease = "font_size_increase"
    const val Bold = "bold"
    const val OutlineToggle = "outline_toggle"
    const val OffsetDecrease = "offset_decrease"
    const val OffsetIncrease = "offset_increase"
    const val DelaySet = "delay_set"
    const val Reset = "reset"
    const val TextColorPrefix = "text_color"
    const val OutlineColorPrefix = "outline_color"
}

@Composable
private fun rememberFocusRequesterMap(keys: List<String>): Map<String, FocusRequester> {
    return remember(keys) { keys.associateWith { FocusRequester() } }
}

@Composable
private fun rememberStyleFocusRequesters(): Map<String, FocusRequester> {
    return remember {
        listOf(
            StyleFocusKey.FontSizeDecrease,
            StyleFocusKey.FontSizeIncrease,
            StyleFocusKey.Bold,
            StyleFocusKey.OutlineToggle,
            StyleFocusKey.OffsetDecrease,
            StyleFocusKey.OffsetIncrease,
            StyleFocusKey.DelaySet,
            StyleFocusKey.Reset
        ).associateWith { FocusRequester() } +
            OverlayTextColors.associate { color ->
                "${StyleFocusKey.TextColorPrefix}:${color.toArgb()}" to FocusRequester()
            } +
            OverlayOutlineColors.associate { color ->
                "${StyleFocusKey.OutlineColorPrefix}:${color.toArgb()}" to FocusRequester()
            }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollItemIntoView(
    targetIndex: Int,
    contextItemsBefore: Int = 1
) {
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem((targetIndex - contextItemsBefore).coerceAtLeast(0))
}

private data class SubtitleLanguageRailItem(
    val key: String,
    val label: String,
    val count: Int
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val isSelected: Boolean,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null
)

private fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    noneLabel: String
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    val sortedItems = counts.entries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, count) ->
            SubtitleLanguageRailItem(
                key = key,
                label = subtitleLanguageLabel(key),
                count = count
            )
        }

    return listOf(
        SubtitleLanguageRailItem(
            key = SubtitleOffLanguageKey,
            label = noneLabel,
            count = 0
        )
    ) + sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    installedAddonOrder: List<String>
): List<SubtitleOptionRailItem> {
    if (selectedLanguageKey == SubtitleOffLanguageKey) return emptyList()

    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            SubtitleOptionRailItem(
                id = "internal:${track.index}",
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = "Built in",
                meta = listOfNotNull(
                    track.codec,
                    if (track.isForced) "Forced" else null
                ).joinToString(" • ").ifBlank { null },
                isSelected = selectedAddonSubtitle == null && track.index == selectedInternalIndex,
                internalTrackIndex = track.index
            )
        }

    val addonItems = addonSubtitles
        .withIndex()
        .filter { (_, subtitle) -> normalizeOverlayLanguageKey(subtitle.lang) == selectedLanguageKey }
        .sortedWith(
            compareBy(
                { (index, subtitle) -> addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE },
                { (index, _) -> index }
            )
        )
        .map { (_, subtitle) ->
            SubtitleOptionRailItem(
                id = "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}",
                kind = SubtitleOptionKind.ADDON,
                title = Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)),
                sourceLabel = subtitle.addonName,
                meta = subtitle.id.takeIf { it.isNotBlank() && it != subtitle.lang },
                isSelected = selectedAddonSubtitle?.id == subtitle.id && selectedAddonSubtitle.url == subtitle.url,
                addonSubtitle = subtitle
            )
        }

    return internalItems + addonItems
}

private fun selectedSubtitleLanguageKey(
    languageItems: List<SubtitleLanguageRailItem>,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String): String {
    return when (key) {
        SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none")
        SubtitleUnknownLanguageKey -> "Unknown"
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    else -> subtitleLanguageLabel(key).lowercase()
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return when {
        delayMs > 0 -> "+${delayMs}ms"
        delayMs < 0 -> "${delayMs}ms"
        else -> "0ms"
    }
}
