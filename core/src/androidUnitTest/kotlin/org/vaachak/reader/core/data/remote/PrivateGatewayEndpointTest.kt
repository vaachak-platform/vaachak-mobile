package org.vaachak.reader.core.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PrivateGatewayEndpointTest {
    @Test fun acceptsPrivateTailnetGateway() {
        assertEquals("https://ai-g732.tail1f3687.ts.net:8443/v1",
            PrivateGatewayEndpoint.normalizedBase("https://ai-g732.tail1f3687.ts.net:8443/v1/"))
        assertEquals("local-chat", PrivateGatewayEndpoint.validatedModel(" local-chat "))
    }

    @Test fun refusesRawOllamaLanAndUntrustedHosts() {
        val invalid = listOf(
            "http://192.168.68.67:11434/v1",
            "https://evil.example.com:8443/v1",
            "https://ai-g732.tail1f3687.ts.net:8443/v1/chat/completions",
            "https://ai-g732.tail1f3687.ts.net:8443/v1?token=secret",
            "https://user@ai-g732.tail1f3687.ts.net:8443/v1",
            "https://ai-g732.tail1f3687.ts.net:443/v1"
        )
        invalid.forEach { address ->
            try { PrivateGatewayEndpoint.normalizedBase(address); fail("Accepted invalid URL") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun refusesMalformedModelAliases() {
        listOf("", "bad name", "../", "model/name").forEach { value ->
            try { PrivateGatewayEndpoint.validatedModel(value); fail("Accepted invalid alias") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
