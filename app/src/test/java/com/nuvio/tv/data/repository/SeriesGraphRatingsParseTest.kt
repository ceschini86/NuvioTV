package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesGraphRatingsParseTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val type = Types.newParameterizedType(List::class.java, SeriesGraphSeasonRatingsDto::class.java)
    private val adapter = moshi.adapter<List<SeriesGraphSeasonRatingsDto>>(type)

    @Test
    fun parsesIntegerAndFloatCommunityAvg() {
        val json = """
            [{"season_number":1,"episodes":[
              {"season_number":1,"episode_number":1,"community_avg":9,"name":"Pilot"},
              {"season_number":1,"episode_number":2,"community_avg":8.7,"name":"Cat"}
            ]}]
        """.trimIndent()
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(9.0, parsed!![0].episodes!![0].communityAverage!!, 0.0)
        assertEquals(8.7, parsed[0].episodes!![1].communityAverage!!, 0.0)
    }

    @Test
    fun repositoryMapperKeepsBoth() {
        val json = """
            [{"season_number":1,"episodes":[
              {"season_number":1,"episode_number":1,"community_avg":9},
              {"season_number":1,"episode_number":2,"community_avg":8.7}
            ]}]
        """.trimIndent()
        val parsed = adapter.fromJson(json)!!
        val map = buildMap {
            parsed.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val s = episode.seasonNumber ?: return@forEach
                    val e = episode.episodeNumber ?: return@forEach
                    val avg = episode.communityAverage ?: return@forEach
                    put(s to e, avg)
                }
            }
        }
        assertEquals(2, map.size)
        assertTrue(map.containsKey(1 to 1))
        assertTrue(map.containsKey(1 to 2))
    }
}
