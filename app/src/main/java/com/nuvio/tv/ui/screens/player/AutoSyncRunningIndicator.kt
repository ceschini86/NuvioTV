package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

internal data class AutoSyncRunStatus(val url: String, val state: AutoSyncRunState)

internal object AutoSyncRunningSubtitle {
    private data class Run(val url: String, val job: Job)

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
                else -> AutoSyncRunStatus(subtitleUrl, AutoSyncRunState.FAILED)
            }
        }
    }
}

/** Hook at the start of an AutoSync job: the menu shows [subtitleUrl] as syncing until it ends. */
internal fun CoroutineScope.trackAutoSyncRun(subtitleUrl: String) =
    AutoSyncRunningSubtitle.track(subtitleUrl, coroutineContext.job)

/**
 * "Syncing…" while AutoSync checks the selected add-on subtitle, "Sync failed" when it ended without
 * a match; empty otherwise ("Auto synced" is upstream's chip).
 */
@Composable
internal fun AutoSyncStatusChip(subtitleUrl: String, selected: Boolean) {
    val status by AutoSyncRunningSubtitle.status.collectAsState()
    val state = status?.takeIf { selected && it.url == subtitleUrl }?.state ?: return
    val failed = state == AutoSyncRunState.FAILED

    val contentColor = NuvioTheme.colors.OnSecondary.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .background(NuvioTheme.colors.OnSecondary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (failed) Icons.Default.SyncProblem else Icons.Default.Sync,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(
                if (failed) R.string.autosync_label_failed else R.string.autosync_label_syncing
            ),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

/** The dot on the player's subtitle button: AutoSync's state for the selected add-on subtitle. */
internal enum class AutoSyncDotState { RUNNING, SYNCED, FAILED }

internal fun autoSyncDotState(
    selectedAddonUrl: String?,
    runStatus: AutoSyncRunStatus?,
    syncedUrl: String?,
): AutoSyncDotState? {
    if (selectedAddonUrl == null) return null
    if (runStatus?.url == selectedAddonUrl) {
        return when (runStatus.state) {
            AutoSyncRunState.RUNNING -> AutoSyncDotState.RUNNING
            AutoSyncRunState.FAILED -> AutoSyncDotState.FAILED
        }
    }
    return if (syncedUrl == selectedAddonUrl) AutoSyncDotState.SYNCED else null
}

/**
 * Wraps the player's subtitle button with a small dot for AutoSync, so the state shows with the
 * controls without opening the subtitle menu: white pulsing while checking, green once synced,
 * amber when it failed (no fit, no embedded reference, inconclusive). No dot otherwise.
 */
@Composable
internal fun AutoSyncStatusBadge(selectedAddonUrl: String?, content: @Composable () -> Unit) {
    val runStatus by AutoSyncRunningSubtitle.status.collectAsState()
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    val state = autoSyncDotState(selectedAddonUrl, runStatus, syncedUrl)
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
            val label = stringResource(
                when (state) {
                    AutoSyncDotState.RUNNING -> R.string.autosync_label_syncing
                    AutoSyncDotState.SYNCED -> R.string.autosync_label_synced
                    AutoSyncDotState.FAILED -> R.string.autosync_label_failed
                }
            )
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

private fun autoSyncDotColor(state: AutoSyncDotState): Color = when (state) {
    AutoSyncDotState.RUNNING -> Color.White
    AutoSyncDotState.SYNCED -> Color(0xFF4CAF50)
    AutoSyncDotState.FAILED -> Color(0xFFFFB300)
}
