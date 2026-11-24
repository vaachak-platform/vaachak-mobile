package org.vaachak.reader.core.data.repository

import org.vaachak.reader.core.domain.model.OpdsEntity
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** One browser opening. No URL lookup, no plaintext credential fields, no persistent token. */
class OpdsCatalogSession(feed: OpdsEntity) {
    val catalogId: Long = feed.id
    val catalogUrl: String = feed.url
    private val catalogTitle = feed.title
    private val predefined = feed.isPredefined
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val credentialStamp = stamp(feed)

    /** A changed credential revision or URL requires a new browser opening. */
    internal fun matches(row: OpdsEntity): Boolean = catalogId > 0 &&
        row.id == catalogId && row.url == catalogUrl &&
        MessageDigest.isEqual(credentialStamp, stamp(row))

    internal fun matchesEditor(row: OpdsEntity): Boolean =
        matches(row) && catalogTitle == row.title && predefined == row.isPredefined

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
