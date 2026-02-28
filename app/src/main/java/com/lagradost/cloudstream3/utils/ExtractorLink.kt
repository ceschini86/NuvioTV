package com.lagradost.cloudstream3.utils

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH
}

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown,
    val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    val headers: Map<String, String> = emptyMap(),
    /** @deprecated Use [type] instead. Kept for backward compatibility with older extensions. */
    @Deprecated("Use type instead", replaceWith = ReplaceWith("type == ExtractorLinkType.M3U8"))
    val isM3u8: Boolean = type == ExtractorLinkType.M3U8,
    /** @deprecated Use [type] instead. */
    @Deprecated("Use type instead", replaceWith = ReplaceWith("type == ExtractorLinkType.DASH"))
    val isDash: Boolean = type == ExtractorLinkType.DASH
) {
    constructor(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        isM3u8: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        isDash: Boolean = false
    ) : this(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        },
        headers = headers
    )
}
