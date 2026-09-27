package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesGraphRatingsParseTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val type = Types.newParameterizedType(List::class.java, SeriesGraphSeasonRatingsDto::class.java)
    private val adapter = moshi.adapter<List<SeriesGraphSeasonRatingsDto>>(type)

    @Test
    fun parsesImdbRatingAndCommunityAvg() {
        val json = """
            [{"season_number":1,"episodes":[
              {"season_number":1,"episode_number":1,"imdb_rating":9.1,"community_avg":9,"name":"Pilot"},
              {"season_number":1,"episode_number":2,"imdb_rating":8.6,"community_avg":8.7,"name":"Cat"}
            ]}]
        """.trimIndent()
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(9.1, parsed!![0].episodes!![0].imdbRating!!, 0.0)
        assertEquals(9.0, parsed[0].episodes!![0].communityAverage!!, 0.0)
        assertEquals(8.6, parsed[0].episodes!![1].imdbRating!!, 0.0)
        assertEquals(8.7, parsed[0].episodes!![1].communityAverage!!, 0.0)
    }

    @Test
    fun repositoryMapperPrefersImdbRating() {
        val json = """
            [{"season_number":1,"episodes":[
              {"season_number":1,"episode_number":1,"imdb_rating":9.1,"community_avg":9},
              {"season_number":1,"episode_number":2,"imdb_rating":8.6,"community_avg":8.7},
              {"season_number":1,"episode_number":3,"community_avg":7.5}
            ]}]
        """.trimIndent()
        val parsed = adapter.fromJson(json)!!
        val map = buildMap {
            parsed.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val s = episode.seasonNumber ?: return@forEach
                    val e = episode.episodeNumber ?: return@forEach
                    val rating = seriesGraphEpisodeRatingValue(
                        imdbRating = episode.imdbRating,
                        communityAverage = episode.communityAverage
                    ) ?: return@forEach
                    put(s to e, rating)
                }
            }
        }
        assertEquals(3, map.size)
        assertEquals(9.1, map.getValue(1 to 1), 0.0)
        assertEquals(8.6, map.getValue(1 to 2), 0.0)
        assertEquals(7.5, map.getValue(1 to 3), 0.0)
        assertTrue(map.containsKey(1 to 1))
    }

    @Test
    fun ratingValuePrefersImdbThenCommunity() {
        assertEquals(9.1, seriesGraphEpisodeRatingValue(9.1, 9.0)!!, 0.0)
        assertEquals(8.7, seriesGraphEpisodeRatingValue(null, 8.7)!!, 0.0)
        assertNull(seriesGraphEpisodeRatingValue(0.0, null))
        assertNull(seriesGraphEpisodeRatingValue(null, null))
    }
}
