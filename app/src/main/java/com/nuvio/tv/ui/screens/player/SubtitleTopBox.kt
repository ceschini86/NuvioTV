@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import com.nuvio.tv.data.local.SubtitleStyleSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Fork: "Top + black background" subtitle mode, a toggle in the Style tab of the subtitle menu.
 * On, subtitles render at the top of the screen on a black box; off, the user's own style is used
 * as it was. The user's style settings are never written: the mode only changes the style the
 * player renders with. It is remembered per title (series or movie id), so it can stay on for the
 * few titles that need it (burned-in text at the bottom, bright footage).
 *
 * Covers the text renderer (ExoPlayer, the add-on sidecar) and mpv. ASS rendered by libass keeps
 * the positions written in the file.
 */
internal object SubtitleTopBox {
    private const val PREFS = "fork_subtitle_top_box"
    private const val KEY_TITLES = "titles"

    private val _enabled = MutableStateFlow(false)

    /** Whether the mode is on for the title playing now. Read on the playback thread as well. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load(context: Context, titleId: String?) {
        _enabled.value = titleId != null && titles(context).contains(titleId)
    }

    /** Flips the mode for [titleId] (session only when null) and returns the new state. */
    fun toggle(context: Context, titleId: String?): Boolean {
        val on = !_enabled.value
        if (titleId != null) {
            val updated = titles(context).toMutableSet().apply { if (on) add(titleId) else remove(titleId) }
            prefs(context).edit { putStringSet(KEY_TITLES, updated) }
        }
        _enabled.update { on }
        return on
    }

    fun reset() {
        _enabled.value = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun titles(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TITLES, null).orEmpty()
}

internal val SubtitleTopBoxBackground = Color.Black.toArgb()

/** Line of a cue moved to the top, as a fraction of the subtitle view's height. */
internal const val SUBTITLE_TOP_BOX_LINE = 0.06f

/** The style to render with while the mode is on: the user's style on a black box. */
internal fun SubtitleStyleSettings.withTopBox(on: Boolean): SubtitleStyleSettings =
    if (on) copy(backgroundColor = SubtitleTopBoxBackground) else this

/**
 * Moves a cue placed in the lower half (or with no position, which renders at the bottom) to the
 * top, anchored by its first line so longer cues grow downwards. Cues already in the upper half
 * stay where they are.
 */
internal fun Cue.movedToTop(): Cue {
    val inLowerHalf = when {
        line == Cue.DIMEN_UNSET -> true
        lineType == Cue.LINE_TYPE_FRACTION -> line >= 0.5f
        lineType == Cue.LINE_TYPE_NUMBER -> line < 0f
        else -> false
    }
    if (!inLowerHalf) return this
    return buildUpon()
        .setLine(SUBTITLE_TOP_BOX_LINE, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()
}

internal fun topBoxCues(cues: List<Cue>, on: Boolean = SubtitleTopBox.enabled.value): List<Cue> =
    if (on && cues.isNotEmpty()) cues.map { it.movedToTop() } else cues

/** Text output hook: moves the renderer's cues to the top while the mode is on. */
internal class SubtitleTopBoxTextOutput(private val delegate: TextOutput) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        if (!SubtitleTopBox.enabled.value) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(CueGroup(topBoxCues(cueGroup.cues, on = true), cueGroup.presentationTimeUs))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(topBoxCues(cues))
    }
}

/** Loads the mode for the title being opened. */
internal fun PlayerRuntimeController.loadSubtitleTopBox() {
    SubtitleTopBox.load(context, contentId)
    _uiState.update { it.copy(subtitleTopBox = SubtitleTopBox.enabled.value) }
}

internal fun PlayerRuntimeController.toggleSubtitleTopBox() {
    val on = SubtitleTopBox.toggle(context, contentId)
    Log.d(PlayerRuntimeController.TAG, "SUB_TOP_BOX on=$on title=$contentId")
    _uiState.update { it.copy(subtitleTopBox = on) }
}
