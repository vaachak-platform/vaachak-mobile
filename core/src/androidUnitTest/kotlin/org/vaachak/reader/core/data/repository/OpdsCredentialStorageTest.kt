package org.vaachak.reader.core.data.repository

import org.junit.Test

internal class FakeOpdsSecretBackend : OpdsSecretBackend {
    val values = mutableMapOf<String, String>()
    var reads = 0
    var writes = 0
    var clears = 0
    var durabilityChecks = 0
    var rejectDurability = false
    var rejectRead = false
    var rejectWrite = false
    var failAfterWrite = false
    var corruptWrite = false
    var cancelAfterWrite = false

    override fun read(id: String): String {
        reads++
        if (rejectRead) error("synthetic-sensitive-backend-error")
        return values[id].orEmpty()
    }

    override fun write(id: String, value: String) {
        writes++
        if (rejectWrite) error("synthetic-sensitive-backend-error")
        values[id] = if (corruptWrite) "corrupt" else value
        if (failAfterWrite) error("synthetic-sensitive-backend-error")
        if (cancelAfterWrite) throw java.util.concurrent.CancellationException("simulated cancellation")
    }

    override fun ensureDurable(id: String) {
        durabilityChecks++
        check(!rejectDurability && id in values) { "synthetic-sensitive-backend-error" }
    }

    override fun clear(id: String) {
        clears++
        values.remove(id)
    }
}

internal fun expectCredentialFailure(reason: OpdsCredentialFailure, action: () -> Unit) {
    val failure = runCatching(action).exceptionOrNull() as? OpdsCredentialStorageException
        ?: error("Expected credential storage failure")
    check(failure.reason == reason)
    check(failure.cause == null)
    check(!failure.message.orEmpty().contains("synthetic-sensitive"))
}

class OpdsCredentialStorageTest {
    private val backend = FakeOpdsSecretBackend()
    private val storage = OpdsCredentialStorage(backend)
    private val ref = OpdsCredentialRef.create(42)
    private val origin = "https://reader.example/opds"
    private val pair = OpdsCredentialPair("synthetic-reader", "synthetic-sensitive-password: 123")

    @Test fun verifiedRoundTripPreservesPairAndUnicode() {
        val unicode = OpdsCredentialPair("synthetic-réader", "synthetic-पासवर्ड: 😀")
        storage.writeVerified(ref, origin, unicode)
        val actual = checkNotNull(storage.readForRequest(42, ref, origin, "https://READER.example:443/book"))
        check(actual.username == unicode.username && actual.password == unicode.password)
        check(backend.writes == 1)
        check(!actual.toString().contains(unicode.password))
    }

    @Test fun crossOriginNeverReadsBackendEvenWhenLocked() {
        storage.writeVerified(ref, origin, pair)
        backend.rejectRead = true
        val before = backend.reads
        check(storage.readForRequest(42, ref, origin, "https://other.example/book") == null)
        check(storage.readForRequest(42, ref, origin, "https://reader.example:8443/book") == null)
        check(backend.reads == before)
    }

    @Test fun mismatchedCatalogIdNeverReadsBackend() {
        expectCredentialFailure(OpdsCredentialFailure.INVALID_INPUT) {
            storage.readForRequest(43, ref, origin, origin)
        }
        check(backend.reads == 0)
    }

    @Test fun rejectsHttpUserInfoAndInvalidUrl() {
        listOf("http://reader.example/opds", "https://user:secret@reader.example/opds", "invalid").forEach { url ->
            expectCredentialFailure(OpdsCredentialFailure.INVALID_INPUT) { storage.writeVerified(ref, url, pair) }
        }
        check(backend.writes == 0)
    }

    @Test fun preventsReuseAfterOriginChange() {
        storage.writeVerified(ref, origin, pair)
        expectCredentialFailure(OpdsCredentialFailure.ORIGIN_CHANGED) {
            storage.readForRequest(42, ref, "https://changed.example/opds", "https://changed.example/book")
        }
    }

    @Test fun retriesIdenticalGenerationWithoutOverwriting() {
        storage.writeVerified(ref, origin, pair)
        storage.writeVerified(ref, origin, pair)
        check(backend.writes == 1)
        check(backend.durabilityChecks == 1)
    }

    @Test fun interruptedRenameRetryRequiresDurabilityBeforeSuccess() {
        backend.failAfterWrite = true
        expectCredentialFailure(OpdsCredentialFailure.UNAVAILABLE) { storage.writeVerified(ref, origin, pair) }
        backend.failAfterWrite = false
        backend.rejectDurability = true
        expectCredentialFailure(OpdsCredentialFailure.UNAVAILABLE) { storage.writeVerified(ref, origin, pair) }
        backend.rejectDurability = false
        storage.writeVerified(ref, origin, pair)
        check(backend.durabilityChecks == 2 && backend.writes == 1)
        check(storage.readForRequest(42, ref, origin, origin)?.password == pair.password)
    }

    @Test fun generationConflictPreservesExistingValue() {
        storage.writeVerified(ref, origin, pair)
        expectCredentialFailure(OpdsCredentialFailure.GENERATION_CONFLICT) {
            storage.writeVerified(ref, origin, OpdsCredentialPair("other", "other-secret"))
        }
        check(storage.readForRequest(42, ref, origin, origin)?.password == pair.password)
    }

    @Test fun missingReferenceDoesNotBecomeAnonymous() {
        expectCredentialFailure(OpdsCredentialFailure.MISSING) { storage.readForRequest(42, ref, origin, origin) }
    }

    @Test fun lockedReferenceFailsWithoutSecretDiagnostics() {
        storage.writeVerified(ref, origin, pair)
        backend.rejectRead = true
        expectCredentialFailure(OpdsCredentialFailure.UNAVAILABLE) { storage.readForRequest(42, ref, origin, origin) }
    }

    @Test fun failedReadBackIsNotSuccess() {
        backend.corruptWrite = true
        expectCredentialFailure(OpdsCredentialFailure.VERIFY_FAILED) { storage.writeVerified(ref, origin, pair) }
    }

    @Test fun malformedEnvelopeFailsClosed() {
        backend.values[ref.storageId()] = "OPDS1.invalid.invalid.invalid"
        val failure = runCatching { storage.readForRequest(42, ref, origin, origin) }.exceptionOrNull()
        check(failure is OpdsCredentialStorageException)
    }

    @Test fun duplicateUrlsUseIndependentCatalogAndGenerationKeys() {
        val second = OpdsCredentialRef.create(43)
        val replacement = OpdsCredentialRef.create(42)
        storage.writeVerified(ref, origin, pair)
        storage.writeVerified(second, origin, OpdsCredentialPair("second", "second-secret"))
        storage.writeVerified(replacement, origin, OpdsCredentialPair("replacement", "replacement-secret"))
        storage.retire(ref)
        check(storage.readForRequest(43, second, origin, origin)?.password == "second-secret")
        check(storage.readForRequest(42, replacement, origin, origin)?.password == "replacement-secret")
        check(backend.clears == 1)
    }

    @Test fun invalidOrOversizedPairIsRejectedBeforeWriting() {
        listOf(OpdsCredentialPair("", "password"), OpdsCredentialPair("reader", ""),
            OpdsCredentialPair("name:bad", "password"), OpdsCredentialPair("reader", "bad\npassword"),
            OpdsCredentialPair("reader", "a".repeat(4097)), OpdsCredentialPair("reader", "\uD800")).forEach {
            expectCredentialFailure(OpdsCredentialFailure.INVALID_INPUT) { storage.writeVerified(ref, origin, it) }
        }
        check(backend.writes == 0)
    }
}
