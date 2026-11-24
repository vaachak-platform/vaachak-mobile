package org.vaachak.reader.core.data.repository

import java.net.URI
import java.util.Locale

/**
 * OPDS credentials belong to one HTTPS origin, not to arbitrary download links in a feed.
 * Both the scheme and the effective TCP port must match. Hostname equality alone is
 * insufficient (a different port may be a different service or tenant).
 */
internal object OpdsAuthOrigin {
    fun sameOrigin(catalogUrl: String, requestedUrl: String): Boolean {
        val catalog = runCatching { URI(catalogUrl) }.getOrNull() ?: return false
        val requested = runCatching { URI(requestedUrl) }.getOrNull() ?: return false
        if (!catalog.scheme.equals("https", ignoreCase = true) ||
            !requested.scheme.equals("https", ignoreCase = true) ||
            catalog.rawUserInfo != null || requested.rawUserInfo != null
        ) return false
        val catalogHost = catalog.host?.lowercase(Locale.ROOT) ?: return false
        val requestedHost = requested.host?.lowercase(Locale.ROOT) ?: return false
        if (catalogHost != requestedHost) return false
        fun effectivePort(port: Int): Int = if (port == -1) 443 else port
        val catalogPort = effectivePort(catalog.port)
        val requestedPort = effectivePort(requested.port)
        return catalogPort in 1..65535 && catalogPort == requestedPort
    }
}
