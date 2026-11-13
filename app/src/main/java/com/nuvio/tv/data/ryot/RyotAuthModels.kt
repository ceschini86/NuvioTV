package com.nuvio.tv.data.ryot

import kotlinx.serialization.Serializable

// Fork: Ryot (self-hosted tracker) as a tracking provider. See docs/adr/0004-ryot-como-provedor-de-tracking.md.

/** The profile a Ryot login belongs to. [generation] changes on every login or logout of that profile. */
data class RyotAuthScope(val profileId: Int, val generation: Long)

/**
 * What the app keeps of a Ryot login. The password is never stored: [apiKey] is the session token
 * Ryot returns from `loginUser`, and [integrationSlug] is the app's own "Generic Json" integration,
 * which receives scrobbles and watched marks at `<baseUrl>/_i/<slug>`.
 */
@Serializable
data class RyotStoredAuth(
    val baseUrl: String,
    val apiKey: String,
    val userId: String,
    val userName: String,
    val integrationSlug: String? = null
) {
    override fun toString(): String = "RyotStoredAuth(baseUrl=$baseUrl, userName=$userName)"
}

enum class RyotAuthError {
    INVALID_URL,
    UNREACHABLE,
    NOT_RYOT,
    WRONG_CREDENTIALS,
    TWO_FACTOR_REQUIRED,
    ACCOUNT_DISABLED,
    SESSION_EXPIRED,
    INTEGRATION_FAILED
}

class RyotAuthException(val error: RyotAuthError, cause: Throwable? = null) : Exception(error.name, cause)

data class RyotAuthState(
    val scope: RyotAuthScope = RyotAuthScope(1, 0),
    val auth: RyotStoredAuth? = null,
    val error: RyotAuthError? = null
) {
    val isAuthenticated: Boolean get() = auth != null
}

/** A Ryot login bound to the profile scope it was read in. */
class RyotAuthorization(val scope: RyotAuthScope, val auth: RyotStoredAuth) {
    override fun toString(): String = "RyotAuthorization(scope=$scope)"
}
