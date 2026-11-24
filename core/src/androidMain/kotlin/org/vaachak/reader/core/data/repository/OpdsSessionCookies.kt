package org.vaachak.reader.core.data.repository

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** In-memory cookies belong to one catalog opening and its exact HTTPS origin. */
internal class OpdsSessionCookies(private val catalogUrl: String) : CookieJar {
    private val values = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!OpdsAuthOrigin.sameOrigin(catalogUrl, url.toString())) return
        val now = System.currentTimeMillis()
        values.removeAll { it.expiresAt <= now }
        for (cookie in cookies) {
            // OkHttp validates Set-Cookie domain when parsing. Never share across origins.
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) values.add(cookie)
        }
        while (values.size > 128) values.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!OpdsAuthOrigin.sameOrigin(catalogUrl, url.toString())) return emptyList()
        val now = System.currentTimeMillis()
        values.removeAll { it.expiresAt <= now }
        return values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}
