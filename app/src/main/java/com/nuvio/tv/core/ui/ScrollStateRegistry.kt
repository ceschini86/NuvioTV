package com.nuvio.tv.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A global singleton to track the scrolling state of the application.
 * This allows performance-critical components like image loaders to
 * adjust their behavior (e.g., memory-only loads) without being part
 * of the Compose composition tree, avoiding expensive recompositions.
 */
object ScrollStateRegistry {
    /**
     * True if any primary scrollable container (like the home grid) is currently scrolling.
     * Uses Compose State so it can be observed in leaf-level LaunchEffects for zero-jank reloads.
     */
    var isScrolling by mutableStateOf(false)
}
