package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    abstract suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    )
}

/**
 * Global function called by extensions to resolve a URL through registered extractors.
 * The actual implementation is provided by [ExternalExtractorRegistry].
 */
var loadExtractor: suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Unit = { _, _, _, _ -> }
