@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH
}

/** Sentinel value indicating the type should be inferred. */
val INFER_TYPE: ExtractorLinkType? = null

open class ExtractorLink(
    open var source: String,
    open var name: String,
    open var url: String,
    open var referer: String = "",
    open var quality: Int = Qualities.Unknown.value,
    open var type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    open var headers: Map<String, String> = emptyMap(),
    open var extractorData: String? = null,
    @Deprecated("Use type instead") open var isM3u8: Boolean = type == ExtractorLinkType.M3U8,
    @Deprecated("Use type instead") open var isDash: Boolean = type == ExtractorLinkType.DASH
) {
    constructor(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        isM3u8: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null,
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
        headers = headers,
        extractorData = extractorData
    )
}

fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    referer: String = "",
    quality: Int = Qualities.Unknown.value,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    headers: Map<String, String> = emptyMap(),
    extractorData: String? = null
): ExtractorLink = ExtractorLink(
    source = source,
    name = name,
    url = url,
    referer = referer,
    quality = quality,
    type = type,
    headers = headers,
    extractorData = extractorData
)

