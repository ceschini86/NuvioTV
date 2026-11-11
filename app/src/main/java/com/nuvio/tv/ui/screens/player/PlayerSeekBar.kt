package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Fork: the Stremio-style seek bar on top of 1.1.0's progress fill and chapter marks. A thin flat
 * track with the accent fill, an accent thumb with a white ring while focused or scrubbing, and a
 * bar that grows while the user scrubs: a little on a quick press, fully once the key is held.
 * PlayerScreen's progress hosts call it in place of upstream's ProgressBar (ADR 0001).
 */

/** Scrub visuals: idle, quick press (reduced growth), held or continuous scrub (full growth). */
private const val SCRUB_IDLE = 0
private const val SCRUB_LIGHT = 1
private const val SCRUB_FULL = 2

/** A press held longer than this counts as a held scrub. */
private const val SCRUB_HOLD_MS = 200L

/** How long the grown bar stays after the scrub ends, so quick presses in a row do not flicker. */
private const val SCRUB_SETTLE_MS = 280L

/** Clears the largest thumb (22dp plus its 2dp ring) without clipping. */
private val SeekBarHeight = 30.dp

@Composable
internal fun PlayerSeekBar(
    currentPosition: Long,
    duration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    /** OK on the bar: upstream wires play/pause here, so OK resumes after a scrub. */
    onClick: (() -> Unit)? = null,
    /** A preview seek is pending (the user is scrubbing). */
    isScrubbing: Boolean = false,
    /** Start at the full scrub look; used by the seek overlay, which only shows while seeking. */
    preferFullScrubVisual: Boolean = false,
    /** Position (ms) up to which content is buffered. Pass 0 to skip the overlay. */
    bufferedPosition: Long = 0L,
    /** Each chapter after the first gets a mark where it starts. */
    chapters: List<PlayerChapter> = emptyList()
) {
    val accentBrush = NuvioTheme.palette.accentBrush()
    val seekBarLabel = stringResource(R.string.cd_seek_bar)
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val bufferedProgress = if (duration > 0 && bufferedPosition > currentPosition) {
        (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "progress"
    )
    val animatedBufferedProgress by animateFloatAsState(
        targetValue = bufferedProgress,
        animationSpec = tween(200),
        label = "bufferedProgress"
    )

    var isFocused by remember { mutableStateOf(false) }
    var isHoldingSeek by remember { mutableStateOf(false) }
    var scrubLevel by remember {
        mutableIntStateOf(if (preferFullScrubVisual) SCRUB_FULL else SCRUB_IDLE)
    }
    val scrubbingActive = isScrubbing || isHoldingSeek
    LaunchedEffect(scrubbingActive, preferFullScrubVisual) {
        if (scrubbingActive) {
            if (preferFullScrubVisual) {
                scrubLevel = SCRUB_FULL
            } else {
                if (scrubLevel == SCRUB_IDLE) scrubLevel = SCRUB_LIGHT
                delay(SCRUB_HOLD_MS)
                scrubLevel = SCRUB_FULL
            }
        } else {
            delay(SCRUB_SETTLE_MS)
            scrubLevel = SCRUB_IDLE
        }
    }
    val lightScrubbing = scrubLevel == SCRUB_LIGHT
    val fullScrubbing = scrubLevel == SCRUB_FULL
    val thumbSize by animateDpAsState(
        targetValue = when {
            fullScrubbing -> 22.dp
            lightScrubbing || isFocused -> 20.dp
            else -> 18.dp
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "thumbSize"
    )
    val trackHeight by animateDpAsState(
        targetValue = when {
            fullScrubbing -> 9.dp
            lightScrubbing || isFocused -> 7.dp
            else -> 5.dp
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "trackHeight"
    )
    val trackShape = RoundedCornerShape(percent = 50)
    val thumbColor = NuvioTheme.colors.Secondary
    val showThumbRing = isFocused || lightScrubbing || fullScrubbing

    fun startScrub(repeatCount: Int) {
        isHoldingSeek = true
        if (repeatCount > 0) {
            scrubLevel = SCRUB_FULL
        } else if (scrubLevel == SCRUB_IDLE) {
            scrubLevel = SCRUB_LIGHT
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SeekBarHeight)
            .graphicsLayer { clip = false }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .semantics {
                contentDescription = seekBarLabel
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            isHoldingSeek = false
                            onSeekCommit()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> onClick != null
                        else -> false
                    }
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (onClick != null) {
                            // Ignore key-repeat so holding OK doesn't toggle repeatedly.
                            if (native.repeatCount == 0) onClick()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (downFocusRequester != null) {
                            runCatching { downFocusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (upFocusRequester != null) {
                            runCatching { upFocusRequester.requestFocus() }
                            true
                        } else if (onUpKey != null) {
                            onUpKey()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        startScrub(native.repeatCount)
                        onSeekPreview(
                            PlayerScrubRates.deltaMsForKeyRepeat(
                                repeatCount = native.repeatCount,
                                forward = native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            )
                        )
                        true
                    }
                    else -> false
                }
            }
    ) {
        val trackWidth = maxWidth
        val thumbStart = (trackWidth * animatedProgress - thumbSize / 2)
            .coerceIn(0.dp, (trackWidth - thumbSize).coerceAtLeast(0.dp))

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(trackShape)
                .background(Color.White.copy(alpha = 0.26f))
                .playerProgressFill(
                    progress = { animatedProgress },
                    bufferedProgress = { animatedBufferedProgress },
                    bufferedColor = NuvioTheme.colors.Secondary,
                    playedBrush = accentBrush
                )
                // Chapter marks, drawn over the fill.
                .drawBehind {
                    if (chapters.isNotEmpty() && duration > 0) {
                        val markWidth = 2.dp.toPx()
                        chapters.forEach { chapter ->
                            val fraction = chapter.startMs.toFloat() / duration.toFloat()
                            if (fraction <= 0f || fraction >= 1f) return@forEach
                            drawRect(
                                color = Color.Black.copy(alpha = 0.6f),
                                topLeft = Offset(size.width * fraction - markWidth / 2f, 0f),
                                size = Size(markWidth, size.height)
                            )
                        }
                    }
                }
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = thumbStart)
                .size(thumbSize)
                .background(thumbColor, CircleShape)
                .then(
                    if (showThumbRing) Modifier.border(BorderStroke(2.dp, Color.White), CircleShape)
                    else Modifier
                )
        )
    }
}

/** True while a preview seek is pending; recomposes only when that flips. */
@Composable
internal fun rememberPlayerScrubbing(viewModel: PlayerViewModel): State<Boolean> {
    val flow = remember(viewModel) {
        viewModel.uiState
            .map { it.pendingPreviewSeekPosition != null }
            .distinctUntilChanged()
    }
    // No preview seek is pending when the controls first compose; the flow corrects it next frame.
    return flow.collectAsState(initial = false)
}
