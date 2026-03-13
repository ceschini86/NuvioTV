@file:Suppress("unused")

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
 * The actual implementation is provided by ExternalExtractorRegistry.
 */
var loadExtractor: suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Unit = { _, _, _, _ -> }

/** Overload used by some extensions (without referer). */
suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) = loadExtractor(url, null, subtitleCallback, callback)

fun httpsify(url: String): String {
    return if (url.startsWith("http://")) url.replaceFirst("http://", "https://")
    else url
}

fun getAndUnpack(response: String): String {
    // Basic JavaScript eval unpacker stub
    // Many extensions use this for deobfuscation but it requires full JS eval
    return response
}
