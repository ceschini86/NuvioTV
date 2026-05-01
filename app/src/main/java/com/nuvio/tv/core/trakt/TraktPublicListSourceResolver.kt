package com.nuvio.tv.core.trakt

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktProminentListDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.normalizeContentId
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktListSort
import com.nuvio.tv.domain.model.TraktSortHow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TraktPublicListImportMetadata(
    val title: String? = null,
    val coverImageUrl: String? = null,
    val traktListId: Long? = null
)

data class TraktPublicListSearchResult(
    val traktListId: Long,
    val title: String,
    val subtitle: String,
    val coverImageUrl: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null
)

@Singleton
class TraktPublicListSourceResolver @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    fun resolve(source: TraktCollectionSource, page: Int = 1): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val type = source.mediaType.toTraktType()
                val sortBy = TraktListSort.normalize(source.sortBy)
                val sortHow = TraktSortHow.normalize(source.sortHow)
                trace(
                    "items request listId=${source.traktListId} type=$type page=$page limit=$PAGE_LIMIT sortBy=$sortBy sortHow=$sortHow title=${source.title}"
                )
                val response = publicRequest {
                    traktApi.getPublicListItems(
                        id = source.traktListId.toString(),
                        type = type,
                        page = page,
                        limit = PAGE_LIMIT,
                        sortBy = sortBy,
                        sortHow = sortHow
                    )
                }
                val pageCountHeader = response.headers()["X-Pagination-Page-Count"]
                val itemCountHeader = response.headers()["X-Pagination-Item-Count"]
                trace(
                    "items response listId=${source.traktListId} type=$type status=${response.code()} success=${response.isSuccessful} pageCount=$pageCountHeader itemCount=$itemCountHeader"
                )
                if (!response.isSuccessful) error(errorMessageFor(response.code(), "Could not load Trakt list"))
                val rawItems = response.body().orEmpty()
                trace(
                    "items raw listId=${source.traktListId} type=$type count=${rawItems.size} sample=${rawItems.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary(source.mediaType) }}"
                )
                val items = rawItems
                    .mapNotNull { it.toPreview(source.mediaType) }
                    .distinctBy { "${it.apiType}:${it.id}" }
                trace(
                    "items mapped listId=${source.traktListId} type=$type mapped=${items.size} withPoster=${items.count { !it.poster.isNullOrBlank() }} withBackground=${items.count { !it.background.isNullOrBlank() }} sample=${items.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary() }}"
                )
                val pageCount = pageCountHeader?.toIntOrNull() ?: page
                row(
                    source = source.copy(
                        sortBy = sortBy,
                        sortHow = sortHow
                    ),
                    page = page,
                    hasMore = page < pageCount && items.isNotEmpty(),
                    items = items
                )
            }
        }
        result.fold(
            onSuccess = { emit(NetworkResult.Success(it)) },
            onFailure = { emit(NetworkResult.Error(it.message ?: "Could not load Trakt list")) }
        )
    }

    suspend fun listImportMetadata(input: String): TraktPublicListImportMetadata = withContext(Dispatchers.IO) {
        val idPath = parseTraktListPath(input) ?: error("Enter a valid Trakt list ID or URL")
        trace("metadata request input=$input resolvedPath=$idPath extended=full,images")
        val response = publicRequest { traktApi.getPublicList(id = idPath) }
        trace("metadata response input=$input resolvedPath=$idPath status=${response.code()} success=${response.isSuccessful}")
        if (!response.isSuccessful) error(errorMessageFor(response.code(), "Trakt list not found"))
        val list = response.body() ?: error("Trakt list not found")
        trace("metadata body input=$input summary=${list.debugSummary()}")
        val id = list.ids?.trakt ?: idPath.toLongOrNull() ?: error("Trakt list did not include a numeric ID")
        TraktPublicListImportMetadata(
            title = list.name?.takeIf { it.isNotBlank() },
            coverImageUrl = list.images?.posters.firstTraktImageUrl(),
            traktListId = id
        )
    }

    suspend fun searchPublicLists(query: String): List<TraktPublicListSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        trace("search request query=${query.trim()} extended=full,images page=1 limit=20")
        val response = publicRequest {
            traktApi.searchLists(
                query = query.trim()
            )
        }
        trace("search response query=${query.trim()} status=${response.code()} success=${response.isSuccessful} count=${response.body()?.size ?: 0}")
        if (!response.isSuccessful) error(errorMessageFor(response.code(), "Could not search Trakt lists"))
        val body = response.body().orEmpty()
        trace("search raw query=${query.trim()} sample=${body.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary() }}")
        body.mapNotNull { it.toPublicListResult() }.also { results ->
            trace("search mapped query=${query.trim()} mapped=${results.size} sample=${results.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary() }}")
        }
    }

    suspend fun trendingPublicLists(): List<TraktPublicListSearchResult> = loadProminentLists(name = "trending") {
        traktApi.getTrendingLists()
    }

    suspend fun popularPublicLists(): List<TraktPublicListSearchResult> = loadProminentLists(name = "popular") {
        traktApi.getPopularLists()
    }

    fun parseTraktListId(input: String): Long? {
        return parseTraktListPath(input)?.toLongOrNull()
    }

    private suspend fun loadProminentLists(
        name: String = "prominent",
        call: suspend () -> Response<List<TraktProminentListDto>>
    ): List<TraktPublicListSearchResult> = withContext(Dispatchers.IO) {
        trace("$name request extended=full,images page=1 limit=20")
        val response = publicRequest(call)
        trace("$name response status=${response.code()} success=${response.isSuccessful} count=${response.body()?.size ?: 0}")
        if (!response.isSuccessful) error(errorMessageFor(response.code(), "Could not load Trakt lists"))
        val body = response.body().orEmpty()
        trace("$name raw sample=${body.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary() }}")
        body.mapNotNull { item ->
            item.list?.toPublicListResult(
                likeCount = item.likeCount,
                commentCount = item.commentCount
            )
        }.also { results ->
            trace("$name mapped=${results.size} sample=${results.take(LOG_SAMPLE_SIZE).joinToString(" || ") { it.debugSummary() }}")
        }
    }

    private suspend fun <T> publicRequest(call: suspend () -> Response<T>): Response<T> {
        return traktAuthService.executePublicRequest(call) ?: error("Trakt request failed")
    }

    private fun row(source: TraktCollectionSource, page: Int, hasMore: Boolean, items: List<MetaPreview>): CatalogRow {
        val rawType = source.mediaType.toCollectionRawType()
        return CatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "",
            catalogId = source.key(),
            catalogName = source.title,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            items = items,
            isLoading = false,
            hasMore = hasMore,
            currentPage = page,
            supportsSkip = hasMore,
            skipStep = PAGE_LIMIT
        )
    }

    private fun TraktListItemDto.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        return when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> movie?.toPreview()
            TmdbCollectionMediaType.TV -> show?.toPreview()
        }
    }

    private fun TraktMovieDto.toPreview(): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val fallback = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            !ids?.slug.isNullOrBlank() -> "movie:${ids.slug}"
            else -> null
        }
        val contentId = normalizeContentId(ids, fallback)
        if (contentId.isBlank()) return null
        return MetaPreview(
            id = contentId,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = title,
            poster = images.traktBestPosterUrl(),
            posterShape = PosterShape.POSTER,
            background = images.traktBestBackdropUrl(),
            logo = images.traktBestLogoUrl(),
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: released?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            runtime = runtime?.takeIf { it > 0 }?.let { "$it min" },
            status = status,
            ageRating = certification,
            language = languages?.firstOrNull(),
            released = released,
            country = country,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() },
            landscapePoster = images.traktBestBackdropUrl(),
            rawPosterUrl = images.traktPosterUrl()
        )
    }

    private fun TraktShowDto.toPreview(): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val fallback = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            !ids?.slug.isNullOrBlank() -> "series:${ids.slug}"
            else -> null
        }
        val contentId = normalizeContentId(ids, fallback)
        if (contentId.isBlank()) return null
        return MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = "series",
            name = title,
            poster = images.traktBestPosterUrl(),
            posterShape = PosterShape.POSTER,
            background = images.traktBestBackdropUrl(),
            logo = images.traktBestLogoUrl(),
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: firstAired?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            runtime = runtime?.takeIf { it > 0 }?.let { "$it min" },
            status = status,
            ageRating = certification,
            language = languages?.firstOrNull(),
            released = firstAired,
            country = country,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() },
            landscapePoster = images.traktBestBackdropUrl(),
            rawPosterUrl = images.traktPosterUrl()
        )
    }

    private fun TraktSearchResultDto.toPublicListResult(): TraktPublicListSearchResult? {
        if (!type.equals("list", ignoreCase = true)) return null
        return list?.toPublicListResult()
    }

    private fun TraktListSummaryDto.toPublicListResult(
        likeCount: Int? = null,
        commentCount: Int? = null
    ): TraktPublicListSearchResult? {
        val id = ids?.trakt ?: return null
        val listTitle = name?.takeIf { it.isNotBlank() } ?: "Trakt List $id"
        val owner = user?.username?.takeIf { it.isNotBlank() }
        val stats = buildList {
            itemCount?.let { add("$it items") }
            (likeCount ?: likes)?.let { add("$it likes") }
            (commentCount ?: this@toPublicListResult.commentCount)?.let { add("$it comments") }
        }
        val subtitle = (listOfNotNull(owner) + stats).joinToString(" • ").ifBlank { "Trakt public list" }
        return TraktPublicListSearchResult(
            traktListId = id,
            title = listTitle,
            subtitle = subtitle,
            coverImageUrl = images?.posters.firstTraktImageUrl(),
            sortBy = sortBy,
            sortHow = sortHow
        )
    }

    private fun parseTraktListPath(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        trimmed.toLongOrNull()?.let { return it.toString() }
        Regex("""[?&]id=([^&#/]+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        Regex("""trakt\.tv/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        Regex("""trakt\.tv/users/[^/]+/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return trimmed.takeIf { it.matches(Regex("""[A-Za-z0-9_-]+""")) }
    }

    private fun TmdbCollectionMediaType.toTraktType(): String {
        return when (this) {
            TmdbCollectionMediaType.MOVIE -> "movie"
            TmdbCollectionMediaType.TV -> "show"
        }
    }

    private fun TmdbCollectionMediaType.toCollectionRawType(): String {
        return when (this) {
            TmdbCollectionMediaType.MOVIE -> "movie"
            TmdbCollectionMediaType.TV -> "series"
        }
    }

    private fun TraktCollectionSource.key(): String {
        return listOf(
            "trakt",
            "list",
            traktListId.toString(),
            mediaType.value,
            sortBy.lowercase(Locale.US),
            sortHow.lowercase(Locale.US)
        ).joinToString("_")
    }

    private fun errorMessageFor(code: Int, fallback: String): String {
        return when (code) {
            401, 403 -> "Trakt list not found or not public"
            404 -> "Trakt list not found or not public"
            429 -> "Trakt rate limit reached"
            else -> "$fallback ($code)"
        }
    }

    private fun trace(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun TraktListItemDto.debugSummary(mediaType: TmdbCollectionMediaType): String {
        return when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> movie?.debugSummary(rank = rank, listedAt = listedAt) ?: "rank=$rank type=$type movie=null"
            TmdbCollectionMediaType.TV -> show?.debugSummary(rank = rank, listedAt = listedAt) ?: "rank=$rank type=$type show=null"
        }
    }

    private fun TraktMovieDto.debugSummary(rank: Int? = null, listedAt: String? = null): String {
        return "rank=$rank listedAt=$listedAt title=${title.orEmpty()} ids=${ids.debugSummary()} images=${images.debugSummary()}"
    }

    private fun TraktShowDto.debugSummary(rank: Int? = null, listedAt: String? = null): String {
        return "rank=$rank listedAt=$listedAt title=${title.orEmpty()} ids=${ids.debugSummary()} images=${images.debugSummary()}"
    }

    private fun TraktImagesDto?.debugSummary(): String {
        if (this == null) return "null"
        return "poster=${poster.debugSummary()} fanart=${fanart.debugSummary()} logo=${logo.debugSummary()} thumb=${thumb.debugSummary()}"
    }

    private fun List<String>?.debugSummary(): String {
        if (this == null) return "null"
        return "count=$size first=${firstOrNull().orEmpty()}"
    }

    private fun com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto?.debugSummary(): String {
        if (this == null) return "null"
        return "imdb=${imdb.orEmpty()} tmdb=${tmdb ?: ""} trakt=${trakt ?: ""} slug=${slug.orEmpty()}"
    }

    private fun TraktListSummaryDto.debugSummary(): String {
        return "id=${ids?.trakt ?: ""} slug=${ids?.slug.orEmpty()} name=${name.orEmpty()} user=${user?.username.orEmpty()} items=${itemCount ?: ""} likes=${likes ?: ""} comments=${commentCount ?: ""} images=${images?.posters.debugSummary()}"
    }

    private fun TraktSearchResultDto.debugSummary(): String {
        return "type=${type.orEmpty()} score=${score ?: ""} list=${list?.debugSummary() ?: "null"}"
    }

    private fun TraktProminentListDto.debugSummary(): String {
        return "likes=${likeCount ?: ""} comments=${commentCount ?: ""} list=${list?.debugSummary() ?: "null"}"
    }

    private fun TraktPublicListSearchResult.debugSummary(): String {
        return "id=$traktListId title=$title subtitle=$subtitle cover=${coverImageUrl.orEmpty()} sortBy=${sortBy.orEmpty()} sortHow=${sortHow.orEmpty()}"
    }

    private fun MetaPreview.debugSummary(): String {
        return "id=$id apiType=$apiType name=$name poster=${poster.orEmpty()} background=${background.orEmpty()} logo=${logo.orEmpty()}"
    }

    companion object {
        private const val TAG = "TraktPublicLists"
        private const val LOG_SAMPLE_SIZE = 5
        const val PAGE_LIMIT = 50
    }
}
