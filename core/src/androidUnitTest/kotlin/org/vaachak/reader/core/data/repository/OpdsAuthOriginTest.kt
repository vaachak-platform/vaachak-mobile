package org.vaachak.reader.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL comparison is pure; these cases must not make network requests. */
class OpdsAuthOriginTest {
    @Test fun sameHttpsHostAndEffectivePortCanReuseAuthentication() {
        val catalog = "https://reader.example.org/opds"
        listOf(
            "https://reader.example.org/books/1.epub",
            "https://READER.EXAMPLE.ORG/books/1.epub",
            "https://reader.example.org:443/books/1.epub",
            "https://reader.example.org/opds/items?page=2"
        ).forEach { download ->
            assertTrue("Should preserve same-origin OPDS authentication: $download",
                OpdsAuthOrigin.sameOrigin(catalog, download))
        }
        assertTrue(OpdsAuthOrigin.sameOrigin(
            "https://reader.example.org:8443/opds", "https://reader.example.org:8443/book.epub"))
    }

    @Test fun crossHostCrossPortPlaintextAndUrlCredentialsNeverShareAuthentication() {
        val catalog = "https://reader.example.org/opds"
        listOf(
            "https://evil.example.org/book.epub",
            "https://reader.example.org.evil.example/book.epub",
            "https://reader.example.org:8443/book.epub",
            "http://reader.example.org/book.epub",
            "https://user:secret@reader.example.org/book.epub",
            "file:///sdcard/private.epub",
            "relative/book.epub",
            "garbage URL"
        ).forEach { download ->
            assertFalse("Cross-origin download must not receive credentials: $download",
                OpdsAuthOrigin.sameOrigin(catalog, download))
        }
        assertFalse(OpdsAuthOrigin.sameOrigin("http://reader.example.org/opds", "https://reader.example.org/book.epub"))
        assertFalse(OpdsAuthOrigin.sameOrigin("https://reader.example.org:8443/opds", "https://reader.example.org/book.epub"))
        assertFalse(OpdsAuthOrigin.sameOrigin("https://u:p@reader.example.org/opds", "https://reader.example.org/book.epub"))
        assertFalse(OpdsAuthOrigin.sameOrigin("http://", "https://reader.example.org/book.epub"))
    }
}
