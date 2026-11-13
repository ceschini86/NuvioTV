package com.nuvio.tv.data.ryot

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RyotHttpResponse(val code: Int, val body: String)

/** The HTTP layer, behind an interface so tests can answer without a server. */
interface RyotHttp {
    suspend fun postJson(url: String, body: String, bearer: String?): RyotHttpResponse
}

class OkHttpRyot(private val client: OkHttpClient) : RyotHttp {
    override suspend fun postJson(url: String, body: String, bearer: String?): RyotHttpResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                RyotHttpResponse(response.code, response.body?.string().orEmpty())
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

enum class RyotApiFailure { NETWORK, HTTP, UNAUTHORIZED, GRAPHQL, INVALID_RESPONSE }

class RyotApiException(
    val failure: RyotApiFailure,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Sends GraphQL documents to `<baseUrl>/backend/graphql` and webhook payloads to `<baseUrl>/_i/<slug>`.
 *
 * Ryot answers an unknown or expired session with a GraphQL error whose message is NO_USER_ID,
 * NO_SESSION_ID or SESSION_EXPIRED; those become [RyotApiFailure.UNAUTHORIZED].
 */
class RyotGraphqlClient(private val http: RyotHttp) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun execute(
        baseUrl: String,
        apiKey: String?,
        document: String,
        variables: JsonObject = JsonObject(emptyMap()),
        allowPartialErrors: Boolean = false
    ): JsonObject {
        val body = buildJsonObject {
            put("query", document)
            put("variables", variables)
        }.toString()
        val response = try {
            http.postJson("${baseUrl.trimEnd('/')}/backend/graphql", body, apiKey)
        } catch (error: IOException) {
            throw RyotApiException(RyotApiFailure.NETWORK, error.message ?: "network", cause = error)
        }
        if (response.code == 401 || response.code == 403) {
            throw RyotApiException(RyotApiFailure.UNAUTHORIZED, "HTTP ${response.code}", response.code)
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw RyotApiException(
                if (response.code in 200..299) RyotApiFailure.INVALID_RESPONSE else RyotApiFailure.HTTP,
                "HTTP ${response.code}",
                response.code
            )
        val errors = (root["errors"] as? JsonArray).orEmpty()
        if (errors.isNotEmpty()) {
            val messages = errors.mapNotNull { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull }
            val unauthorized = messages.any { it in UNAUTHORIZED_MESSAGES }
            // In an aliased batch, a missing record fails only its own alias; keep the others.
            val partialData = root["data"] as? JsonObject
            if (allowPartialErrors && !unauthorized && partialData != null) return partialData
            throw RyotApiException(
                if (unauthorized) RyotApiFailure.UNAUTHORIZED else RyotApiFailure.GRAPHQL,
                messages.joinToString("; ").ifBlank { "GraphQL error" },
                response.code
            )
        }
        if (response.code !in 200..299) {
            throw RyotApiException(RyotApiFailure.HTTP, "HTTP ${response.code}", response.code)
        }
        return when (val data = root["data"]) {
            is JsonObject -> data
            null, JsonNull -> throw RyotApiException(RyotApiFailure.INVALID_RESPONSE, "no data", response.code)
            else -> throw RyotApiException(RyotApiFailure.INVALID_RESPONSE, "data is not an object", response.code)
        }
    }

    /** Posts [payload] to the integration webhook. Ryot queues it and answers 202 before processing. */
    suspend fun postWebhook(baseUrl: String, slug: String, payload: JsonElement) {
        val response = try {
            http.postJson("${baseUrl.trimEnd('/')}/_i/$slug", payload.toString(), null)
        } catch (error: IOException) {
            throw RyotApiException(RyotApiFailure.NETWORK, error.message ?: "network", cause = error)
        }
        if (response.code !in 200..299) {
            throw RyotApiException(RyotApiFailure.HTTP, "webhook HTTP ${response.code}", response.code)
        }
    }

    private companion object {
        val UNAUTHORIZED_MESSAGES = setOf("NO_USER_ID", "NO_SESSION_ID", "SESSION_EXPIRED")
    }
}
