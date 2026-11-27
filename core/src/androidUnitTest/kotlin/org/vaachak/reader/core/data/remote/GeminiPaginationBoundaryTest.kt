package org.vaachak.reader.core.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiPaginationBoundaryTest {
    private fun client(body: (okhttp3.Request) -> String) = GeminiRestClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body(chain.request()).toResponseBody()).build()
    }.build())
    @Test fun paginatedCatalogDeduplicatesAndNeverRunsInference() = runBlocking {
        val tokens = mutableListOf<String?>()
        val c = client { request ->
            assertEquals("GET",request.method)
            assertEquals("synthetic-key",request.header("x-goog-api-key"))
            val token = request.url.queryParameter("pageToken"); tokens.add(token)
            if (token == null) """{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["countTokens","generateContent"]},{"name":"models/gemini-missing-methods"}],"nextPageToken":"next"}"""
            else """{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}"""
        }
        assertEquals(listOf("gemini-a","gemini-b"),c.listTextCandidates("synthetic-key"))
        assertEquals(listOf(null,"next"),tokens)
    }
    @Test fun repeatedOversizedAndTooManyPageTokensAreRejected() = runBlocking {
        var requests = 0
        val repeated = client { requests++; """{"models":[],"nextPageToken":"same"}""" }
        assertTrue(runCatching { repeated.listTextCandidates("key") }.exceptionOrNull() is GeminiResponseException)
        assertEquals(2,requests)
        val oversized = client { """{"models":[],"nextPageToken":"${"x".repeat(4097)}"}""" }
        assertTrue(runCatching { oversized.listTextCandidates("key") }.exceptionOrNull() is GeminiResponseException)
        requests=0
        val endless = client { requests++; """{"models":[],"nextPageToken":"page-$requests"}""" }
        assertTrue(runCatching { endless.listTextCandidates("key") }.exceptionOrNull() is GeminiResponseException)
        assertEquals(8,requests)
    }
    @Test fun malformedOrOversizedCatalogAndInvalidInputsFailClosed() = runBlocking {
        for (raw in listOf("", "not json", "{}", """{"models":[1]}""", "x".repeat(1024*1024+1))) {
            assertTrue(runCatching { client { raw }.listTextCandidates("key") }.exceptionOrNull() is GeminiResponseException)
        }
        val never = client { throw AssertionError("No request expected") }
        assertTrue(runCatching { never.listTextCandidates("") }.isFailure)
        for (prompt in listOf("", "x".repeat(48_001))) {
            assertTrue(runCatching { never.generate("gemini-a","key",prompt) }.isFailure)
        }
    }
}
