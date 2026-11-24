package org.vaachak.reader.core.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.readium.r2.shared.util.Try
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.io.File

/** Synthetic credentials; intercept all requests before any external connection. */
class OpdsScopedRequestTest {
    private val root = "https://catalog.example/opds"
    private val first = OpdsEntity(id = 1L, title = "First", url = root, username = "reader-a", password = "fixture-a")
    private val second = first.copy(id = 2L, title = "Second", username = "reader-b", password = "fixture-b")
    private val dao = mockk<OpdsDao>()
    private val requests = mutableListOf<Request>()

    private fun repository(): OpdsRepository {
        every { dao.getAllFeeds() } returns flowOf(emptyList())
        coEvery { dao.getFeedById(1L) } returns first
        coEvery { dao.getFeedById(2L) } returns second
        return OpdsRepository(dao).apply {
            setAbsoluteUrlResolverForTest { mockk(relaxed = true) }
            setClientForTest(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val code = if (request.url.encodedPath.endsWith("/folder")) 401 else 200
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                    .body("fixture".toResponseBody("application/octet-stream".toMediaType())).build()
            }.build())
        }
    }

    @Test fun explicitSelectionRoutesFeedBookAndCoverWithoutUrlLookup() = runTest {
        val repository = repository()
        val session = OpdsCatalogSession(second)
        val file = File.createTempFile("opds-scoped-request", ".tmp")
        try {
            // HTTP 401 captures the request without invoking Readium parsers.
            check(repository.parseFeed("$root/folder", session) is Try.Failure)
            check(repository.downloadPublication("https://catalog.example/book.epub", file, root, session))
            check(repository.downloadPublication("https://catalog.example/cover.jpg", file, root, session))
            check(repository.downloadPublication("https://other.example/book.epub", file, root, session))
            check(requests.map { it.header("Authorization") } == listOf(
                Credentials.basic("reader-b", "fixture-b"), Credentials.basic("reader-b", "fixture-b"),
                Credentials.basic("reader-b", "fixture-b"), null))
            coVerify(exactly = 4) { dao.getFeedById(2L) }
            coVerify(exactly = 0) { dao.getFeedByUrl(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun staleAndEncryptedSelectionFailBeforeNetwork() = runTest {
        val repository = repository()
        val session = OpdsCatalogSession(first)
        coEvery { dao.getFeedById(1L) } returns first.copy(password = "changed")
        val result = repository.parseFeed(root, session)
        check(result is Try.Failure && result.value.message?.contains("changed") != true)
        val active = first.copy(credentialGeneration = "generation")
        coEvery { dao.getFeedById(1L) } returns active
        val file = File.createTempFile("opds-scoped-failure", ".tmp")
        try {
            check(!repository.downloadPublication("https://catalog.example/book.epub", file, root, OpdsCatalogSession(active)))
            check(requests.isEmpty())
            coVerify(exactly = 0) { dao.getFeedByUrl(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }
}
