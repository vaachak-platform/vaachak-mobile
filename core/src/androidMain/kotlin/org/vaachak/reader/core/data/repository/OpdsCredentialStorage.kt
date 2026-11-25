package org.vaachak.reader.core.data.repository

import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** A non-secret generation pointer; never derive the key from a URL or username. */
internal class OpdsCredentialRef(val catalogId: Long, val generation: String) {
    init {
        require(catalogId > 0) { "Invalid catalog identity" }
        require(runCatching { UUID.fromString(generation).toString() == generation }.getOrDefault(false)) {
            "Invalid credential generation"
        }
    }

    internal fun storageId(): String = "vaachak_credential_v1:OPDS:$catalogId:$generation"

    companion object {
        fun create(catalogId: Long): OpdsCredentialRef = OpdsCredentialRef(catalogId, UUID.randomUUID().toString())
    }

    override fun equals(other: Any?): Boolean = other is OpdsCredentialRef &&
        catalogId == other.catalogId && generation == other.generation

    override fun hashCode(): Int = 31 * catalogId.hashCode() + generation.hashCode()
}

/** Do not make this a data class: its generated toString would disclose credentials. */
internal class OpdsCredentialPair(val username: String, val password: String) {
    override fun toString(): String = "OpdsCredentialPair(REDACTED)"
}

internal enum class OpdsCredentialFailure {
    INVALID_INPUT, UNAVAILABLE, MISSING, INVALID_ENVELOPE, ORIGIN_CHANGED, GENERATION_CONFLICT, VERIFY_FAILED
}

/** No backend exception/cause escapes this boundary; providers can include secret data in errors. */
internal class OpdsCredentialStorageException(val reason: OpdsCredentialFailure) :
    IllegalStateException("OPDS credentials: ${reason.name.lowercase(Locale.ROOT).replace('_', ' ')}")

/** Existing authenticated Keystore storage supplies encryption and atomic file replacement. */
internal interface OpdsSecretBackend {
    /** Empty is the backend's explicit not-present result. An inaccessible file must throw. */
    fun read(id: String): String
    /** Return only after the encrypted file and directory entry are synced. */
    fun write(id: String, value: String)
    /** Sync a pre-existing generation without creating or rewriting it. */
    fun ensureDurable(id: String)
    fun clear(id: String)
}

/**
 * Storage primitive for a single, immutable credential generation.
 * The caller must journal the ref before writing, publish it only after successful
 * read-back, and retire it only after an atomic database decision proves it is unreferenced.
 * No Room writes or plaintext fallback are performed by this class.
 */
internal class OpdsCredentialStorage(private val backend: OpdsSecretBackend) {
    private companion object {
        const val MAX_ENVELOPE_BYTES = 4096 // PrivateGatewaySecrets scoped-token limit
        const val VERSION = "OPDS1"
    }

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Bind a generation to its catalog ID (backend AAD) and approved HTTPS origin (payload). */
    @Synchronized
    fun writeVerified(ref: OpdsCredentialRef, catalogUrl: String, pair: OpdsCredentialPair) {
        val origin = httpsOrigin(catalogUrl)
        val payload = encode(origin, pair)
        val id = ref.storageId()
        val existing = backendCall { backend.read(id) }
        when {
            existing.isEmpty() -> backendCall { backend.write(id, payload) }
            existing != payload -> fail(OpdsCredentialFailure.GENERATION_CONFLICT)
            // A prior rename may have succeeded before directory sync failed.
            else -> backendCall { backend.ensureDurable(id) }
        }
        val readBack = backendCall { backend.read(id) }
        if (readBack != payload) fail(OpdsCredentialFailure.VERIFY_FAILED)
    }

    /**
     * A foreign origin is anonymous without even attempting decryption. A referenced but
     * missing/locked/corrupt generation is an error, never an anonymous success or legacy fallback.
     */
    @Synchronized
    fun readForRequest(
        catalogId: Long, ref: OpdsCredentialRef, catalogUrl: String, requestedUrl: String
    ): OpdsCredentialPair? {
        if (catalogId != ref.catalogId) fail(OpdsCredentialFailure.INVALID_INPUT)
        val origin = httpsOrigin(catalogUrl)
        if (origin != httpsOrigin(requestedUrl)) return null
        val payload = backendCall { backend.read(ref.storageId()) }
        if (payload.isEmpty()) fail(OpdsCredentialFailure.MISSING)
        return decode(origin, payload)
    }

    /** Caller must prove the generation is unreferenced; never clear another record or a shared alias. */
    @Synchronized
    fun retire(ref: OpdsCredentialRef) {
        backendCall { backend.clear(ref.storageId()) }
    }

    private fun encode(origin: String, pair: OpdsCredentialPair): String {
        validatePair(pair)
        val payload = listOf(VERSION, b64(origin), b64(pair.username), b64(pair.password)).joinToString(".")
        if (payload.length > MAX_ENVELOPE_BYTES) fail(OpdsCredentialFailure.INVALID_INPUT)
        return payload
    }

    private fun decode(expectedOrigin: String, payload: String): OpdsCredentialPair {
        if (payload.length > MAX_ENVELOPE_BYTES) fail(OpdsCredentialFailure.INVALID_ENVELOPE)
        val parts = payload.split('.')
        if (parts.size != 4 || parts[0] != VERSION) fail(OpdsCredentialFailure.INVALID_ENVELOPE)
        val origin = unb64(parts[1])
        if (origin != expectedOrigin) fail(OpdsCredentialFailure.ORIGIN_CHANGED)
        val pair = OpdsCredentialPair(unb64(parts[2]), unb64(parts[3]))
        try {
            validatePair(pair)
        } catch (_: OpdsCredentialStorageException) {
            fail(OpdsCredentialFailure.INVALID_ENVELOPE)
        }
        return pair
    }

    private fun validatePair(pair: OpdsCredentialPair) {
        if (pair.username.isBlank() || pair.password.isBlank() || ':' in pair.username ||
            pair.username.any { it.isISOControl() } || pair.password.any { it.isISOControl() }
        ) fail(OpdsCredentialFailure.INVALID_INPUT)
        // Bound input before allocating/encoding an envelope. The final encoded limit is checked separately.
        if (pair.username.length > MAX_ENVELOPE_BYTES || pair.password.length > MAX_ENVELOPE_BYTES) {
            fail(OpdsCredentialFailure.INVALID_INPUT)
        }
    }

    private fun b64(value: String): String = try {
        encoder.encodeToString(value.encodeToByteArray(throwOnInvalidSequence = true))
    } catch (_: Exception) {
        fail(OpdsCredentialFailure.INVALID_INPUT)
    }

    private fun unb64(value: String): String = try {
        val bytes = decoder.decode(value)
        val decoded = bytes.decodeToString(throwOnInvalidSequence = true)
        if (b64(decoded) != value) fail(OpdsCredentialFailure.INVALID_ENVELOPE)
        decoded
    } catch (_: Exception) {
        fail(OpdsCredentialFailure.INVALID_ENVELOPE)
    }

    private fun <T> backendCall(block: () -> T): T = try {
        block()
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(OpdsCredentialFailure.UNAVAILABLE)
    }

    private fun fail(reason: OpdsCredentialFailure): Nothing = throw OpdsCredentialStorageException(reason)
}

/** Canonical origin matching includes effective port, excludes URL credentials and non-HTTPS. */
internal fun httpsOrigin(url: String): String {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        throw OpdsCredentialStorageException(OpdsCredentialFailure.INVALID_INPUT)
    }
    val host = uri.host?.lowercase(Locale.ROOT)
    val port = if (uri.port == -1) 443 else uri.port
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
        host == null || port !in 1..65535
    ) throw OpdsCredentialStorageException(OpdsCredentialFailure.INVALID_INPUT)
    return "https://$host:$port"
}
