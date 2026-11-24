package org.vaachak.reader.core.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.domain.model.OpdsEntity
import java.io.File

/** Synthetic catalog passwords; in-memory OkHttp responses, no external network. */
class OpdsDownloadAuthorizationTest {
    private val catalogUrl = "https://reader.example.org/opds"

    @Test fun thirdPartyHttpsDownloadIsAllowedButNeverReceivesCatalogPassword() = runTest {
        assertRequestAuth("https://downloads.example.net/book.epub", null)
    }

    @Test fun anotherPortOnSameHostnameDoesNotReceiveCatalogPassword() = runTest {
        assertRequestAuth("https://reader.example.org:8443/book.epub", null)
    }

    @Test fun sameOriginHttpsDownloadStillReceivesCatalogPassword() = runTest {
        assertRequestAuth("https://reader.example.org/book.epub", okhttp3.Credentials.basic("reader", "synthetic-password"))
    }

    private suspend fun assertRequestAuth(downloadUrl: String, expectedHeader: String?) {
        val dao = mockk<OpdsDao>()
        // OpdsRepository eagerly initializes its two catalog flows in the constructor.
        // Without this stub construction throws MockKException before any download.
        every { dao.getAllFeeds() } returns flowOf(emptyList())
        coEvery { dao.getFeedByUrl(any()) } returns null
        coEvery { dao.getFeedByUrl(catalogUrl) } returns OpdsEntity(
            id = 44, title = "Fixture", url = catalogUrl,
            username = "reader", password = "synthetic-password"
        )
        var actualRequest: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            actualRequest = request
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Test OK")
                .body("fixture-epub".toResponseBody("application/epub+zip".toMediaType()))
                .build()
        }.build()
        val repository = OpdsRepository(dao)
        repository.setClientForTest(client)
        val target = File.createTempFile("vaachak-opds-auth-boundary", ".epub")
        try {
            assertTrue(repository.downloadPublication(downloadUrl, target, catalogUrl))
            assertEquals(expectedHeader, actualRequest?.header("Authorization"))
            assertEquals("fixture-epub", target.readText())
        } finally {
            check(target.delete() || !target.exists())
        }
    }
}
