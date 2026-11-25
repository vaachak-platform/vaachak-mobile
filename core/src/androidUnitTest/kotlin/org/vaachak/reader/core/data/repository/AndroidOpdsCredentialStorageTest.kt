package org.vaachak.reader.core.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/** Exercises the real Android adapter, with a synthetic backend in place of Android Keystore. */
class AndroidOpdsCredentialStorageTest {
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val values = mutableMapOf<String, String>()
    private val storage = AndroidOpdsCredentialStorage(secrets).storage
    private val ref = OpdsCredentialRef.create(42)
    private val url = "https://catalog.example/opds"
    private val pair = OpdsCredentialPair("fixture-user", "fixture-password")

    private fun stubBackend() {
        every { secrets.read(any()) } answers { values[firstArg<String>()].orEmpty() }
        every { secrets.write(any(), any()) } answers {
            values[firstArg<String>()] = secondArg<String>()
        }
        every { secrets.ensureDurable(any()) } answers { check(firstArg<String>() in values) }
        every { secrets.clear(any()) } answers { values.remove(firstArg<String>()); Unit }
    }

    @Test fun generationWriteReadRetryAndRetirementUseSameScopedStorageId() {
        stubBackend()
        val key = ref.storageId()
        storage.writeVerified(ref, url, pair)
        storage.writeVerified(ref, url, pair)
        check(storage.readForRequest(42, ref, url, "$url/folder")?.password == pair.password)
        verify(exactly = 1) { secrets.write(key, any()) }
        verify(exactly = 1) { secrets.ensureDurable(key) }
        // This fixture has no DB reference; retiring its generation is intentional.
        storage.retire(ref)
        verify(exactly = 1) { secrets.clear(key) }
        check(values.isEmpty())
        expectCredentialFailure(OpdsCredentialFailure.MISSING) { storage.readForRequest(42, ref, url, url) }
    }

    @Test fun backendDurabilityFailureIsNotReportedAsSuccessfulWrite() {
        stubBackend()
        storage.writeVerified(ref, url, pair)
        every { secrets.ensureDurable(ref.storageId()) } throws IllegalStateException("synthetic-sensitive-backend-error")
        expectCredentialFailure(OpdsCredentialFailure.UNAVAILABLE) { storage.writeVerified(ref, url, pair) }
        verify(exactly = 1) { secrets.write(ref.storageId(), any()) }
        verify(exactly = 0) { secrets.clear(any()) }
        every { secrets.ensureDurable(ref.storageId()) } answers { Unit }
        storage.writeVerified(ref, url, pair)
        check(storage.readForRequest(42, ref, url, url)?.username == pair.username)
    }
}
