package com.lagradost.cloudstream3

enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    OVA,
    Cartoon,
    Documentary,
    Live,
    NSFW,
    Others,
    AsianDrama,
    Torrent,
    Music,
    AudioBook;

    companion object {
        /** Map external TvType to NuvioTV content type string ("movie" or "tv"). */
        fun toNuvioType(tvType: TvType): String = when (tvType) {
            Movie, AnimeMovie, Documentary, Torrent -> "movie"
            else -> "tv"
        }

        fun fromString(value: String): TvType? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}
