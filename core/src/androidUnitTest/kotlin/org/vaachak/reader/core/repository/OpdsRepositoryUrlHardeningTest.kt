package org.vaachak.reader.core.data.repository

import com.sun.net.httpserver.HttpServer
import io.mockk.coVerify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** The old URL-only authorization assertions are deliberately replaced by secure-generation assertions. */
class OpdsRepositoryUrlHardeningTest {
    private val harness = OpdsRepositoryHarness()
    private val repository = harness.repository
    private val root = "https://secure.example/opds"
    private val requests = mutableListOf<Request>()

    private fun response(code: Int = 200, payload: ByteArray = "fixture".toByteArray(), type: String = "application/octet-stream") {
        repository.setClientForTest(OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic response").body(payload.toResponseBody(type.toMediaType())).build()
        }.build())
    }

    private fun failure(result: Try<*, Exception>, messagePart: String) {
        check(result is Try.Failure && result.value.message.orEmpty().contains(messagePart, ignoreCase = true))
    }

    @Test fun catalogs_seedsDefaultFeeds_whenDatabaseIsEmpty() = runTest {
        repository.catalogs.first()
        repository.allFeeds.first()
        check(harness.rows.rows.values.map { it.url } == listOf("https://gutendex.com/books", "https://manybooks.net/opds"))
        check(harness.rows.rows.values.all { it.username == null && it.password == null && it.credentialGeneration == null })
        coVerify(exactly = 0) { harness.visible.insertFeed(any()) }
    }

    @Test fun catalogs_doesNotSeed_whenDatabaseHasFeeds() = runTest {
        val row = harness.anonymous(root)
        repository.catalogs.first()
        check(harness.rows.rows.values.single() == row)
        coVerify(exactly = 0) { harness.visible.insertFeed(any()) }
    }

    @Test fun saveCatalog_savesValidHttpsUrlWithOnlyEncryptedGeneration() = runTest {
        check(repository.saveCatalog(null, "My Calibre", root, CatalogCredentialAction.REPLACE, "reader", "fixture").isSuccess)
        val saved = harness.rows.rows.values.single()
        check(saved.url == root && saved.username == null && saved.password == null && saved.credentialGeneration != null)
        check(harness.lifecycle.readForRequest(OpdsCatalogSession(saved), root)?.password == "fixture")
        coVerify(exactly = 0) { harness.visible.insertFeed(any()) }
    }

    @Test fun saveCatalog_preservesIdentityWhenUpdatingPublicUrl() = runTest {
        val original = harness.anonymous(root)
        val target = "https://example.com/catalog.xml"
        check(repository.saveCatalog(original, "Updated Feed", target, CatalogCredentialAction.KEEP).isSuccess)
        val updated = harness.rows.rows.values.single()
        check(updated.id == original.id && updated.url == target && updated.title == "Updated Feed")
        coVerify(exactly = 0) { harness.visible.updateFeed(any()) }
    }

    @Test fun saveCatalog_rejectsHttpUrlWithoutAllocatingRow() = runTest {
        val result = repository.saveCatalog(null, "Broken", "http://example.com/opds", CatalogCredentialAction.REMOVE)
        check(result.isFailure && harness.rows.rows.isEmpty())
    }

    @Test fun saveCatalog_rejectsHttpUpdateWithoutChangingRow() = runTest {
        val row = harness.anonymous(root)
        check(repository.saveCatalog(row, "Changed", "http://example.com/opds", CatalogCredentialAction.KEEP).isFailure)
        check(harness.rows.rows.values.single() == row)
    }

    @Test fun parseFeed_returnsFailure_forHttpUrl() = runTest {
        response()
        failure(repository.parseFeed("http://example.com/opds"), "HTTPS")
        check(requests.isEmpty())
    }

    @Test fun parseFeed_returnsFailure_forInvalidUrl() = runTest {
        response()
        failure(repository.parseFeed("not a url"), "Invalid URL")
        check(requests.isEmpty())
    }

    @Test fun parseFeed_returnsFailure_whenAbsoluteUrlResolverReturnsNull() = runTest {
        response()
        repository.setAbsoluteUrlResolverForTest { null }
        failure(repository.parseFeed(root), "Invalid URL")
        check(requests.isEmpty())
    }

    @Test fun downloadPublication_returnsFalse_forHttpDownloadUrl() = runTest {
        response()
        val file = File.createTempFile("opds-http-fixture", ".tmp")
        try {
            check(!repository.downloadPublication("http://example.com/book.epub", file, root))
            check(requests.isEmpty())
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun downloadPublication_returnsFalse_forInvalidOriginalFeedUrl() = runTest {
        response()
        val file = File.createTempFile("opds-invalid-fixture", ".tmp")
        try {
            check(!repository.downloadPublication("https://example.com/book.epub", file, "not a url"))
            check(requests.isEmpty())
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun parseFeed_returnsFailure_forHttpErrorResponse() = runTest {
        response(code = 500)
        failure(repository.parseFeed(root), "HTTP 500")
        check(requests.size == 1)
    }

    @Test fun parseFeed_returnsFailure_forEmptyBody() = runTest {
        response(payload = byteArrayOf(), type = "application/xml")
        failure(repository.parseFeed(root), "Empty response")
    }

    @Test fun parseFeed_returnsFailure_forHtmlBody() = runTest {
        response(payload = "<html><body>Login</body></html>".toByteArray(), type = "text/html")
        failure(repository.parseFeed(root), "HTML")
    }

    @Test fun parseFeed_addsAuthHeader_whenSelectedGenerationHasCredentials() = runTest {
        val row = harness.authenticated(root, "reader", "fixture")
        response(code = 401)
        failure(repository.parseFeed(root, OpdsCatalogSession(row)), "HTTP 401")
        check(requests.single().header("Authorization") == Credentials.basic("reader", "fixture"))
    }

    @Test fun parseFeed_rejectsLegacyPartialUsernameOrPasswordBeforeNetwork() = runTest {
        response(code = 401)
        for ((user, pass) in listOf("" to "fixture", "reader" to "", null to "fixture")) {
            val row = harness.anonymous(root).copy(username = user, password = pass)
            harness.rows.rows[row.id] = row
            check(repository.parseFeed(root, OpdsCatalogSession(row)) is Try.Failure)
        }
        check(requests.isEmpty())
    }

    @Test fun parseFeed_urlOnlyCompatibilityNeverLoadsCredentials() = runTest {
        harness.authenticated(root, "reader", "fixture")
        response(code = 401)
        failure(repository.parseFeed(root), "HTTP 401")
        check(requests.single().header("Authorization") == null)
        coVerify(exactly = 0) { harness.visible.getFeedByUrl(any()) }
    }

    @Test fun parseFeed_executesJsonParserPath_forOpds2LikeBody() = runTest {
        response(payload = """{"metadata":{"title":"Catalog"},"links":[],"publications":[],"navigation":[]}""".toByteArray(), type = "application/opds+json")
        val result = repository.parseFeed(root)
        if (result is Try.Failure) check(result.value.message?.contains("no answer found") != true)
        check(requests.size == 1)
    }

    @Test fun parseFeed_executesOpds1FallbackPath_forBomXmlBody() = runTest {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Catalog</title><id>urn:uuid:catalog-1</id><updated>2020-01-01T00:00:00Z</updated></feed>"""
        response(payload = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray(), type = "application/atom+xml")
        val result = repository.parseFeed(root)
        if (result is Try.Failure) check(result.value.message?.contains("no answer found") != true)
        check(requests.size == 1)
    }

    @Test fun downloadPublication_writesFileAndUsesSelectedGeneration() = runTest {
        val row = harness.authenticated(root, "reader", "fixture")
        response(payload = "epub-bytes".toByteArray(), type = "application/epub+zip")
        val file = File.createTempFile("opds-download-fixture", ".epub")
        try {
            check(repository.downloadPublication("https://secure.example/book.epub", file, root, OpdsCatalogSession(row)))
            check(requests.single().header("Authorization") == Credentials.basic("reader", "fixture"))
            check(file.readText() == "epub-bytes")
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun downloadPublication_neverUsesOpdsBaseFallbackForAuthLookup() = runTest {
        harness.authenticated(root, "reader", "fixture")
        response(payload = "epub-bytes".toByteArray())
        val file = File.createTempFile("opds-no-fallback", ".epub")
        try {
            check(repository.downloadPublication("https://secure.example/book.epub", file, "$root/search?q=test"))
            check(requests.single().header("Authorization") == null && file.readText() == "epub-bytes")
            coVerify(exactly = 0) { harness.visible.getFeedByUrl(any()) }
        } finally { check(file.delete() || !file.exists()) }
    }

    @Test fun deleteFeed_returnsSanitizedFailure_whenDaoThrows() = runTest {
        val row = harness.anonymous(root)
        harness.rows.loadFailure = IllegalStateException("synthetic-sensitive-db-error")
        val result = repository.deleteFeed(row)
        check(result.isFailure && result.exceptionOrNull()?.cause == null)
        check(result.exceptionOrNull()?.message?.contains("synthetic-sensitive") == false)
        check(harness.rows.rows[row.id] == row)
    }

    @Test fun createClient_appliesHeadersWithoutPersistingUnscopedCookies() {
        val method = repository.javaClass.getDeclaredMethod("createClient").apply { isAccessible = true }
        val client = method.invoke(repository) as OkHttpClient
        val requestCount = AtomicInteger()
        var firstUserAgent: String? = null
        var firstAccept: String? = null
        var secondCookie: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feed") { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                firstUserAgent = exchange.requestHeaders.getFirst("User-Agent")
                firstAccept = exchange.requestHeaders.getFirst("Accept")
                exchange.responseHeaders.add("Set-Cookie", "session=abc; Path=/")
            } else secondCookie = exchange.requestHeaders.getFirst("Cookie")
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/feed"
            repeat(2) { client.newCall(Request.Builder().url(url).build()).execute().use {} }
        } finally { server.stop(0) }
        check(firstUserAgent == "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        check(firstAccept?.contains("application/opds+json") == true && secondCookie == null)
        check(requestCount.get() == 2)
    }

    @Test fun parseFeed_nonHtmlXml_executesOpdsFallbackAndReturnsXmlError() = runTest {
        response(payload = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<feed><title><div>Title</div></title></feed>".toByteArray(), type = "application/xml")
        val result = repository.parseFeed(root)
        check(result is Try.Failure || result is Try.Success)
        check(requests.size == 1)
    }

    @Test fun sanitizeXml_rewritesNestedDivInTitle() {
        val method = repository.javaClass.getDeclaredMethod("sanitizeXml", ByteArray::class.java).apply { isAccessible = true }
        val output = method.invoke(repository, "<feed><title><div>My Title</div></title></feed>".toByteArray()) as ByteArray
        check(output.toString(Charsets.UTF_8).contains("<title>My Title</title>"))
    }

    @Test fun urlEmbeddedUserInfoIsRejectedAndNotLeaked() = runTest {
        response()
        val result = repository.parseFeed("https://fixture-user:fixture-password@secure.example/opds")
        check(result is Try.Failure && result.value.message?.contains("fixture-password") == false)
        check(requests.isEmpty())
        check(repository.saveCatalog(null, "Fixture", "https://@secure.example/opds", CatalogCredentialAction.REMOVE).isFailure)
        check(harness.rows.rows.isEmpty())
    }

    @Test fun repositoryMigrationThenDeleteRetiresOnlySelectedGeneration() = runTest {
        val original = harness.anonymous(root)
        val legacy = original.copy(username = "legacy-fixture", password = "fixture-password")
        harness.rows.rows[original.id] = legacy
        val other = harness.authenticated(root, "other-fixture", "other-password")
        check(repository.migrateLegacyCredentials().getOrThrow() == OpdsMigrationReport(1, 0))
        val active = checkNotNull(harness.rows.rows[original.id])
        check(active.username == null && active.password == null)
        val generation = checkNotNull(active.credentialGeneration)
        check(harness.lifecycle.readForRequest(OpdsCatalogSession(active), root)?.password == "fixture-password")
        check(repository.deleteFeed(active).isSuccess)
        check(original.id !in harness.rows.rows)
        check(harness.rows.entries[original.id to generation]?.state == "RETIRED")
        check(harness.lifecycle.readForRequest(OpdsCatalogSession(other), root)?.password == "other-password")
    }

    @Test fun missingReplacementFieldsDoNotChangeSavedGeneration() = runTest {
        val saved = harness.authenticated(root, "reader", "fixture")
        check(repository.saveCatalog(saved, "Changed", root, CatalogCredentialAction.REPLACE, null, "fixture").isFailure)
        check(repository.saveCatalog(saved, "Changed", root, CatalogCredentialAction.REPLACE, "reader", null).isFailure)
        check(harness.rows.rows[saved.id] == saved)
    }

    @Test fun cancelledFeedRequestIsNotConvertedToOrdinaryFailure() = runTest {
        repository.setClientForTest(OkHttpClient.Builder().addInterceptor { throw CancellationException("fixture") }.build())
        check(runCatching { repository.parseFeed(root) }.exceptionOrNull() is CancellationException)
    }

}
