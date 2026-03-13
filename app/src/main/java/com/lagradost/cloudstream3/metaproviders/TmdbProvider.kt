package com.lagradost.cloudstream3.metaproviders

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Compatibility stub for CloudStream TmdbProvider.
 *
 * In real CloudStream, TmdbProvider queries TMDB for search/load and delegates
 * loadLinks() to subclasses. In NuvioTV, our ExternalExtensionRunner handles
 * the TMDB bridge and calls loadLinks() directly with TmdbLink JSON data.
 */
open class TmdbProvider : MainAPI() {
    override val name: String = "TMDB"
    override val mainUrl: String = "https://www.themoviedb.org"
    override val hasMainPage: Boolean = true

    open val includeAdult: Boolean = false
    open val useMetaLoadResponse: Boolean = false
    open val apiName: String = "TMDB"
    open val disableSeasonZero: Boolean = true

    // In our stub, search/load are no-ops since ExternalExtensionRunner
    // bypasses them for TmdbProvider extensions and calls loadLinks directly.
    override suspend fun search(query: String): List<SearchResponse>? = null
    override suspend fun load(url: String): LoadResponse? = null
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null
}

/**
 * Data class serialized as JSON in the `data` parameter of loadLinks() for TmdbProvider extensions.
 */
data class TmdbLink(
    @SerializedName("imdbID") val imdbID: String?,
    @SerializedName("tmdbID") val tmdbID: Int?,
    @SerializedName("episode") val episode: Int?,
    @SerializedName("season") val season: Int?,
    @SerializedName("movieName") val movieName: String? = null
)
