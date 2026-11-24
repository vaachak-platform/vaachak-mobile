package org.vaachak.reader.core.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class GutendexSecurityPolicyTest {
    @Test fun publicFeedRejectsForeignHttpAndEmbeddedCredentialsBeforeNetwork() = runTest {
        val repository = GutendexRepository()
        for (url in listOf("https://other.example/books", "https://gutendex.com:8443/books", "http://gutendex.com/books", "https://fixture-user:fixture-password@gutendex.com/books")) {
            val result = repository.fetchBooks(url)
            check(result.isFailure && result.exceptionOrNull()?.cause == null)
            check(result.exceptionOrNull()?.message?.contains("fixture-password") == false)
        }
    }
    @Test fun publicDownloadRejectsHttpAndEmbeddedCredentialsWithoutTouchingDestination() = runTest {
        val repository = GutendexRepository()
        val target = File.createTempFile("gutendex-policy", ".tmp")
        try {
            target.writeText("preserve fixture")
            for (url in listOf("http://example.org/book.epub", "https://fixture-user:fixture-password@example.org/book.epub", "not a URL")) {
                check(!repository.downloadBook(url, target))
                check(target.readText() == "preserve fixture")
            }
        } finally { check(target.delete() || !target.exists()) }
    }
}
