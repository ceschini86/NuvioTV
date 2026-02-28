package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink

abstract class MainAPI {
    abstract val name: String
    abstract val mainUrl: String
    open val lang: String = "en"
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val usesWebView: Boolean = false
    open val hasDownloadSupport: Boolean = true
    open val hasChromecastSupport: Boolean = false
    open val vpnStatus: VPNStatus = VPNStatus.None
    open val mainPage: List<MainPageRequest> = emptyList()
    open val requiresReferer: Boolean = false

    /**
     * Internal network interceptor set by the extension loader.
     * Extensions call [get], [post], [request] which delegate here.
     */
    @Transient
    var _networkInterceptor: ExternalNetworkInterface? = null

    open suspend fun search(query: String): List<SearchResponse>? = null

    open suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    open suspend fun load(url: String): LoadResponse? = null

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false

    open suspend fun getMainPage(page: Int = 1, request: MainPageRequest): HomePageResponse? = null

    // Network helper functions called by extensions
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        cacheTime: Int = 0,
        timeout: Long = 0L,
        interceptor: Any? = null,
        verify: Boolean = true
    ): NAppResponse {
        val allHeaders = buildMap {
            putAll(headers)
            if (referer != null) put("Referer", referer)
        }
        return _networkInterceptor?.get(url, allHeaders, params, cookies, timeout)
            ?: NAppResponse("", 0, emptyMap())
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        requestBody: Any? = null,
        cacheTime: Int = 0,
        timeout: Long = 0L,
        interceptor: Any? = null,
        verify: Boolean = true
    ): NAppResponse {
        val allHeaders = buildMap {
            putAll(headers)
            if (referer != null) put("Referer", referer)
        }
        return _networkInterceptor?.post(url, allHeaders, params, cookies, data, requestBody, timeout)
            ?: NAppResponse("", 0, emptyMap())
    }

    fun fixUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return "$mainUrl/$url"
    }
}

enum class VPNStatus {
    None,
    MightBeNeeded,
    Torrent
}

/**
 * Simplified network response returned by the compatibility layer.
 */
data class NAppResponse(
    val text: String,
    val code: Int,
    val headers: Map<String, String>
) {
    val url: String get() = "" // Not tracked in our simplified implementation

    /** Parse HTML via JSoup. */
    val document: org.jsoup.nodes.Document
        get() = org.jsoup.Jsoup.parse(text)

    /** Check if request was successful. */
    val isSuccessful: Boolean get() = code in 200..299

    override fun toString(): String = text
}

/**
 * Interface for the network layer injected into MainAPI instances.
 */
interface ExternalNetworkInterface {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        timeout: Long
    ): NAppResponse

    suspend fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
        requestBody: Any?,
        timeout: Long
    ): NAppResponse
}
