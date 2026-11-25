package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import org.junit.Test
import java.io.File

class GutendexSecurityPolicyTest {
    @Test fun publicFeedRejectsForeignHttpAndEmbeddedCredentialsBeforeNetwork() = runTest {
        val repository = GutendexRepository(Dispatchers.IO)
        for (url in listOf("https://other.example/books", "https://gutendex.com:8443/books", "http://gutendex.com/books", "https://fixture-user:fixture-password@gutendex.com/books")) {
            val result = repository.fetchBooks(url)
            check(result.isFailure && result.exceptionOrNull()?.cause == null)
            check(result.exceptionOrNull()?.message?.contains("fixture-password") == false)
        }
    }
    @Test fun publicDownloadRejectsHttpAndEmbeddedCredentialsWithoutTouchingDestination() = runTest {
        val repository = GutendexRepository(Dispatchers.IO)
        val target = File.createTempFile("gutendex-policy", ".tmp")
        try {
            target.writeText("preserve fixture")
            for (url in listOf("http://example.org/book.epub", "https://fixture-user:fixture-password@example.org/book.epub", "not a URL")) {
                check(!repository.downloadBook(url, target))
                check(target.readText() == "preserve fixture")
            }
        } finally { check(target.delete() || !target.exists()) }
    }

    private fun fixture(code: Int, payload: String): GutendexRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().header("Authorization") == null)
            check(chain.request().header("Cookie") == null)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(payload.toResponseBody()).build()
        }.build()
        return GutendexRepository(client, Dispatchers.IO)
    }

    @Test fun publicFeedParsesResponseAndRejectsHttpErrorsOrInvalidBodies() = runTest {
        val url = "https://gutendex.com/books"
        val result = fixture(200, """{"count":0,"next":null,"previous":null,"results":[]}""").fetchBooks(url).getOrThrow()
        check(result.count == 0 && result.results.isEmpty())
        check(fixture(503, "fixture").fetchBooks(url).isFailure)
        check(fixture(200, "").fetchBooks(url).isFailure)
        check(fixture(200, "{").fetchBooks(url).isFailure)
    }

    @Test fun publicDownloadWritesOnlySuccessfulResponse() = runTest {
        val destination = File.createTempFile("gutendex-response", ".epub")
        try {
            val url = "https://example.org/book.epub"
            check(fixture(200, "fixture-book").downloadBook(url, destination))
            check(destination.readText() == "fixture-book")
            check(!fixture(404, "error").downloadBook(url, destination))
            check(destination.readText() == "fixture-book")
        } finally { check(destination.delete()) }
    }

    @Test fun publicNetworkFailuresAreSanitizedAndCancellationPropagates() = runTest {
        val url = "https://gutendex.com/books"
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("synthetic-private-diagnostic") }.build()
        val repository = GutendexRepository(client, Dispatchers.IO)
        val error = repository.fetchBooks(url).exceptionOrNull()
        check(error?.message == "Public catalog unavailable" && error.cause == null)
        val cancelled = GutendexRepository(OkHttpClient.Builder().addInterceptor { throw CancellationException("fixture") }.build(), Dispatchers.IO)
        check(runCatching { cancelled.fetchBooks(url) }.exceptionOrNull() is CancellationException)
    }

}
