package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Fork: what AutoSync is doing with the selected add-on subtitle, for the subtitle menu. 1.1.0 shows
 * nothing while a run is in progress, the "Auto synced" chip after a match, a toast after a no-fit
 * and nothing at all for the other failures (no embedded reference, inconclusive, download error).
 */

internal enum class AutoSyncRunState { RUNNING, FAILED }

/** Why a run ended without a match, for the Info card. */
internal enum class AutoSyncFailureReason {
    /** The subtitle clearly fits none of the video's own subtitles: another release. */
    NO_FIT,
    /** The video has no embedded subtitles to compare against. */
    NO_EMBEDDED,
    /** Embedded subtitles exist but none could serve (image-based, or not loaded in time). */
    NO_USABLE_REFERENCE,
    /** The add-on subtitle could not be downloaded. */
    DOWNLOAD_FAILED,
    /** No confident match, and nothing shows it does not fit. */
    INCONCLUSIVE,
    /** The run ended without an analysis result (error, or the result could not be applied). */
    NOT_APPLIED,
}

internal fun autoSyncFailureReason(outcome: AutoSyncAnalysisOutcome?): AutoSyncFailureReason =
    when (outcome) {
        AutoSyncAnalysisOutcome.NO_FIT -> AutoSyncFailureReason.NO_FIT
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS -> AutoSyncFailureReason.NO_EMBEDDED
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE -> AutoSyncFailureReason.NO_USABLE_REFERENCE
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE -> AutoSyncFailureReason.DOWNLOAD_FAILED
        AutoSyncAnalysisOutcome.INCONCLUSIVE -> AutoSyncFailureReason.INCONCLUSIVE
        null -> AutoSyncFailureReason.NOT_APPLIED
    }

internal data class AutoSyncRunStatus(
    val url: String,
    val state: AutoSyncRunState,
    val reason: AutoSyncFailureReason? = null,
)

internal object AutoSyncRunningSubtitle {
    private class Run(val url: String, val job: Job) {
        @Volatile var outcome: AutoSyncAnalysisOutcome? = null
    }

    private val current = MutableStateFlow<Run?>(null)
    private val _status = MutableStateFlow<AutoSyncRunStatus?>(null)
    val status: StateFlow<AutoSyncRunStatus?> = _status.asStateFlow()

    /**
     * [job] checks [subtitleUrl]. The subtitle shows as running until the job ends. A job that ends
     * on its own without [syncedUrl] naming the subtitle failed; a cancelled one was replaced (another
     * subtitle, run, stream or player) and leaves no status.
     */
    fun track(
        subtitleUrl: String,
        job: Job,
        syncedUrl: () -> String? = { AutoSyncSyncedSubtitle.url.value },
    ) {
        val run = Run(subtitleUrl, job)
        current.value = run
        _status.value = AutoSyncRunStatus(subtitleUrl, AutoSyncRunState.RUNNING)
        job.invokeOnCompletion { cause ->
            // A newer run may have replaced this one; only the current run sets the status.
            if (!current.compareAndSet(run, null)) return@invokeOnCompletion
            _status.value = when {
                cause != null -> null
                syncedUrl() == subtitleUrl -> null // the "Auto synced" chip takes over
                else -> AutoSyncRunStatus(
                    subtitleUrl,
                    AutoSyncRunState.FAILED,
                    autoSyncFailureReason(run.outcome),
                )
            }
        }
    }

    /** The analysis of the current run for [subtitleUrl] ended with [outcome]; the last one wins. */
    fun noteOutcome(subtitleUrl: String, outcome: AutoSyncAnalysisOutcome) {
        current.value?.takeIf { it.url == subtitleUrl }?.outcome = outcome
    }
}

/** Hook at the start of an AutoSync job: the menu shows [subtitleUrl] as syncing until it ends. */
internal fun CoroutineScope.trackAutoSyncRun(subtitleUrl: String) =
    AutoSyncRunningSubtitle.track(subtitleUrl, coroutineContext.job)

/**
 * AutoSync chip for the selected add-on subtitle: green "Synced", white "Syncing…" or yellow
 * "Failed"; empty when AutoSync has not checked it. It replaces upstream's single-color
 * "Auto synced" chip, which looked too much like "Syncing…".
 */
@Composable
internal fun AutoSyncStatusChip(subtitleUrl: String, selected: Boolean) {
    val status by AutoSyncRunningSubtitle.status.collectAsState()
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    if (!selected) return
    val state = autoSyncDotState(subtitleUrl, status, syncedUrl) ?: return

    val color = autoSyncChipColor(state)
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
            .border(NuvioTheme.spacing.hairline, color.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (state) {
                AutoSyncDotState.SYNCED -> Icons.Default.Check
                AutoSyncDotState.FAILED, AutoSyncDotState.NO_FIT -> Icons.Default.SyncProblem
                AutoSyncDotState.RUNNING, AutoSyncDotState.EMBEDDED -> Icons.Default.Sync
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(autoSyncStateLabel(state)),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * AutoSync lines of the subtitle Info card for the add-on subtitle at [subtitleUrl]: whether it
 * is syncing, synced or failed, and why it failed. Empty when AutoSync has not checked it.
 */
@Composable
internal fun AutoSyncInfoLines(subtitleUrl: String?) {
    val status by AutoSyncRunningSubtitle.status.collectAsState()
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    if (subtitleUrl == null) return
    val runStatus = status?.takeIf { it.url == subtitleUrl }
    val state = autoSyncDotState(subtitleUrl, runStatus, syncedUrl) ?: return
    val stateLabel = stringResource(autoSyncStateLabel(state))
    Text(
        text = stringResource(R.string.autosync_info_status, stateLabel),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.isFailure) autoSyncDotColor(state) else Color.White.copy(alpha = 0.7f),
    )
    if (state == AutoSyncDotState.SYNCED) {
        AutoSyncAdjustmentLines(subtitleUrl)
        return
    }
    val reason = runStatus?.reason?.takeIf { state.isFailure } ?: return
    Text(
        text = stringResource(autoSyncFailureReasonText(reason)),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
    )
}

/** How the synced subtitle was corrected and by how much ([AutoSyncAdjustment]). */
@Composable
private fun AutoSyncAdjustmentLines(subtitleUrl: String) {
    val applied by AutoSyncLastAdjustment.value.collectAsState()
    val adjustment = applied?.takeIf { it.url == subtitleUrl }?.adjustment ?: return
    val lines = buildList {
        add(
            stringResource(
                R.string.autosync_info_method,
                stringResource(
                    when (adjustment.method) {
                        AutoSyncMethod.KEPT -> R.string.autosync_method_kept
                        AutoSyncMethod.DELAY -> R.string.autosync_method_delay
                        AutoSyncMethod.DELAY_AND_SPEED -> R.string.autosync_method_speed
                    }
                ),
            )
        )
        add(
            when (adjustment.method) {
                AutoSyncMethod.KEPT -> stringResource(
                    R.string.autosync_info_kept_diff,
                    formatAutoSyncShift(adjustment.startShiftMs).removePrefix("+"),
                )
                AutoSyncMethod.DELAY -> stringResource(
                    R.string.autosync_info_shift,
                    formatAutoSyncShift(adjustment.startShiftMs),
                )
                AutoSyncMethod.DELAY_AND_SPEED -> stringResource(
                    R.string.autosync_info_shift_range,
                    formatAutoSyncShift(adjustment.startShiftMs),
                    formatAutoSyncShift(adjustment.endShiftMs),
                    formatAutoSyncSpeed(adjustment.speedPercent),
                )
            }
        )
        if (adjustment.locallyAdjustedLines > 0) {
            add(stringResource(R.string.autosync_info_local_lines, adjustment.locallyAdjustedLines))
        }
        if (adjustment.switchedSubtitle) add(stringResource(R.string.autosync_info_switched))
    }
    lines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

private fun autoSyncFailureReasonText(reason: AutoSyncFailureReason): Int = when (reason) {
    AutoSyncFailureReason.NO_FIT -> R.string.autosync_reason_no_fit
    AutoSyncFailureReason.NO_EMBEDDED -> R.string.autosync_reason_no_embedded
    AutoSyncFailureReason.NO_USABLE_REFERENCE -> R.string.autosync_reason_no_usable_reference
    AutoSyncFailureReason.DOWNLOAD_FAILED -> R.string.autosync_reason_download_failed
    AutoSyncFailureReason.INCONCLUSIVE -> R.string.autosync_reason_inconclusive
    AutoSyncFailureReason.NOT_APPLIED -> R.string.autosync_reason_not_applied
}

/**
 * The dot on the player's subtitle button: whether the subtitle on screen is in sync with the
 * video. Green for an embedded track (timed with the video itself) or an add-on subtitle AutoSync
 * synced or confirmed; white while AutoSync checks; red when it found the subtitle does not fit
 * this release; amber when it could not tell (no embedded reference, inconclusive, error).
 */
internal enum class AutoSyncDotState { RUNNING, SYNCED, EMBEDDED, FAILED, NO_FIT }

internal val AutoSyncDotState.isFailure: Boolean
    get() = this == AutoSyncDotState.FAILED || this == AutoSyncDotState.NO_FIT

internal fun autoSyncDotState(
    selectedAddonUrl: String?,
    runStatus: AutoSyncRunStatus?,
    syncedUrl: String?,
    embeddedSelected: Boolean = false,
): AutoSyncDotState? {
    if (selectedAddonUrl == null) return if (embeddedSelected) AutoSyncDotState.EMBEDDED else null
    if (runStatus?.url == selectedAddonUrl) {
        return when (runStatus.state) {
            AutoSyncRunState.RUNNING -> AutoSyncDotState.RUNNING
            AutoSyncRunState.FAILED ->
                if (runStatus.reason == AutoSyncFailureReason.NO_FIT) AutoSyncDotState.NO_FIT else AutoSyncDotState.FAILED
        }
    }
    return if (syncedUrl == selectedAddonUrl) AutoSyncDotState.SYNCED else null
}

/**
 * Wraps the player's subtitle button with a small dot telling whether the subtitle is in sync,
 * visible with the controls without opening the subtitle menu (colors in [AutoSyncDotState]).
 * No dot with subtitles off, or for an add-on subtitle AutoSync has not checked.
 */
@Composable
internal fun AutoSyncStatusBadge(
    selectedAddonUrl: String?,
    embeddedSelected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val runStatus by AutoSyncRunningSubtitle.status.collectAsState()
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    val state = autoSyncDotState(selectedAddonUrl, runStatus, syncedUrl, embeddedSelected)
    Box {
        content()
        if (state != null) {
            val pulse = if (state == AutoSyncDotState.RUNNING) {
                val transition = rememberInfiniteTransition(label = "autoSyncDot")
                val alpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "autoSyncDotAlpha",
                )
                alpha
            } else {
                1f
            }
            val label = stringResource(autoSyncStateLabel(state))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(10.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(1.5.dp)
                    .background(autoSyncDotColor(state), CircleShape)
                    .semantics { contentDescription = label }
            )
        }
    }
}

private val AutoSyncGreen = Color(0xFF4CAF50)
private val AutoSyncAmber = Color(0xFFFFB300)
private val AutoSyncRed = Color(0xFFE53935)

private fun autoSyncDotColor(state: AutoSyncDotState): Color = when (state) {
    AutoSyncDotState.RUNNING -> Color.White
    AutoSyncDotState.SYNCED, AutoSyncDotState.EMBEDDED -> AutoSyncGreen
    AutoSyncDotState.FAILED -> AutoSyncAmber
    AutoSyncDotState.NO_FIT -> AutoSyncRed
}

/** The subtitle-menu chip: one color per outcome, so every failure (no-fit included) is yellow. */
private fun autoSyncChipColor(state: AutoSyncDotState): Color = when (state) {
    AutoSyncDotState.RUNNING -> Color.White
    AutoSyncDotState.SYNCED, AutoSyncDotState.EMBEDDED -> AutoSyncGreen
    AutoSyncDotState.FAILED, AutoSyncDotState.NO_FIT -> AutoSyncAmber
}

private fun autoSyncStateLabel(state: AutoSyncDotState): Int = when (state) {
    AutoSyncDotState.RUNNING -> R.string.autosync_label_syncing
    AutoSyncDotState.SYNCED -> R.string.autosync_label_synced
    AutoSyncDotState.EMBEDDED -> R.string.autosync_label_embedded
    AutoSyncDotState.FAILED, AutoSyncDotState.NO_FIT -> R.string.autosync_label_failed
}
