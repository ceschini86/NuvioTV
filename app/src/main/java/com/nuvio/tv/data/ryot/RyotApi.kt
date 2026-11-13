package com.nuvio.tv.data.ryot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Ryot media types the app tracks. Ryot also tracks books, games and so on; the app ignores them. */
enum class RyotLot { MOVIE, SHOW, ANIME }

/** Static facts about one Ryot metadata row: where it comes from and how to show it. */
data class RyotMetadata(
    val id: String,
    val lot: RyotLot,
    val source: String,
    val identifier: String,
    val title: String,
    val year: Int?,
    val poster: String?,
    val runtimeMinutes: Int?
)

/** One "seen" record: a watch of a movie or an episode, finished or in progress. */
data class RyotSeen(
    val id: String,
    val state: String,
    val progress: Double,
    val season: Int?,
    val episode: Int?,
    val animeEpisode: Int?,
    val finishedOn: String?,
    val lastUpdatedOn: String
) {
    val isCompleted: Boolean get() = state == "COMPLETED"
    val isInProgress: Boolean get() = state == "IN_PROGRESS"
}

/** The user's state for one metadata row. */
data class RyotUserMetadata(
    val metadataId: String,
    val history: List<RyotSeen>,
    val inProgress: RyotSeen?,
    val nextSeason: Int?,
    val nextEpisode: Int?,
    val collections: Set<String>
)

data class RyotCollection(val id: String, val name: String, val count: Int, val isDefault: Boolean, val creatorId: String)

data class RyotIdPage(val ids: List<String>, val nextPage: Int?, val totalItems: Int)

/**
 * The GraphQL operations the app needs, with typed results.
 *
 * Detail queries go out in batches of [BATCH_SIZE] aliased fields (`m0: metadataDetails(...) m1: ...`),
 * so a library of 400 titles takes about 8 requests instead of 400.
 */
class RyotApi(private val client: RyotGraphqlClient) {

    suspend fun listMetadataIds(auth: RyotStoredAuth, lot: RyotLot, page: Int, take: Int, sortBy: String = "LAST_UPDATED"): RyotIdPage {
        val data = client.execute(
            auth.baseUrl, auth.apiKey,
            """
            query List(${'$'}input: UserMetadataListInput!) {
              userMetadataList(input: ${'$'}input) { response { items details { totalItems nextPage } } }
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    put("lot", lot.name)
                    putJsonObject("search") {
                        put("page", page)
                        put("take", take)
                    }
                    putJsonObject("sort") {
                        put("by", sortBy)
                        put("order", "DESC")
                    }
                }
            }
        )
        val response = data.obj("userMetadataList")?.obj("response") ?: return RyotIdPage(emptyList(), null, 0)
        val details = response.obj("details")
        return RyotIdPage(
            ids = response.array("items").mapNotNull { (it as? JsonPrimitive)?.content },
            nextPage = (details?.get("nextPage") as? JsonPrimitive)?.intOrNull,
            totalItems = (details?.get("totalItems") as? JsonPrimitive)?.intOrNull ?: 0
        )
    }

    /** Every metadata id of [lot] in the user's library, most recently updated first. */
    suspend fun allMetadataIds(auth: RyotStoredAuth, lot: RyotLot): List<String> {
        val ids = mutableListOf<String>()
        var page: Int? = 1
        var guard = 0
        while (page != null && guard++ < MAX_PAGES) {
            val result = listMetadataIds(auth, lot, page, PAGE_SIZE)
            ids += result.ids
            page = result.nextPage
        }
        return ids.distinct()
    }

    suspend fun metadataDetails(auth: RyotStoredAuth, ids: Collection<String>): Map<String, RyotMetadata> =
        batched(auth, ids, "metadataDetails", METADATA_FIELDS) { id, response ->
            parseMetadata(id, response)
        }

    suspend fun userMetadataDetails(auth: RyotStoredAuth, ids: Collection<String>): Map<String, RyotUserMetadata> =
        batched(auth, ids, "userMetadataDetails", USER_METADATA_FIELDS) { id, response ->
            parseUserMetadata(id, response)
        }

    suspend fun deleteSeen(auth: RyotStoredAuth, seenId: String) {
        client.execute(
            auth.baseUrl, auth.apiKey,
            "mutation Delete(${'$'}id: String!) { deleteSeenItem(seenId: ${'$'}id) { id } }",
            buildJsonObject { put("id", seenId) }
        )
    }

    suspend fun collections(auth: RyotStoredAuth): List<RyotCollection> =
        client.execute(
            auth.baseUrl, auth.apiKey,
            "query { userCollectionsList { response { id name count isDefault creator { id } } } }"
        ).obj("userCollectionsList")?.array("response").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            RyotCollection(
                id = item.string("id") ?: return@mapNotNull null,
                name = item.string("name") ?: return@mapNotNull null,
                count = (item["count"] as? JsonPrimitive)?.intOrNull ?: 0,
                isDefault = item.string("isDefault") == "true",
                creatorId = item.obj("creator")?.string("id").orEmpty()
            )
        }

    /** Metadata ids in a collection, most recently added first. */
    suspend fun collectionMetadataIds(auth: RyotStoredAuth, collectionId: String): List<String> {
        val ids = mutableListOf<String>()
        var page: Int? = 1
        var guard = 0
        while (page != null && guard++ < MAX_PAGES) {
            val data = client.execute(
                auth.baseUrl, auth.apiKey,
                """
                query Contents(${'$'}input: CollectionContentsInput!) {
                  collectionContents(input: ${'$'}input) {
                    response { results { items { entityId entityLot } details { nextPage } } }
                  }
                }
                """.trimIndent(),
                buildJsonObject {
                    putJsonObject("input") {
                        put("collectionId", collectionId)
                        putJsonObject("search") {
                            put("page", page)
                            put("take", PAGE_SIZE)
                        }
                        putJsonObject("sort") {
                            put("by", "LAST_UPDATED_ON")
                            put("order", "DESC")
                        }
                    }
                }
            )
            val results = data.obj("collectionContents")?.obj("response")?.obj("results") ?: break
            results.array("items").forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                if (item.string("entityLot") == "METADATA") item.string("entityId")?.let(ids::add)
            }
            page = (results.obj("details")?.get("nextPage") as? JsonPrimitive)?.intOrNull
        }
        return ids
    }

    suspend fun changeCollection(auth: RyotStoredAuth, collectionName: String, metadataIds: Collection<String>, add: Boolean) {
        if (metadataIds.isEmpty()) return
        val field = if (add) "deployAddEntitiesToCollectionJob" else "deployRemoveEntitiesFromCollectionJob"
        client.execute(
            auth.baseUrl, auth.apiKey,
            "mutation Change(${'$'}input: ChangeCollectionToEntitiesInput!) { $field(input: ${'$'}input) }",
            buildJsonObject {
                putJsonObject("input") {
                    put("creatorUserId", auth.userId)
                    put("collectionName", collectionName)
                    put("entities", kotlinx.serialization.json.JsonArray(metadataIds.map { id ->
                        buildJsonObject {
                            put("entityId", id)
                            put("entityLot", "METADATA")
                        }
                    }))
                }
            }
        )
    }

    private suspend fun <T> batched(
        auth: RyotStoredAuth,
        ids: Collection<String>,
        field: String,
        selection: String,
        parse: (String, JsonObject) -> T?
    ): Map<String, T> {
        val result = LinkedHashMap<String, T>()
        ids.distinct().chunked(BATCH_SIZE).forEach { chunk ->
            val document = buildString {
                append("query Batch(")
                chunk.indices.joinTo(this) { "${'$'}i$it: String!" }
                append(") {\n")
                chunk.indices.forEach { index ->
                    append("  m$index: $field(metadataId: ${'$'}i$index) { response { $selection } }\n")
                }
                append("}")
            }
            val variables = buildJsonObject { chunk.forEachIndexed { index, id -> put("i$index", id) } }
            val data = client.execute(auth.baseUrl, auth.apiKey, document, variables, allowPartialErrors = true)
            chunk.forEachIndexed { index, id ->
                val response = data.obj("m$index")?.obj("response") ?: return@forEachIndexed
                parse(id, response)?.let { result[id] = it }
            }
        }
        return result
    }

    companion object {
        const val BATCH_SIZE = 50
        const val PAGE_SIZE = 100
        private const val MAX_PAGES = 100

        private const val SEEN_FIELDS =
            "id state progress finishedOn lastUpdatedOn showExtraInformation { season episode } animeExtraInformation { episode }"

        private const val METADATA_FIELDS =
            "id lot source identifier title publishYear assets { remoteImages } movieSpecifics { runtime } showSpecifics { runtime }"

        private const val USER_METADATA_FIELDS =
            "history { $SEEN_FIELDS } inProgress { $SEEN_FIELDS } nextEntry { season episode } " +
                "collections { details { collectionName } }"

        internal fun parseMetadata(id: String, json: JsonObject): RyotMetadata? {
            val lot = runCatching { RyotLot.valueOf(json.string("lot") ?: return null) }.getOrNull() ?: return null
            return RyotMetadata(
                id = json.string("id") ?: id,
                lot = lot,
                source = json.string("source") ?: return null,
                identifier = json.string("identifier") ?: return null,
                title = json.string("title").orEmpty(),
                year = (json["publishYear"] as? JsonPrimitive)?.intOrNull,
                poster = json.obj("assets")?.array("remoteImages")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content },
                runtimeMinutes = (json.obj("movieSpecifics")?.get("runtime") as? JsonPrimitive)?.intOrNull
                    ?: (json.obj("showSpecifics")?.get("runtime") as? JsonPrimitive)?.intOrNull
            )
        }

        internal fun parseSeen(json: JsonObject): RyotSeen? = RyotSeen(
            id = json.string("id") ?: return null,
            state = json.string("state") ?: return null,
            progress = (json["progress"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: 0.0,
            season = (json.obj("showExtraInformation")?.get("season") as? JsonPrimitive)?.intOrNull,
            episode = (json.obj("showExtraInformation")?.get("episode") as? JsonPrimitive)?.intOrNull,
            animeEpisode = (json.obj("animeExtraInformation")?.get("episode") as? JsonPrimitive)?.intOrNull,
            finishedOn = json.string("finishedOn"),
            lastUpdatedOn = json.string("lastUpdatedOn").orEmpty()
        )

        internal fun parseUserMetadata(id: String, json: JsonObject): RyotUserMetadata {
            val next = json.obj("nextEntry")
            return RyotUserMetadata(
                metadataId = id,
                history = json.array("history").mapNotNull { (it as? JsonObject)?.let(::parseSeen) },
                inProgress = json.obj("inProgress")?.let(::parseSeen),
                nextSeason = (next?.get("season") as? JsonPrimitive)?.intOrNull,
                nextEpisode = (next?.get("episode") as? JsonPrimitive)?.intOrNull,
                collections = json.array("collections").mapNotNullTo(linkedSetOf()) { element ->
                    (element as? JsonObject)?.obj("details")?.string("collectionName")
                }
            )
        }
    }
}
