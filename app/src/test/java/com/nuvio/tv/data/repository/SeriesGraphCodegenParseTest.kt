package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Uses Moshi codegen adapters (no KotlinJsonAdapterFactory) like production Retrofit. */
class SeriesGraphCodegenParseTest {
    private val moshi = Moshi.Builder().build()
    private val type = Types.newParameterizedType(List::class.java, SeriesGraphSeasonRatingsDto::class.java)
    private val adapter = moshi.adapter<List<SeriesGraphSeasonRatingsDto>>(type)

    @Test
    fun codegenParsesIntegerCommunityAvg() {
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
}
