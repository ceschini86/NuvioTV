package com.nuvio.tv.core.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * A global singleton to track the scrolling state of the application.
 * Uses a reference counter to handle multiple simultaneous scrollable containers
 * (e.g., vertical list + horizontal rows) without state fighting.
 */
object ScrollStateRegistry {
    private var activeScrollCount by mutableIntStateOf(0)

    /**
     * True if any primary scrollable container is currently scrolling.
     */
    val isScrolling by derivedStateOf { activeScrollCount > 0 }

    /**
     * Reports that a container has started scrolling.
     */
    fun reportScrollStarted() {
        activeScrollCount++
    }

    /**
     * Reports that a container has stopped scrolling.
     */
    fun reportScrollStopped() {
        activeScrollCount = (activeScrollCount - 1).coerceAtLeast(0)
    }
}
