package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage
    ) : this(apiClient, configuration, storage, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<SimklAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        try {
            startLegacyPinAuth(authScope)
        } catch (error: SimklApiException) {
            if (error.errorCode == "unauthorized_client") {
                startOAuth2DeviceAuth(authScope)
            } else {
                throw SimklAuthException(SimklAuthError.NETWORK, error)
            }
        }
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        if (!USER_CODE_PATTERN.matches(session.userCode)) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val deviceCode = session.deviceCode?.trim()?.takeIf(String::isNotBlank)
        if (deviceCode != null) {
            return@withLock pollOAuth2Device(session, deviceCode, authScope)
        }
        pollLegacyPin(session, authScope)
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    fun disconnect() = storage.clearAuth()

    private suspend fun startLegacyPinAuth(authScope: SimklAuthScope): SimklPinSession {
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER
            )
        )
        val payload = decodePinResponse(response.body)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (payload.verificationUri ?: payload.verificationUrl)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        if (payload.result != "OK" || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        return persistPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresIn = expiresIn,
            interval = interval,
            deviceCode = null,
            authScope = authScope
        )
    }

    private suspend fun startOAuth2DeviceAuth(authScope: SimklAuthScope): SimklPinSession {
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/oauth2/device",
                body = json.encodeToString(SimklOAuthDeviceAuthorizationRequest(scope = OAUTH2_MEDIA_SCOPES)),
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER
            )
        )
        val payload = decodePinResponse(response.body)
        val deviceCode = payload.deviceCode?.trim()?.takeIf { it.isNotBlank() && it != LEGACY_DEVICE_CODE_PLACEHOLDER }
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (
            payload.verificationUri
                ?: payload.verificationUrl
                ?: payload.verificationUriComplete
            )
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        if (deviceCode == null || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        return persistPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresIn = expiresIn,
            interval = interval,
            deviceCode = deviceCode,
            authScope = authScope
        )
    }

    private fun persistPinSession(
        userCode: String,
        verificationUri: String,
        expiresIn: Long,
        interval: Int,
        deviceCode: String?,
        authScope: SimklAuthScope
    ): SimklPinSession {
        val session = SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval,
            deviceCode = deviceCode
        )
        if (!storage.savePinSession(session, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        return session
    }

    private suspend fun pollLegacyPin(
        session: SimklPinSession,
        authScope: SimklAuthScope
    ): SimklPinPollResult {
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin/${session.userCode}",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER
            )
        )
        val payload = decodePinResponse(response.body)
        if (!payload.deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return SimklPinPollResult.Invalidated
        }
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (payload.result == "OK" && token != null) {
            return completeAuthorization(token, authScope)
        }
        if (payload.result == "KO") return SimklPinPollResult.Pending
        throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    }

    private suspend fun pollOAuth2Device(
        session: SimklPinSession,
        deviceCode: String,
        authScope: SimklAuthScope
    ): SimklPinPollResult {
        val body = json.encodeToString(
            SimklOAuthDeviceTokenRequest(
                grantType = DEVICE_CODE_GRANT_TYPE,
                deviceCode = deviceCode,
                clientId = configuration.clientId,
                scope = OAUTH2_MEDIA_SCOPES
            )
        )
        val response = try {
            executeAuthRequest(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth2/token",
                    body = body,
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER
                )
            )
        } catch (error: SimklApiException) {
            return when (error.errorCode) {
                "authorization_pending" -> SimklPinPollResult.Pending
                "slow_down" -> {
                    val nextInterval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                    storage.savePinSession(session.copy(intervalSeconds = nextInterval), authScope)
                    SimklPinPollResult.Pending
                }
                "access_denied" -> {
                    storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
                    SimklPinPollResult.Invalidated
                }
                "expired_token" -> {
                    storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
                    SimklPinPollResult.Expired
                }
                else -> throw SimklAuthException(SimklAuthError.NETWORK, error)
            }
        }
        val payload = decodeTokenResponse(response.body)
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        return completeAuthorization(token, authScope)
    }

    private suspend fun completeAuthorization(
        token: String,
        authScope: SimklAuthScope
    ): SimklPinPollResult {
        if (!storage.completePinAuthorization(token, authScope)) {
            return SimklPinPollResult.Invalidated
        }
        try {
            refreshUserSettings(scope = authScope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
        return SimklPinPollResult.Authorized
    }

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: SimklApiException) {
        throw error
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private fun decodePinResponse(body: String): SimklPinResponse =
        runCatching { json.decodeFromString<SimklPinResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private fun decodeTokenResponse(body: String): SimklOAuthTokenResponse =
        runCatching { json.decodeFromString<SimklOAuthTokenResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        /** Legacy PIN codes are 4–12 alphanumerics; OAuth2 device codes include a hyphen (e.g. AB12-CD34). */
        val USER_CODE_PATTERN = Regex("[A-Za-z0-9-]{4,20}")
        const val LEGACY_DEVICE_CODE_PLACEHOLDER = "DEVICE_CODE"
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        /** Required for library mutations, scrobble, and sync write endpoints. */
        const val OAUTH2_MEDIA_SCOPES = "media:read media:write"
    }
}
