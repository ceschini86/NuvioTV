package com.nuvio.tv.ui.screens

import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.StateFlow

/**
 * fork: one catalog row that [CatalogSeeAllScreen] shows and pages on its own, outside the Home
 * and Search view models. Media server rows and the Channels section use it.
 */
interface CatalogRowSource {
    /** The row, or null while the first page loads or after it failed. */
    val row: StateFlow<CatalogRow?>

    /** True once the first page failed, so the screen stops showing a spinner. */
    val failed: StateFlow<Boolean>

    fun loadMore()
}
