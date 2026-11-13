package com.nuvio.tv.data.ryot

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted per-profile storage of one JSON string. */
interface RyotAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

class AndroidRyotAuthPersistence(context: Context) : RyotAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "ryot_auth", "com.nuvio.tv.ryot.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}

/**
 * The Ryot login of the active profile. Same contract as the MDBList store: every change of login
 * or profile bumps the scope's generation, so work started for an older scope can tell it is stale.
 */
class RyotAuthStore(
    private val persistence: RyotAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = RyotAuthScope(initialProfileId, 0)
    private var stored: RyotStoredAuth? = load(initialProfileId)
    private val mutableState = MutableStateFlow(RyotAuthState(currentScope, stored))

    val state: StateFlow<RyotAuthState> = mutableState.asStateFlow()

    fun scope(): RyotAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: RyotAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: RyotAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("Ryot account changed")
    }

    fun authorization(): RyotAuthorization? = synchronized(lock) {
        stored?.let { RyotAuthorization(currentScope, it) }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        currentScope = RyotAuthScope(profileId, currentScope.generation + 1)
        stored = load(profileId)
        publish()
    }

    /** Saves a new login for [scope]. Returns false when the profile changed meanwhile. */
    fun authorize(auth: RyotStoredAuth, scope: RyotAuthScope): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        persistence.write(scope.profileId, json.encodeToString(auth))
        stored = auth
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish()
        true
    }

    /** Logs the profile out. With [expectedApiKey], only when that login is still the stored one. */
    fun clearAuth(
        scope: RyotAuthScope = scope(),
        error: RyotAuthError? = null,
        expectedApiKey: String? = null
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        if (expectedApiKey != null && stored?.apiKey != expectedApiKey) return@synchronized false
        persistence.write(scope.profileId, null)
        stored = null
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = null
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = null
        publish()
    }

    private fun load(profileId: Int): RyotStoredAuth? = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<RyotStoredAuth>(value) }.getOrNull()
    }?.takeIf { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() }

    private fun publish(error: RyotAuthError? = null) {
        mutableState.value = RyotAuthState(currentScope, stored, error)
    }
}
