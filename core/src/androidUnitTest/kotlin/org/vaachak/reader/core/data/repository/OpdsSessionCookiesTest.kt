package org.vaachak.reader.core.data.repository

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class OpdsSessionCookiesTest {
    private val root = "https://catalog.example/opds".toHttpUrl()

    @Test fun independentSessionsNeverShareCookies() {
        val first = OpdsSessionCookies(root.toString())
        val second = OpdsSessionCookies(root.toString())
        val cookie = requireNotNull(Cookie.parse(root, "session=fixture-a; Secure; Path=/opds"))
        first.saveFromResponse(root, listOf(cookie))
        check(first.loadForRequest("https://catalog.example/opds/folder".toHttpUrl()) == listOf(cookie))
        check(second.loadForRequest(root).isEmpty())
        check(first.loadForRequest("https://catalog.example/elsewhere".toHttpUrl()).isEmpty())
    }

    @Test fun foreignOriginCannotSetOrReceiveSessionCookie() {
        val jar = OpdsSessionCookies(root.toString())
        val cookie = requireNotNull(Cookie.parse(root, "session=fixture; Secure; Path=/"))
        val otherPort = "https://catalog.example:8443/opds".toHttpUrl()
        val otherHost = "https://other.example/opds".toHttpUrl()
        jar.saveFromResponse(otherPort, listOf(cookie))
        check(jar.loadForRequest(root).isEmpty())
        jar.saveFromResponse(root, listOf(cookie))
        check(jar.loadForRequest(otherPort).isEmpty())
        check(jar.loadForRequest(otherHost).isEmpty())
        check(jar.loadForRequest("http://catalog.example/opds".toHttpUrl()).isEmpty())
    }

    @Test fun replacementAndExpiryUseSameCookieIdentity() {
        val jar = OpdsSessionCookies(root.toString())
        fun cookie(header: String) = requireNotNull(Cookie.parse(root, header))
        jar.saveFromResponse(root, listOf(cookie("session=one; Path=/")))
        jar.saveFromResponse(root, listOf(cookie("session=two; Path=/")))
        check(jar.loadForRequest(root).single().value == "two")
        jar.saveFromResponse(root, listOf(cookie("session=gone; Max-Age=0; Path=/")))
        check(jar.loadForRequest(root).isEmpty())
    }
}
