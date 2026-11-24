package org.vaachak.reader.core.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Local-only tests; no credential fixture and no network traffic. */
class SyncEndpointPolicyTest {
    @Test fun acceptsHttpsCloudAndSelfHostedPaths() {
        assertEquals(
            "https://sync.example.org",
            SyncEndpointPolicy.normalizedBase(" https://sync.example.org/// ")
        )
        assertEquals(
            "https://my-sync.example.org:8443/vault",
            SyncEndpointPolicy.normalizedBase("my-sync.example.org:8443/vault/")
        )
        assertEquals(
            "https://192.168.1.4:8443/api/v1",
            SyncEndpointPolicy.normalizedBase("https://192.168.1.4:8443/api/v1")
        )
        assertEquals(
            "https://sync.example.org/%20safe",
            SyncEndpointPolicy.normalizedBase("https://sync.example.org/%20safe")
        )
    }

    @Test fun refusesPlaintextCredentialTransportAndMixedSchemes() {
        listOf(
            "http://sync.example.org",
            "http://192.168.1.4:8080",
            "HTTP://sync.example.org",
            "ftp://sync.example.org",
            "file:///etc/passwd",
            "https://",
            " ",
            "not a hostname",
            "https://sync.example.org/path with spaces",
            "https://user:pass@sync.example.org",
            "https://sync.example.org/?token=synthetic",
            "https://sync.example.org/#fragment",
            "https://sync.example.org/path/../private",
            "https://sync.example.org:65536"
        ).forEach { address ->
            try {
                SyncEndpointPolicy.normalizedBase(address)
                fail("Accepted unsafe endpoint: $address")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.isNotEmpty() == true)
            }
        }
    }
}
