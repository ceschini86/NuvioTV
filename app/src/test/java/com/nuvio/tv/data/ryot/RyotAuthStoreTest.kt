package com.nuvio.tv.data.ryot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RyotAuthStoreTest {
    private val auth = RyotStoredAuth("https://ryot.example.com", "key", "usr_1", "ceschini", "int")

    @Test
    fun `each profile keeps its own login`() {
        val persistence = InMemoryRyotAuthPersistence()
        val store = RyotAuthStore(persistence)
        assertTrue(store.authorize(auth, store.scope()))

        store.selectProfile(2)
        assertFalse(store.state.value.isAuthenticated)

        store.selectProfile(1)
        assertEquals("ceschini", store.state.value.auth?.userName)
        assertEquals("ceschini", RyotAuthStore(persistence).state.value.auth?.userName)
    }

    @Test
    fun `a login for an old scope is refused`() {
        val store = RyotAuthStore(InMemoryRyotAuthPersistence())
        val stale = store.scope()
        store.selectProfile(2)

        assertFalse(store.authorize(auth, stale))
        assertFalse(store.state.value.isAuthenticated)
    }

    @Test
    fun `clearing with an older api key keeps the newer login`() {
        val store = RyotAuthStore(InMemoryRyotAuthPersistence())
        store.authorize(auth.copy(apiKey = "new"), store.scope())
        val before = store.scope()

        assertFalse(store.clearAuth(before, RyotAuthError.SESSION_EXPIRED, expectedApiKey = "old"))
        assertTrue(store.state.value.isAuthenticated)

        assertTrue(store.clearAuth(before, RyotAuthError.SESSION_EXPIRED, expectedApiKey = "new"))
        assertNull(store.state.value.auth)
        assertNotEquals(before, store.scope())
    }

    @Test
    fun `stored auth does not print the api key`() {
        assertFalse(auth.toString().contains("key"))
    }
}
