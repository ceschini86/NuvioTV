package com.nuvio.tv.data.ryot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RyotAuthRepositoryTest {
    private val http = FakeRyotHttp()
    private val persistence = InMemoryRyotAuthPersistence()
    private val store = RyotAuthStore(persistence)
    private val repository = RyotAuthRepository(RyotGraphqlClient(http), store)

    private fun serverWithIntegration(integrations: String) {
        http.on("coreDetails", body = ryotData("""{"coreDetails":{"version":"v10.5.8"}}"""))
        http.on("loginUser", body = ryotData("""{"loginUser":{"__typename":"ApiKeyResponse","apiKey":"key-1"}}"""))
        http.on("userDetails", body = ryotData("""{"userDetails":{"__typename":"UserDetails","id":"usr_1","name":"ceschini"}}"""))
        http.on("userIntegrations", body = ryotData("""{"userIntegrations":$integrations}"""))
    }

    @Test
    fun `login stores the api key, user and existing integration but not the password`() = runTest {
        serverWithIntegration("""[{"id":"int_x","name":"Other","provider":"GENERIC_JSON","isDisabled":false},
            {"id":"int_nuvio","name":"Nuvio TV","provider":"GENERIC_JSON","isDisabled":false}]""")

        repository.connect("ryot.example.com/", "ceschini", "s3cret")

        val auth = store.state.value.auth!!
        assertEquals("https://ryot.example.com", auth.baseUrl)
        assertEquals("key-1", auth.apiKey)
        assertEquals("usr_1", auth.userId)
        assertEquals("ceschini", auth.userName)
        assertEquals("int_nuvio", auth.integrationSlug)
        assertFalse(persistence.values.getValue(1).contains("s3cret"))
        assertTrue(http.requests.all { it.url == "https://ryot.example.com/backend/graphql" })
        val login = http.requests.single { "loginUser" in it.query }
        assertNull(login.bearer)
        assertEquals("key-1", http.requests.last().bearer)
    }

    @Test
    fun `login creates the integration when the server has none`() = runTest {
        var created = false
        serverWithIntegration("[]")
        http.on("createOrUpdateUserIntegration") { request ->
            created = true
            val input = request.json["variables"]!!.jsonObject["input"]!!.jsonObject
            assertEquals("GENERIC_JSON", input["provider"]!!.jsonPrimitive.content)
            assertEquals("Nuvio TV", input["name"]!!.jsonPrimitive.content)
            RyotHttpResponse(200, ryotData("""{"createOrUpdateUserIntegration":true}"""))
        }
        http.on("userIntegrations") {
            val list = if (created) """[{"id":"int_new","name":"Nuvio TV","provider":"GENERIC_JSON","isDisabled":false}]""" else "[]"
            RyotHttpResponse(200, ryotData("""{"userIntegrations":$list}"""))
        }

        repository.connect("https://ryot.example.com", "ceschini", "pw")

        assertEquals("int_new", store.state.value.auth!!.integrationSlug)
    }

    @Test
    fun `wrong password keeps the profile logged out`() = runTest {
        serverWithIntegration("[]")
        http.on("loginUser", body = ryotData("""{"loginUser":{"__typename":"LoginError","error":"CREDENTIALS_MISMATCH"}}"""))

        val error = expectAuthError { repository.connect("https://ryot.example.com", "ceschini", "bad") }

        assertEquals(RyotAuthError.WRONG_CREDENTIALS, error)
        assertFalse(store.state.value.isAuthenticated)
    }

    @Test
    fun `a server that is not Ryot is rejected before login`() = runTest {
        http.on("coreDetails", code = 200, body = "<html>hello</html>")

        val error = expectAuthError { repository.connect("https://example.com", "u", "p") }

        assertEquals(RyotAuthError.NOT_RYOT, error)
        assertTrue(http.requests.none { "loginUser" in it.query })
    }

    @Test
    fun `an expired session logs the profile out with SESSION_EXPIRED`() = runTest {
        store.authorize(RyotStoredAuth("https://ryot.example.com", "old", "usr_1", "c", "int"), store.scope())
        http.on("userCollectionsList", body = """{"data":null,"errors":[{"message":"NO_USER_ID"}]}""")
        val client = RyotGraphqlClient(http)

        val error = expectAuthError {
            repository.withAuthorization { client.execute(it.auth.baseUrl, it.auth.apiKey, "query { userCollectionsList { cacheId } }") }
        }

        assertEquals(RyotAuthError.SESSION_EXPIRED, error)
        assertFalse(store.state.value.isAuthenticated)
        assertEquals(RyotAuthError.SESSION_EXPIRED, store.state.value.error)
    }

    @Test
    fun `base url normalization`() {
        assertEquals("https://ryot.example.com", normalizeRyotBaseUrl(" ryot.example.com/ "))
        assertEquals("http://192.168.0.2:8000", normalizeRyotBaseUrl("http://192.168.0.2:8000/backend/graphql"))
        assertEquals("https://example.com/ryot", normalizeRyotBaseUrl("https://example.com/ryot/"))
        assertNull(normalizeRyotBaseUrl("ftp://example.com"))
        assertNull(normalizeRyotBaseUrl("   "))
    }

    private suspend fun expectAuthError(block: suspend () -> Unit): RyotAuthError {
        try {
            block()
        } catch (error: RyotAuthException) {
            return error.error
        }
        fail("expected RyotAuthException")
        throw AssertionError()
    }
}
