package org.vaachak.reader.core.data.repository

import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.File

/** Synthetic encrypted catalogs; in-memory OkHttp responses, no external network. */
class OpdsDownloadAuthorizationTest {
    private val catalogUrl = "https://reader.example.org/opds"

    @Test fun thirdPartyHttpsDownloadIsAllowedButNeverReceivesCatalogPassword() = runTest {
        assertRequestAuth("https://downloads.example.net/book.epub", null)
    }
    @Test fun anotherPortOnSameHostnameDoesNotReceiveCatalogPassword() = runTest {
        assertRequestAuth("https://reader.example.org:8443/book.epub", null)
    }
    @Test fun sameOriginHttpsDownloadStillReceivesCatalogPassword() = runTest {
        assertRequestAuth("https://reader.example.org/book.epub", Credentials.basic("reader", "synthetic-password"))
    }

    private suspend fun assertRequestAuth(downloadUrl: String, expectedHeader: String?) {
        val harness = OpdsRepositoryHarness()
        val row = harness.authenticated(catalogUrl, "reader", "synthetic-password")
        var actualRequest: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            actualRequest = request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Test OK")
                .body("fixture-epub".toResponseBody("application/epub+zip".toMediaType())).build()
        }.build()
        harness.repository.setClientForTest(client)
        val target = File.createTempFile("vaachak-opds-auth-boundary", ".epub")
        try {
            check(harness.repository.downloadPublication(downloadUrl, target, catalogUrl, OpdsCatalogSession(row)))
            check(actualRequest != null && actualRequest?.header("Authorization") == expectedHeader)
            check(target.readText() == "fixture-epub")
            coVerify(exactly = 0) { harness.visible.getFeedByUrl(any()) }
        } finally { check(target.delete() || !target.exists()) }
    }
}
