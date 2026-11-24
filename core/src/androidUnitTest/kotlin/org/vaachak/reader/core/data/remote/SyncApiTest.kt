package org.vaachak.reader.core.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.vaachak.reader.core.data.remote.dto.LoginRequest
import org.vaachak.reader.core.data.remote.dto.RegisterRequest
import org.vaachak.reader.core.data.remote.dto.SyncDto

/**
 * Security-sensitive sync HTTP regression tests, using synthetic credentials.
 * MockEngine never makes an external request, including for redirect responses.
 */
class SyncApiTest {
    private val fixturePassword = "fixture-not-a-real-password"
    private val syncResponse = """{"new_sync_timestamp":1700000000,"vault_entries":[]}"""

    private fun fake(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        observe: (HttpRequestData) -> Unit = {}
    ): SyncApi = SyncApi(MockEngine { request ->
        observe(request)
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )
    })

    private fun syncRequest(): SyncDto.SyncRequest = SyncDto.SyncRequest(
        auth = SyncDto.AuthDto("fixture-reader", fixturePassword),
        deviceId = "fixture-device",
        lastSyncTimestamp = 0L,
        vaultEntries = emptyList()
    )

    @Test fun syncSuccessParsesDtoAndUsesHttpsPost() = runBlocking {
        var observed: HttpRequestData? = null
        val api = fake(body = syncResponse) { observed = it }
        api.setBaseUrl("https://sync.example.org/private/")
        val result = api.syncVault(syncRequest())
        assertTrue(result.isSuccess)
        assertEquals(1_700_000_000L, result.getOrThrow().newSyncTimestamp)
        assertTrue(result.getOrThrow().vaultEntries.isEmpty())
        assertEquals(HttpMethod.Post, observed?.method)
        assertEquals("https", observed?.url?.protocol?.name)
        assertEquals("/private/api/v1/sync", observed?.url?.encodedPath)
    }

    @Test fun unauthorizedAndServerFailuresDoNotRevealCredentials() = runBlocking {
        val unauthorized = fake(HttpStatusCode.Unauthorized).syncVault(syncRequest())
        assertTrue(unauthorized.isFailure)
        assertTrue(unauthorized.exceptionOrNull()?.message.orEmpty().contains("Unauthorized"))
        val failed = fake(HttpStatusCode.ServiceUnavailable).syncVault(syncRequest())
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("HTTP 503"))
        assertFalse(failed.exceptionOrNull()?.message.orEmpty().contains(fixturePassword))
    }

    @Test fun malformedSyncResponseFailsWithSanitizedTransportMessage() = runBlocking {
        val result = fake(body = "not-json fixture-password-dont-log").syncVault(syncRequest())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Secure sync transport failed"))
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("fixture-password-dont-log"))
    }

    @Test fun registerHandlesSuccessConflictAndFailure() = runBlocking {
        val request = RegisterRequest("fixture-reader", fixturePassword)
        var path = ""
        val success = fake(HttpStatusCode.Created) { path = it.url.encodedPath }.register(request)
        assertTrue(success.isSuccess)
        assertEquals("/api/v1/register", path)
        val conflict = fake(HttpStatusCode.Conflict).register(request)
        assertTrue(conflict.isFailure)
        assertEquals("Username already taken", conflict.exceptionOrNull()?.message)
        val unavailable = fake(HttpStatusCode.ServiceUnavailable).register(request)
        assertTrue(unavailable.isFailure)
        assertTrue(unavailable.exceptionOrNull()?.message.orEmpty().contains("HTTP 503"))
        assertFalse(unavailable.exceptionOrNull()?.message.orEmpty().contains(fixturePassword))
    }

    @Test fun loginHandlesSuccessAndRejectsNonSuccess() = runBlocking {
        val request = LoginRequest("fixture-reader", fixturePassword)
        var observed: HttpRequestData? = null
        val success = fake { observed = it }.login(request)
        assertTrue(success.isSuccess)
        assertEquals(HttpMethod.Post, observed?.method)
        assertEquals("/api/v1/login", observed?.url?.encodedPath)
        val unauthorized = fake(HttpStatusCode.Unauthorized).login(request)
        assertTrue(unauthorized.isFailure)
        assertTrue(unauthorized.exceptionOrNull()?.message.orEmpty().contains("HTTP 401"))
        assertFalse(unauthorized.exceptionOrNull()?.message.orEmpty().contains(fixturePassword))
    }

    @Test fun healthCheckUsesCorrectPathAndRequiresSuccessStatus() = runBlocking {
        var observed: HttpRequestData? = null
        val api = fake { observed = it }
        api.setBaseUrl("https://sync.example.org/api/")
        assertTrue(api.testConnection().isSuccess)
        assertEquals(HttpMethod.Get, observed?.method)
        assertEquals("/api/api/v1/health", observed?.url?.encodedPath)
        val failed = fake(HttpStatusCode.ServiceUnavailable).testConnection()
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("HTTP 503"))
    }

    @Test fun redirectIsNeverFollowedWithCredentials() = runBlocking {
        var requests = 0
        val api = SyncApi(MockEngine {
            requests += 1
            respond(
                content = "redirect fixture",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://another.example.org/steal")
            )
        })
        val result = api.syncVault(syncRequest())
        assertTrue(result.isFailure)
        assertEquals(1, requests)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains(fixturePassword))
    }

    @Test fun transportExceptionsAreSanitizedForEveryEndpoint() = runBlocking {
        fun failingClient() = SyncApi(MockEngine {
            throw IllegalStateException("sensitive-endpoint/fixture-secret-in-client-exception")
        })
        val outcomes = listOf(
            failingClient().syncVault(syncRequest()).exceptionOrNull(),
            failingClient().register(RegisterRequest("fixture-reader", fixturePassword)).exceptionOrNull(),
            failingClient().login(LoginRequest("fixture-reader", fixturePassword)).exceptionOrNull(),
            failingClient().testConnection().exceptionOrNull()
        )
        outcomes.forEach { failure ->
            assertTrue("Expected sanitized transport failure", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("Secure sync transport failed"))
            assertFalse(failure?.message.orEmpty().contains("fixture-secret-in-client-exception"))
        }
    }

    @Test fun explicitHttpAndEmbeddedCredentialsAreRejectedBeforeRequest() {
        var requests = 0
        val api = fake(observe = { requests += 1 })
        for (bad in listOf("http://sync.example.org", "https://user:pass@sync.example.org")) {
            try {
                api.setBaseUrl(bad)
                fail("Accepted a credential-bearing unsafe URL")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.isNotBlank() == true)
            }
        }
        assertEquals(0, requests)
    }
}
