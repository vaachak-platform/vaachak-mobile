package org.vaachak.reader.core.data.repository

import org.vaachak.reader.core.domain.model.OpdsEntity
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** One browser opening. No URL lookup, no plaintext credential fields, no persistent token. */
class OpdsCatalogSession(feed: OpdsEntity) {
    val catalogId: Long = feed.id
    val catalogUrl: String = feed.url
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val credentialStamp = stamp(feed)

    /** A changed credential revision or URL requires a new browser opening. */
    internal fun matches(row: OpdsEntity): Boolean = catalogId > 0 &&
        row.id == catalogId && row.url == catalogUrl &&
        MessageDigest.isEqual(credentialStamp, stamp(row))

    private fun stamp(row: OpdsEntity): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        listOf(row.username, row.password, row.credentialGeneration).forEach { value ->
            // Frame null, empty and nonempty fields unambiguously.
            val bytes = value?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) {
                digest.update(bytes)
                bytes.fill(0)
            }
        }
        return digest.digest()
    }

    override fun toString(): String = "OpdsCatalogSession(REDACTED)"
}

/** Transitional request resolver. Never guesses a catalog from a navigation URL. */
internal suspend fun resolveLegacyCatalogCredentials(
    session: OpdsCatalogSession,
    requestedUrl: String,
    load: suspend (Long) -> OpdsEntity?
): OpdsCredentialPair? {
    check(session.catalogId > 0) { "Catalog identity unavailable; reopen the catalog" }
    val origin = httpsOrigin(session.catalogUrl)
    val destination = httpsOrigin(requestedUrl)
    val row = load(session.catalogId)
    check(row != null && session.matches(row)) { "Catalog changed; reopen the catalog" }
    if (origin != destination) return null
    // Do not activate encrypted generations until journal-aware CRUD and editor are ready.
    check(row.credentialGeneration == null) { "Secure catalog credentials are not available in this build" }
    if (row.username == null && row.password == null) return null
    val username = row.username
    val password = row.password
    check(!username.isNullOrBlank() && !password.isNullOrBlank()) { "Catalog credentials incomplete" }
    return OpdsCredentialPair(username, password)
}
