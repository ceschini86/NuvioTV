package com.nuvio.tv.ui.util

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.nuvio.tv.core.ui.ScrollStateRegistry
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes the [isScrollInProgress] state of a [ScrollableState] and syncs it
 * to the global [ScrollStateRegistry]. Uses a reference counter to ensure
 * multiple containers don't fight over the global state.
 */
@Composable
fun ScrollStateRegistrySync(scrollableState: ScrollableState) {
    val isReporting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(scrollableState) {
        onDispose {
            if (isReporting.getAndSet(false)) {
                ScrollStateRegistry.reportScrollStopped()
            }
        }
    }

    LaunchedEffect(scrollableState) {
        snapshotFlow { scrollableState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !isReporting.get()) {
                    isReporting.set(true)
                    ScrollStateRegistry.reportScrollStarted()
                } else if (!scrolling && isReporting.get()) {
                    isReporting.set(false)
                    ScrollStateRegistry.reportScrollStopped()
                }
            }
    }
}

/**
 * Remembers a numeric nonce that increments every time global scrolling stops.
 * Cards can use this as a key in [remember] or [LaunchedEffect] to trigger
 * a high-quality image reload once the viewport has settled.
 */
@Composable
fun rememberScrollAwareReloadNonce(): Int {
    var reloadNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { ScrollStateRegistry.isScrolling }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    reloadNonce++
                }
            }
    }
    return reloadNonce
}
