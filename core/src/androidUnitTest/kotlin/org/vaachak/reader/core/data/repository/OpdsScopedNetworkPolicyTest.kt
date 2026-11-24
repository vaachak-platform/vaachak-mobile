package org.vaachak.reader.core.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.readium.r2.shared.util.Try
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.io.File
import java.io.IOException

/** Invoke the actual scoped network interceptor, without DNS, sockets or a remote service. */
class OpdsScopedNetworkPolicyTest {
    private val row = OpdsEntity(id = 1, title = "Fixture", url = "https://catalog.example/opds",
        username = "fixture-user", password = "fixture-password")
    private val dao = mockk<OpdsDao>()
    private val session = OpdsCatalogSession(row)

    private fun repository(): OpdsRepository {
        every { dao.getAllFeeds() } returns flowOf(emptyList())
        return OpdsRepository(dao).apply { setAbsoluteUrlResolverForTest { mockk(relaxed = true) } }
    }

    private fun interceptor(repository: OpdsRepository): Interceptor {
        // Existing tests use reflection for the private client factory; exercise the real policy.
        val method = OpdsRepository::class.java.getDeclaredMethod("clientForSession", OpdsCatalogSession::class.java)
        method.isAccessible = true
        val client = method.invoke(repository, session) as OkHttpClient
        return client.networkInterceptors.last()
    }

    private fun request(url: String): Request = Request.Builder().url(url)
        .header("Authorization", "Basic synthetic-fixture").header("Cookie", "session=fixture").build()

    @Test fun scopedInterceptorPreservesOnlyExactHttpsOriginHeaders() {
        val policy = interceptor(repository())
        for (url in listOf("https://catalog.example:443/book", "https://other.example/book", "https://catalog.example:8443/book")) {
            val input = request(url)
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns input
            every { chain.proceed(any()) } answers {
                Response.Builder().request(firstArg<Request>()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("Fixture").build()
            }
            val output = policy.intercept(chain).request
            val sameOrigin = input.url.host == "catalog.example" && input.url.port == 443
            check(output.header("Authorization") == if (sameOrigin) input.header("Authorization") else null)
            check(output.header("Cookie") == if (sameOrigin) input.header("Cookie") else null)
            check(output.url == input.url)
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    @Test fun scopedInterceptorRejectsHttpBeforeProceeding() {
        val policy = interceptor(repository())
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request("http://catalog.example/book")
        check(runCatching { policy.intercept(chain) }.exceptionOrNull() is IOException)
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test fun scopedFeedAndDownloadPropagateCancellationRatherThanReturnAnonymousSuccess() = runTest {
        val repository = repository()
        coEvery { dao.getFeedById(row.id) } throws CancellationException("fixture cancellation")
        val client = mockk<OkHttpClient>()
        repository.setClientForTest(client)
        val file = File.createTempFile("opds-cancel-fixture", ".tmp")
        try {
            check(runCatching { repository.parseFeed(row.url, session) }.exceptionOrNull() is CancellationException)
            check(runCatching {
                repository.downloadPublication("https://catalog.example/book.epub", file, row.url, session)
            }.exceptionOrNull() is CancellationException)
            verify(exactly = 0) { client.newCall(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun scopedReadFailureDoesNotExposeBackendDiagnosticOrStartRequest() = runTest {
        val repository = repository()
        coEvery { dao.getFeedById(row.id) } throws IllegalStateException("synthetic-sensitive-db-error")
        val client = mockk<OkHttpClient>()
        repository.setClientForTest(client)
        val result = repository.parseFeed(row.url, session)
        check(result is Try.Failure && result.value.cause == null)
        check(result.value.message?.contains("synthetic-sensitive") == false)
        verify(exactly = 0) { client.newCall(any()) }
    }
}
