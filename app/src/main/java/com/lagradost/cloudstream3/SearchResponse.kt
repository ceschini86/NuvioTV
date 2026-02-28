package com.lagradost.cloudstream3

abstract class SearchResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    open val type: TvType? = null
    open val posterUrl: String? = null
    open val year: Int? = null
    open val id: Int? = null
    open val quality: Int? = null
    open val posterHeaders: Map<String, String>? = null
}

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val id: Int? = null,
    override val quality: Int? = null,
    override val posterHeaders: Map<String, String>? = null
) : SearchResponse()

data class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val id: Int? = null,
    override val quality: Int? = null,
    override val posterHeaders: Map<String, String>? = null
) : SearchResponse()

data class AnimeSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Anime,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val id: Int? = null,
    override val quality: Int? = null,
    override val posterHeaders: Map<String, String>? = null,
    val dubStatus: Set<String>? = null
) : SearchResponse()

data class LiveSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Live,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val id: Int? = null,
    override val quality: Int? = null,
    override val posterHeaders: Map<String, String>? = null
) : SearchResponse()

// Helper constructors used by extensions
fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true
): MovieSearchResponse = MovieSearchResponse(
    name = name,
    url = if (fix) fixUrl(url) else url,
    apiName = this.name,
    type = type
)

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true
): TvSeriesSearchResponse = TvSeriesSearchResponse(
    name = name,
    url = if (fix) fixUrl(url) else url,
    apiName = this.name,
    type = type
)

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true
): AnimeSearchResponse = AnimeSearchResponse(
    name = name,
    url = if (fix) fixUrl(url) else url,
    apiName = this.name,
    type = type
)
