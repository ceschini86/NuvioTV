package com.nuvio.tv.data.ryot

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Logs a profile in to a Ryot server and keeps the session valid for the other Ryot classes.
 *
 * Login sends the user name and password to `loginUser` once and stores only the API key it returns.
 * It then finds or creates the app's "Generic Json" integration, named [INTEGRATION_NAME], whose
 * webhook takes scrobbles and watched marks by TMDB id.
 */
class RyotAuthRepository(
    private val client: RyotGraphqlClient,
    private val store: RyotAuthStore
) {
    suspend fun connect(serverUrl: String, userName: String, password: String) {
        val scope = store.scope()
        val baseUrl = normalizeRyotBaseUrl(serverUrl) ?: throw RyotAuthException(RyotAuthError.INVALID_URL)
        checkIsRyot(baseUrl)
        val apiKey = login(baseUrl, userName.trim(), password)
        store.checkScope(scope)
        val (userId, name) = userDetails(baseUrl, apiKey)
        val slug = try {
            ensureIntegration(baseUrl, apiKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw RyotAuthException(RyotAuthError.INTEGRATION_FAILED, error)
        }
        store.checkScope(scope)
        if (!store.authorize(RyotStoredAuth(baseUrl, apiKey, userId, name, slug), scope)) {
            throw CancellationException("Ryot profile changed during login")
        }
    }

    /** Ends the server session when it can and always forgets the login on this device. */
    suspend fun disconnect() {
        val authorization = store.authorization() ?: return
        runCatching {
            client.execute(authorization.auth.baseUrl, authorization.auth.apiKey, "mutation { logoutUser }")
        }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        store.clearAuth(authorization.scope)
    }

    /**
     * Runs [block] with the current login. A rejected session logs the profile out with
     * [RyotAuthError.SESSION_EXPIRED], so the settings screen asks for the password again.
     */
    suspend fun <T> withAuthorization(block: suspend (RyotAuthorization) -> T): T {
        val authorization = store.authorization() ?: throw RyotAuthException(RyotAuthError.SESSION_EXPIRED)
        try {
            return block(authorization)
        } catch (error: RyotApiException) {
            if (error.failure == RyotApiFailure.UNAUTHORIZED) {
                store.clearAuth(authorization.scope, RyotAuthError.SESSION_EXPIRED, authorization.auth.apiKey)
                throw RyotAuthException(RyotAuthError.SESSION_EXPIRED, error)
            }
            throw error
        }
    }

    private suspend fun checkIsRyot(baseUrl: String) {
        val data = try {
            client.execute(baseUrl, null, "query { coreDetails { version } }")
        } catch (error: RyotApiException) {
            throw RyotAuthException(
                if (error.failure == RyotApiFailure.NETWORK) RyotAuthError.UNREACHABLE else RyotAuthError.NOT_RYOT,
                error
            )
        }
        if (data.obj("coreDetails")?.string("version") == null) throw RyotAuthException(RyotAuthError.NOT_RYOT)
    }

    private suspend fun login(baseUrl: String, userName: String, password: String): String {
        val variables = buildJsonObject {
            putJsonObject("input") {
                putJsonObject("password") {
                    put("username", userName)
                    put("password", password)
                }
            }
        }
        val result = client.execute(
            baseUrl, null,
            """
            mutation Login(${'$'}input: AuthUserInput!) {
              loginUser(input: ${'$'}input) {
                __typename
                ... on ApiKeyResponse { apiKey }
                ... on LoginError { error }
              }
            }
            """.trimIndent(),
            variables
        ).obj("loginUser") ?: throw RyotAuthException(RyotAuthError.NOT_RYOT)
        return when (result.string("__typename")) {
            "ApiKeyResponse" -> result.string("apiKey") ?: throw RyotAuthException(RyotAuthError.NOT_RYOT)
            "StringIdObject" -> throw RyotAuthException(RyotAuthError.TWO_FACTOR_REQUIRED)
            else -> throw RyotAuthException(
                if (result.string("error") == "ACCOUNT_DISABLED") RyotAuthError.ACCOUNT_DISABLED
                else RyotAuthError.WRONG_CREDENTIALS
            )
        }
    }

    private suspend fun userDetails(baseUrl: String, apiKey: String): Pair<String, String> {
        val user = client.execute(
            baseUrl, apiKey,
            "query { userDetails { __typename ... on UserDetails { id name } } }"
        ).obj("userDetails")
        val id = user?.string("id") ?: throw RyotAuthException(RyotAuthError.WRONG_CREDENTIALS)
        return id to (user.string("name") ?: id)
    }

    private suspend fun ensureIntegration(baseUrl: String, apiKey: String): String {
        findIntegration(baseUrl, apiKey)?.let { return it }
        client.execute(
            baseUrl, apiKey,
            """
            mutation CreateIntegration(${'$'}input: CreateOrUpdateUserIntegrationInput!) {
              createOrUpdateUserIntegration(input: ${'$'}input)
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    put("name", INTEGRATION_NAME)
                    put("provider", "GENERIC_JSON")
                    // A scrobble below 2% is dropped; above 90% it counts as watched, as in the app.
                    put("minimumProgress", "2")
                    put("maximumProgress", "90")
                    putJsonObject("extraSettings") { put("disableOnContinuousErrors", false) }
                }
            }
        )
        return findIntegration(baseUrl, apiKey) ?: throw RyotAuthException(RyotAuthError.INTEGRATION_FAILED)
    }

    private suspend fun findIntegration(baseUrl: String, apiKey: String): String? {
        val integrations = client.execute(
            baseUrl, apiKey,
            "query { userIntegrations { id name provider isDisabled } }"
        )["userIntegrations"] as? JsonArray ?: return null
        return integrations.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull {
                it.string("provider") == "GENERIC_JSON" && it.string("name") == INTEGRATION_NAME &&
                    it.string("isDisabled") != "true"
            }
            ?.string("id")
    }

    companion object {
        const val INTEGRATION_NAME = "Nuvio TV"
    }
}

/**
 * Turns what the user typed into the server's base URL: adds https:// when no scheme is given and
 * drops a trailing slash or a pasted `/backend/graphql`. Returns null when it is not an http(s) URL.
 */
fun normalizeRyotBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    val path = url.encodedPath.trimEnd('/').removeSuffix("/backend/graphql").removeSuffix("/backend")
    return url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).query(null).fragment(null)
        .build().toString().trimEnd('/')
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
