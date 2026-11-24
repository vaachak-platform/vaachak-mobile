package org.vaachak.reader.core.data.repository

import org.junit.Test
import java.util.Base64

class OpdsCredentialStoragePolicyCoverageTest {
    private val backend = FakeOpdsSecretBackend()
    private val storage = OpdsCredentialStorage(backend)
    private val ref = OpdsCredentialRef.create(42)
    private val url = "https://catalog.example/opds"

    @Test fun generationIdentityRequiresPositiveIdAndCanonicalUuid() {
        check(runCatching { OpdsCredentialRef(0, ref.generation) }.isFailure)
        for (invalid in listOf("not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
            check(runCatching { OpdsCredentialRef(42, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(ref == OpdsCredentialRef(42, ref.generation))
        check(ref.hashCode() == OpdsCredentialRef(42, ref.generation).hashCode())
        check(ref != OpdsCredentialRef(43, ref.generation))
        check(ref != OpdsCredentialRef.create(42))
    }

    @Test fun canonicalButInvalidDecodedPairsFailClosed() {
        val encode = Base64.getUrlEncoder().withoutPadding()
        fun field(value: String) = encode.encodeToString(value.toByteArray(Charsets.UTF_8))
        for ((user, password) in listOf("" to "password", "reader" to "", "reader:alias" to "password",
                "reader\n" to "password", "reader" to "password\u0000")) {
            backend.values[ref.storageId()] = "OPDS1.${field(httpsOrigin(url))}.${field(user)}.${field(password)}"
            expectCredentialFailure(OpdsCredentialFailure.INVALID_ENVELOPE) { storage.readForRequest(42, ref, url, url) }
        }
    }

    @Test fun invalidPairAndOversizedInputAreRejectedBeforeBackendRead() {
        for (pair in listOf(OpdsCredentialPair("reader:", "pass"), OpdsCredentialPair("reader", "bad\u0000pass"),
                OpdsCredentialPair("x".repeat(4097), "pass"), OpdsCredentialPair("reader", "x".repeat(4097)))) {
            expectCredentialFailure(OpdsCredentialFailure.INVALID_INPUT) { storage.writeVerified(ref, url, pair) }
        }
        check(backend.reads == 0 && backend.writes == 0)
    }

    @Test fun malformedUriAndInvalidEffectivePortsFailBeforeBackendLookup() {
        for (invalid in listOf("https://[invalid", "https://catalog.example:0/opds", "https://catalog.example:65536/opds")) {
            expectCredentialFailure(OpdsCredentialFailure.INVALID_INPUT) {
                storage.readForRequest(42, ref, url, invalid)
            }
        }
        check(backend.reads == 0)
    }

    @Test fun cancellationAfterWriteDoesNotBecomeAStorageFailure() {
        backend.cancelAfterWrite = true
        val failure = runCatching { storage.writeVerified(ref, url, OpdsCredentialPair("reader", "fixture")) }.exceptionOrNull()
        check(failure is java.util.concurrent.CancellationException)
        check(backend.values.size == 1 && backend.clears == 0)
    }
}
