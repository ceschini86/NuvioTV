package com.lagradost.cloudstream3

abstract class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    abstract val type: TvType
    open val posterUrl: String? = null
    open val year: Int? = null
    open val plot: String? = null
    open val rating: Int? = null
    open val tags: List<String>? = null
    open val duration: Int? = null
    open val trailerUrl: String? = null
    open val recommendations: List<SearchResponse>? = null
    open val actors: List<ActorRole>? = null
    open val comingSoon: Boolean = false
    open val syncData: Map<String, String>? = null
    open val posterHeaders: Map<String, String>? = null
    open val backgroundPosterUrl: String? = null

    companion object {
        fun LoadResponse.addTrailer(url: String?) {
            // Stub - not used in our context
        }
    }
}

data class Episode(
    val data: String,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val rating: Int? = null,
    val description: String? = null,
    val date: Long? = null
)

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    val dataUrl: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailerUrl: String? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorRole>? = null,
    override val comingSoon: Boolean = false,
    override val syncData: Map<String, String>? = null,
    override val posterHeaders: Map<String, String>? = null,
    override val backgroundPosterUrl: String? = null
) : LoadResponse()

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    val episodes: List<Episode>,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailerUrl: String? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorRole>? = null,
    override val comingSoon: Boolean = false,
    override val syncData: Map<String, String>? = null,
    override val posterHeaders: Map<String, String>? = null,
    override val backgroundPosterUrl: String? = null,
    val showStatus: ShowStatus? = null,
    val seasonNames: List<SeasonData>? = null
) : LoadResponse()

data class AnimeLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Anime,
    val episodes: Map<String, List<Episode>> = emptyMap(),
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailerUrl: String? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorRole>? = null,
    override val comingSoon: Boolean = false,
    override val syncData: Map<String, String>? = null,
    override val posterHeaders: Map<String, String>? = null,
    override val backgroundPosterUrl: String? = null
) : LoadResponse()

data class LiveLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Live,
    val dataUrl: String,
    override val posterUrl: String? = null,
    override val year: Int? = null,
    override val plot: String? = null,
    override val rating: Int? = null,
    override val tags: List<String>? = null,
    override val duration: Int? = null,
    override val trailerUrl: String? = null,
    override val recommendations: List<SearchResponse>? = null,
    override val actors: List<ActorRole>? = null,
    override val comingSoon: Boolean = false,
    override val syncData: Map<String, String>? = null,
    override val posterHeaders: Map<String, String>? = null,
    override val backgroundPosterUrl: String? = null
) : LoadResponse()

enum class ShowStatus {
    Completed,
    Ongoing
}

data class SeasonData(
    val season: Int,
    val name: String? = null,
    val displaySeason: Int? = null
)

// Helper constructors used by extensions
fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    dataUrl: String,
    initializer: MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    val response = MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl
    )
    response.initializer()
    return response
}

fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    episodes: List<Episode>,
    initializer: TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    val response = TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        episodes = episodes
    )
    response.initializer()
    return response
}

fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    initializer: AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse {
    val response = AnimeLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    )
    response.initializer()
    return response
}

fun newEpisode(
    data: String,
    initializer: Episode.() -> Unit = {}
): Episode {
    val episode = Episode(data = data)
    episode.initializer()
    return episode
}
