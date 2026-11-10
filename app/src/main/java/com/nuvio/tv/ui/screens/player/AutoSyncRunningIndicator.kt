package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fork: the add-on subtitle AutoSync is still checking, so the menu can say so. 1.1.0 shows nothing
 * while a run is in progress, only the "Auto synced" chip after a match or a toast after a no-fit.
 */
internal object AutoSyncRunningSubtitle {
    private data class Run(val url: String, val job: Job)

    private val current = MutableStateFlow<Run?>(null)
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /** [job] checks [subtitleUrl]; the subtitle shows as syncing until that job ends. */
    fun track(subtitleUrl: String, job: Job) {
        val run = Run(subtitleUrl, job)
        current.value = run
        _url.value = subtitleUrl
        job.invokeOnCompletion {
            // A newer run may have replaced this one; only its own end clears the chip.
            if (current.compareAndSet(run, null)) _url.value = null
        }
    }
}

/** Hook at the start of an AutoSync job: the menu shows [subtitleUrl] as syncing until it ends. */
internal fun CoroutineScope.trackAutoSyncRun(subtitleUrl: String) =
    AutoSyncRunningSubtitle.track(subtitleUrl, coroutineContext.job)

/** "Syncing…" chip for the selected add-on subtitle while AutoSync checks it; empty otherwise. */
@Composable
internal fun AutoSyncRunningChip(subtitleUrl: String, selected: Boolean) {
    val runningUrl by AutoSyncRunningSubtitle.url.collectAsState()
    if (!selected || runningUrl != subtitleUrl) return

    val contentColor = NuvioTheme.colors.OnSecondary.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .background(NuvioTheme.colors.OnSecondary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.autosync_label_syncing),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
