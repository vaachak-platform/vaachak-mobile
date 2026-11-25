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
import org.readium.r2.shared.util.Try
import java.io.File

/** Synthetic credentials through the actual lifecycle; no external network. */
class OpdsScopedRequestTest {
    private val root = "https://catalog.example/opds"
    private val harness = OpdsRepositoryHarness()
    private val requests = mutableListOf<Request>()
    private val repository = harness.repository.apply {
        setClientForTest(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val code = if (request.url.encodedPath.endsWith("/folder")) 401 else 200
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body("fixture".toResponseBody("application/octet-stream".toMediaType())).build()
        }.build())
    }

    @Test fun explicitSelectionRoutesFeedBookAndCoverWithoutUrlLookup() = runTest {
        val first = harness.authenticated(root, "reader-a", "fixture-a")
        val second = harness.authenticated(root, "reader-b", "fixture-b")
        val session = OpdsCatalogSession(second)
        val file = File.createTempFile("opds-scoped-request", ".tmp")
        try {
            check(repository.parseFeed("$root/folder", session) is Try.Failure)
            check(repository.downloadPublication("https://catalog.example/book.epub", file, root, session))
            check(repository.downloadPublication("https://catalog.example/cover.jpg", file, root, session))
            check(repository.downloadPublication("https://other.example/book.epub", file, root, session))
            check(repository.downloadPublication("https://catalog.example/book.epub", file, root, OpdsCatalogSession(first)))
            check(requests.map { it.header("Authorization") } == listOf(
                Credentials.basic("reader-b", "fixture-b"), Credentials.basic("reader-b", "fixture-b"),
                Credentials.basic("reader-b", "fixture-b"), null, Credentials.basic("reader-a", "fixture-a")))
            coVerify(exactly = 0) { harness.visible.getFeedByUrl(any()) }
            coVerify(exactly = 0) { harness.visible.getFeedById(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun staleLockedMissingAndLegacySelectionsFailBeforeNetwork() = runTest {
        val row = harness.authenticated(root, "reader-a", "fixture-a")
        val session = OpdsCatalogSession(row)
        val file = File.createTempFile("opds-scoped-failure", ".tmp")
        try {
            harness.backend.rejectRead = true
            check(repository.parseFeed(root, session) is Try.Failure)
            check(!repository.downloadPublication("https://catalog.example/book.epub", file, root, session))
            harness.backend.rejectRead = false
            harness.backend.values.clear()
            check(repository.parseFeed(root, session) is Try.Failure)
            check(repository.saveCatalog(row, "Public", root, CatalogCredentialAction.REMOVE).isSuccess)
            check(repository.parseFeed(root, session) is Try.Failure)
            val legacy = harness.anonymous(root).copy(username = "legacy", password = "fixture")
            harness.rows.rows[legacy.id] = legacy
            check(repository.parseFeed(root, OpdsCatalogSession(legacy)) is Try.Failure)
            check(requests.isEmpty())
            coVerify(exactly = 0) { harness.visible.getFeedByUrl(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }
}
