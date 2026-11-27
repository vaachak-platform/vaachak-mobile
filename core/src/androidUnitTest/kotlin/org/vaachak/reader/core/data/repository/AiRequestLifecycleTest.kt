package org.vaachak.reader.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import org.vaachak.reader.core.data.remote.GatewayHttpException
import org.vaachak.reader.core.data.remote.GatewayProbeResult
import org.vaachak.reader.core.data.remote.PrivateGatewayClient
import org.vaachak.reader.core.domain.model.AiTextProvider
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AiRequestLifecycleTest {
    private val identity = MutableStateFlow("d88_${UUID.randomUUID()}")
    private val vault = mockk<VaultRepository>()
    private val secrets = mockk<PrivateGatewaySecrets>()
    private val tokens = mutableMapOf<String, String>()
    private val base = "https://reader.example.ts.net:8443/v1"
    private val settings: SettingsRepository

    private val ai = AiCredentialRuntimeFixture()

    init {
        every { vault.activeVaultId } returns identity
        every { vault.persistedVaultIdentity } returns identity
        every { secrets.write(any(), any()) } answers { tokens[firstArg()] = secondArg(); Unit }
        every { secrets.read(any()) } answers { tokens[firstArg()].orEmpty() }
        every { secrets.clear(any()) } answers { tokens.remove(firstArg()); Unit }
        configureAiVault(vault, identity)
        configureLegacyAiStorage(secrets, tokens)
        settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>(), vault, secrets, ai.runtime)
    }

    private suspend fun configureGateway(token: String) {
        settings.setOfflineMode(false)
        settings.setAiTextProvider(AiTextProvider.PRIVATE_GATEWAY)
        settings.savePrivateGatewaySettings(base, "local-chat", token)
    }

    @Test fun pinnedVaultConfigurationNeverReadsAnotherVaultCredential() = runBlocking {
        val old = identity.value
        configureGateway("first-token")
        val first = settings.loadAiRequest(AiRequestKind.TEXT, old)
        assertEquals("first-token", first.credential)
        identity.value = "d88_${UUID.randomUUID()}"
        configureGateway("second-token")
        assertTrue(first.invalidated.first())
        try {
            settings.loadAiRequest(AiRequestKind.TEXT, old)
            fail("Expected vault mismatch cancellation")
        } catch (_: CancellationException) {
            // New vault credentials must not be selected for an old reading context.
        }
        val second = settings.loadAiRequest(AiRequestKind.TEXT, identity.value)
        assertEquals("second-token", second.credential)
        assertFalse(second.invalidated.first())
        identity.value = old
        assertEquals("first-token", settings.loadAiRequest(AiRequestKind.TEXT, old).credential)
    }

    @Test fun offlineAndCredentialRevisionInvalidateAnExistingSnapshot() = runBlocking {
        configureGateway("original")
        val before = settings.loadAiRequest(AiRequestKind.TEXT, identity.value)
        settings.savePrivateGatewayToken("replacement")
        assertTrue(before.invalidated.first())
        val next = settings.loadAiRequest(AiRequestKind.TEXT, identity.value)
        assertEquals("replacement", next.credential)
        settings.setOfflineMode(true)
        assertTrue(next.invalidated.first())
        try {
            settings.loadAiRequest(AiRequestKind.TEXT, identity.value)
            fail("Offline mode must prevent configuration load")
        } catch (_: AiOfflineException) {
            // Expected.
        }
    }

    @Test fun invalidationCancelsActiveWorkAndDoesNotPublishResult() = runBlocking {
        val invalid = MutableStateFlow(false)
        val config = AiRequestConfig(AiTextProvider.PRIVATE_GATEWAY, base, "local-chat", "token", invalid)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val request = async {
            config.runGuarded {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        withTimeout(5_000) { started.await() }
        invalid.value = true
        try {
            withTimeout(5_000) { request.await() }
            fail("Cancelled request returned a result")
        } catch (_: CancellationException) {
            assertTrue(request.isCancelled)
        }
        withTimeout(5_000) { stopped.await() }
    }

    @Test fun diagnosticReportsCatalogOnlyAndNeverCallsInferenceOrCloudflare() = runBlocking {
        configureGateway("synthetic-key")
        val gateway = mockk<PrivateGatewayClient>()
        val cloudflare = mockk<CloudflareAiApi>()
        val repository = AiRepository(settings, cloudflare, gateway, mockk<org.vaachak.reader.core.data.remote.GeminiRestClient>())
        val outcomes = mapOf(
            GatewayProbeResult.MODEL_LISTED to GatewayDiagnostic.MODEL_LISTED,
            GatewayProbeResult.MODEL_NOT_LISTED to GatewayDiagnostic.MODEL_NOT_LISTED,
            GatewayProbeResult.CATALOG_UNSUPPORTED to GatewayDiagnostic.UNSUPPORTED,
            GatewayProbeResult.CATALOG_UNRECOGNIZED to GatewayDiagnostic.UNRECOGNIZED
        )
        for ((probe, expected) in outcomes) {
            coEvery { gateway.probe(base, "local-chat", "synthetic-key") } returns probe
            val result = withContext(AiVaultScope(identity.value)) { repository.testSavedGateway() }
            assertEquals(expected, result.status)
            assertFalse(result.message.contains("synthetic-key"))
        }
        coEvery { gateway.probe(any(), any(), any()) } throws GatewayHttpException(401)
        assertEquals(GatewayDiagnostic.FAILED, repository.testSavedGateway().status)
        assertTrue(repository.testSavedGateway().message.contains("401"))
        settings.setAiTextProvider(AiTextProvider.GEMINI)
        assertEquals(GatewayDiagnostic.NOT_SELECTED, repository.testSavedGateway().status)
        settings.setOfflineMode(true)
        assertEquals(GatewayDiagnostic.OFFLINE, repository.testSavedGateway().status)
        coVerify(exactly = 6) { gateway.probe(any(), any(), any()) }
        coVerify(exactly = 0) { gateway.chat(any(), any(), any(), any()) }
        coVerify(exactly = 0) { cloudflare.generateImage(any(), any(), any()) }
    }
}
