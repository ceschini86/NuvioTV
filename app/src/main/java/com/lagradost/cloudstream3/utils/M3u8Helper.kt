@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

object M3u8Helper {
    suspend fun generateM3u8(
        source: String,
        streamUrl: String,
        referer: String,
        quality: Int = Qualities.Unknown.value,
        headers: Map<String, String> = emptyMap(),
        name: String = source
    ): List<ExtractorLink> {
        return listOf(
            ExtractorLink(
                source = source,
                name = name,
                url = streamUrl,
                referer = referer,
                quality = quality,
                type = ExtractorLinkType.M3U8,
                headers = headers
            )
        )
    }
}
