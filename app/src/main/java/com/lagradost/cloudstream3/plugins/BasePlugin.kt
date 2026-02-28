package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

/**
 * Base class for CloudStream plugins.
 * Extensions subclass this and override [load] to register their providers.
 */
abstract class BasePlugin {
    /** Called during plugin initialization. Register APIs here. */
    open fun load(context: Context) {}

    private val _registeredMainAPIs = mutableListOf<MainAPI>()
    private val _registeredExtractorAPIs = mutableListOf<ExtractorApi>()

    val registeredMainAPIs: List<MainAPI> get() = _registeredMainAPIs
    val registeredExtractorAPIs: List<ExtractorApi> get() = _registeredExtractorAPIs

    fun registerMainAPI(api: MainAPI) {
        _registeredMainAPIs.add(api)
    }

    fun registerExtractorAPI(api: ExtractorApi) {
        _registeredExtractorAPIs.add(api)
    }
}
