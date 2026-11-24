package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.vaachak.reader.core.domain.model.OpdsEntity

class OpdsCatalogSessionTest {
    private val url = "https://catalog.example/opds"
    private val first = OpdsEntity(id = 1, title = "One", url = url, username = "reader-a", password = "fixture-a")
    private val second = first.copy(id = 2, title = "Two", username = "reader-b", password = "fixture-b")

    @Test fun duplicateUrlsUseOnlySelectedRow() = runBlocking {
        val rows = mapOf(first.id to first, second.id to second)
        val ids = mutableListOf<Long>()
        val pair = resolveLegacyCatalogCredentials(OpdsCatalogSession(second), "$url/folder") { id ->
            ids.add(id)
            rows[id]
        }
        check(ids == listOf(2L) && pair?.username == "reader-b" && pair.password == "fixture-b")
    }

    @Test fun foreignHostAndPortNeverReceiveLegacyPair() = runBlocking {
        val session = OpdsCatalogSession(first)
        for (target in listOf("https://other.example/book.epub", "https://catalog.example:8443/book.epub")) {
            check(resolveLegacyCatalogCredentials(session, target) { first } == null)
        }
        check(resolveLegacyCatalogCredentials(session, "https://catalog.example:443/book.epub") { first } != null)
    }

    @Test fun missingWrongOrEditedRowCannotBeReselectedByUrl() = runBlocking {
        val session = OpdsCatalogSession(first)
        for (row in listOf(null, second, first.copy(url = "$url/new"), first.copy(password = "changed"),
            first.copy(username = null, password = null), first.copy(credentialGeneration = "new-generation"))) {
            check(runCatching { resolveLegacyCatalogCredentials(session, url) { row } }.isFailure)
        }
    }

    @Test fun metadataOnlyEditDoesNotChangeCredentialRevision() = runBlocking {
        val session = OpdsCatalogSession(first)
        val pair = resolveLegacyCatalogCredentials(session, url) { first.copy(title = "Renamed") }
        check(pair?.username == first.username)
    }

    @Test fun anonymousCatalogAndIncompleteCredentialAreDistinct() = runBlocking {
        val public = first.copy(username = null, password = null)
        val partial = public.copy(username = "reader")
        val blank = first.copy(password = "")
        check(resolveLegacyCatalogCredentials(OpdsCatalogSession(public), url) { public } == null)
        check(runCatching { resolveLegacyCatalogCredentials(OpdsCatalogSession(partial), url) { partial } }.isFailure)
        check(runCatching { resolveLegacyCatalogCredentials(OpdsCatalogSession(blank), url) { blank } }.isFailure)
    }

    @Test fun activeGenerationNeverFallsBackToLegacyOrAnonymousOnSameOrigin() = runBlocking {
        for (row in listOf(first, first.copy(username = null, password = null))) {
            val active = row.copy(credentialGeneration = "generation")
            check(runCatching { resolveLegacyCatalogCredentials(OpdsCatalogSession(active), url) { active } }.isFailure)
            check(resolveLegacyCatalogCredentials(OpdsCatalogSession(active), "https://other.example/file") { active } == null)
        }
    }

    @Test fun invalidIdentityAndUrlsFailBeforeLookup() = runBlocking {
        var reads = 0
        val cases = listOf(first.copy(id = 0L) to url, first.copy(url = "http://catalog.example/opds") to url,
            first to "http://catalog.example/file", first to "https://user:password@catalog.example/file")
        for ((row, target) in cases) {
            check(runCatching { resolveLegacyCatalogCredentials(OpdsCatalogSession(row), target) { reads++; row } }.isFailure)
        }
        check(reads == 0)
    }

    @Test fun sessionIsRedactedAndReopeningCreatesNewIdentity() {
        val a = OpdsCatalogSession(first)
        val b = OpdsCatalogSession(first)
        check(a !== b && a != b)
        check(a.toString() == "OpdsCatalogSession(REDACTED)")
    }
}
