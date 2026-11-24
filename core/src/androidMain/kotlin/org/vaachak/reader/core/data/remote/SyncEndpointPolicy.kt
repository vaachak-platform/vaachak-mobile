package org.vaachak.reader.core.data.remote

import java.net.URI
import java.util.Locale

/**
 * Credential-bearing sync endpoints must use TLS with normal certificate validation.
 * Host-only URLs retain the previous `https://` convenience, but explicit HTTP,
 * URL user-info, queries and fragments are never accepted.
 *
 * For a local Calibre/sync host, configure valid HTTPS first. Do not silently
 * downgrade to HTTP or disable the platform trust manager.
 */
internal object SyncEndpointPolicy {
    fun normalizedBase(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Enter a secure sync server URL" }
        require(trimmed.none(Char::isWhitespace)) { "Sync URL contains whitespace" }

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid sync server URL")
        }

        require(parsed.scheme?.lowercase(Locale.ROOT) == "https") {
            "Sync requires HTTPS; configure a valid TLS certificate on the server"
        }
        require(!parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null &&
            (parsed.port == -1 || parsed.port in 1..65535)) {
            "Invalid sync server URL; do not include a password, query or fragment"
        }
        require(parsed.rawPath?.split('/')?.none { it == "." || it == ".." } != false) {
            "Sync server URL must not contain traversal segments"
        }
        // Normalize scheme case only. Preserving the raw path avoids re-encoding
        // percent-escaped base paths used by self-hosted reverse proxies.
        return ("https" + candidate.substring(parsed.scheme!!.length)).trimEnd('/')
    }
}
