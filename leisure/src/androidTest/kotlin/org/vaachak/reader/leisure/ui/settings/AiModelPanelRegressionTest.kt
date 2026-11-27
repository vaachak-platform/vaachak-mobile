package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.SettingsRepository
import org.vaachak.reader.core.data.repository.AiCredentialStatus
import org.vaachak.reader.core.data.repository.AiManagedSlot
import org.vaachak.reader.core.domain.model.AiConfig
import org.vaachak.reader.core.domain.model.AiTextProvider
import org.vaachak.reader.leisure.testing.ComposeTestHostActivity

/** Real Compose panels and ViewModel with synthetic repository responses; never contact providers. */
@RunWith(AndroidJUnit4::class)
class AiModelPanelRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private class Fixture {
        val model = MutableStateFlow("")
        val settings = mockk<SettingsRepository>()
        val ai = mockk<AiRepository>()
        val vm: GeminiModelViewModel
        init {
            every { settings.geminiModel } returns model
            coEvery { settings.saveGeminiModel("vault-fixture", any()) } coAnswers { model.value = secondArg(); Unit }
            coEvery { ai.discoverSavedGeminiModels() } returns AiRepository.GeminiCatalogReport(listOf("gemini-a", "gemini-b"), "Synthetic candidates")
            coEvery { ai.testSavedGemini() } returns "Synthetic text verified"
            coEvery { ai.testSavedCloudflare() } returns "Synthetic image verified"
            vm = GeminiModelViewModel(settings, ai)
        }
    }
    @Composable
    private fun Fixture.Panel(enabled: Boolean) {
        val saved by model.collectAsState()
        var draft by remember(saved) { mutableStateOf(saved) }
        GeminiModelPanel("vault-fixture", enabled, GeminiModelEditor(saved, draft, true, { draft = it }, {}), vm)
    }

    private fun button(label: String) = compose.onNodeWithText(label).performScrollTo()

    @Test fun modelDiscoverySelectionSaveAndTestUseOnlySavedModel() {
        val f = Fixture()
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { f.Panel(enabled) } }
        }
        val save = "Save Gemini model"
        val refresh = "Refresh text model candidates"
        val test = "Test saved Gemini (uses quota)"
        button(save).assertIsNotEnabled()
        button(test).assertIsNotEnabled()
        button(refresh).assertIsEnabled().performClick()
        compose.onNodeWithText("gemini-b", substring = false).performClick()
        button(save).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { f.model.value == "gemini-b" }
        compose.onNodeWithText("Saved Gemini model: gemini-b").assertExists()
        button(test).assertIsEnabled().performClick()
        compose.onNodeWithText("Synthetic text verified").assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("gemini-a")
        button(test).assertIsNotEnabled()
        compose.runOnIdle { enabled = false }
        button(refresh).assertIsNotEnabled()
        button("Choose discovered model").assertIsNotEnabled()
        compose.onNodeWithText("Synthetic text verified").assertDoesNotExist()
        coVerify(exactly = 1) { f.ai.discoverSavedGeminiModels() }
        coVerify(exactly = 1) { f.settings.saveGeminiModel("vault-fixture", "gemini-b") }
        coVerify(exactly = 1) { f.ai.testSavedGemini() }
    }

    @Test fun invalidSaveAndEmptyCatalogShowSafeGuidanceAndBusyGates() {
        val f = Fixture()
        f.model.value = "gemini-a"
        val pending = CompletableDeferred<AiRepository.GeminiCatalogReport>()
        coEvery { f.ai.discoverSavedGeminiModels() } coAnswers { pending.await() }
        coEvery { f.settings.saveGeminiModel(any(), any()) } throws IllegalStateException("synthetic sensitive error")
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { f.Panel(true) } }
        }
        compose.onNodeWithText("Saved Gemini model: gemini-a").assertExists()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("not-a-model")
        button("Save Gemini model").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("gemini-b")
        button("Save Gemini model").performClick()
        compose.onNodeWithText("Could not save model. Check vault and model ID.").assertExists()
        compose.onNodeWithText("synthetic sensitive error").assertDoesNotExist()
        button("Refresh text model candidates").performClick()
        compose.onNodeWithText("Checking Gemini…").assertExists()
        button("Refresh text model candidates").assertIsNotEnabled()
        button("Save Gemini model").assertIsNotEnabled()
        compose.runOnIdle { pending.complete(AiRepository.GeminiCatalogReport(emptyList(), "No synthetic candidates")) }
        compose.onNodeWithText("No synthetic candidates").assertExists()
        button("Choose discovered model").assertIsNotEnabled()
        coVerify(exactly = 0) { f.ai.testSavedGemini() }
    }

    @Test fun cloudflareDiagnosticBusyAndOfflineControlsNeverSendReaderContent() {
        val f = Fixture()
        var enabled by mutableStateOf(false)
        val pending = CompletableDeferred<String>()
        coEvery { f.ai.testSavedCloudflare() } coAnswers { pending.await() }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CloudflareDiagnosticPanel("vault-fixture", enabled, f.vm) } } }
        val test = "Test saved Cloudflare (uses quota)"
        button(test).assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        button(test).assertIsEnabled().performClick()
        compose.onNodeWithText("Checking Cloudflare…").assertExists()
        button(test).assertIsNotEnabled()
        compose.runOnIdle { pending.complete("Synthetic image verified") }
        compose.onNodeWithText("Synthetic image verified").assertExists()
        button(test).assertIsEnabled()
        compose.runOnIdle { enabled = false }
        button(test).assertIsNotEnabled()
        compose.onNodeWithText("Synthetic image verified").assertDoesNotExist()
        coVerify(exactly = 1) { f.ai.testSavedCloudflare() }
    }

    @Test fun formGatesRealDiagnosticPanelsOnSavedProviderEndpointAndOfflineState() {
        val f = Fixture()
        var state by mutableStateOf(SettingsUiState(aiConfig = AiConfig(textProvider = AiTextProvider.GEMINI, cloudflareUrl = "https://fixture.invalid/image")))
        var statuses by mutableStateOf(AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED })
        var provider by mutableStateOf(AiTextProvider.GEMINI)
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.key(provider) {
            AiConfigForm(state, {}, statuses, { _, _, _ -> }, {},
                provider = provider,
                geminiModelPanel = { enabled, editor -> GeminiModelPanel("vault-fixture", enabled, editor, f.vm) },
                cloudflareDiagnosticPanel = { CloudflareDiagnosticPanel("vault-fixture", it, f.vm) })
            }
        } }
        val refresh = "Refresh text model candidates"
        val image = "Test saved Cloudflare (uses quota)"
        button(refresh).assertIsEnabled()
        button(image).assertIsEnabled()
        compose.runOnIdle { state = state.copy(isOfflineMode = true) }
        button(refresh).assertIsNotEnabled(); button(image).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(isOfflineMode = false) }
        compose.onNodeWithTag("ai_config_cloudflare_url").performScrollTo().performTextReplacement("https://other.invalid/image")
        button(image).assertIsNotEnabled(); button(refresh).assertIsEnabled()
        compose.onNodeWithTag("ai_config_cloudflare_url").performScrollTo().performTextReplacement("https://fixture.invalid/image")
        button(image).assertIsEnabled()
        compose.runOnIdle { provider = AiTextProvider.PRIVATE_GATEWAY }
        compose.onNodeWithText(refresh).assertDoesNotExist()
        compose.onNodeWithText(image).assertDoesNotExist()
        compose.onNodeWithTag("ai_config_auto_save").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_internal_panel").assertExists()
        compose.runOnIdle { provider = AiTextProvider.GEMINI }
        compose.runOnIdle { statuses = statuses + (AiManagedSlot.GEMINI to AiCredentialStatus.RECOVERY_REQUIRED) }
        button(refresh).assertIsNotEnabled()
        coVerify(exactly = 0) { f.ai.discoverSavedGeminiModels() }
        coVerify(exactly = 0) { f.ai.testSavedCloudflare() }
    }

    @Test fun saveChangesSubmitsDiscoveredModelWithoutSeparateSaveAndReopenShowsSaved() {
        val f = Fixture()
        var submitted: AiConfigSaveRequest? = null
        var screen by mutableStateOf(0)
        var saved by mutableStateOf("")
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.key(screen) {
                AiConfigForm(SettingsUiState(), {}, AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED },
                    { _, _, _ -> error("Unexpected credential mutation") }, { submitted = it },
                    geminiModelPanel = { enabled, editor -> GeminiModelPanel("vault-fixture", enabled, editor, f.vm) },
                    savedGeminiModel = saved)
            }
        } }
        button("Refresh text model candidates").performClick()
        compose.onNodeWithText("gemini-b", substring = false).performClick()
        compose.onNodeWithText("Unsaved model selection — Save Changes or Save Gemini model.").assertExists()
        button("Save Changes").performClick()
        compose.runOnIdle {
            assertEquals("gemini-b", requireNotNull(submitted).geminiModel)
            assertEquals("", requireNotNull(submitted).geminiKey)
            assertEquals("", requireNotNull(submitted).cloudflareToken)
            assertEquals("", requireNotNull(submitted).gateway.token)
            saved = requireNotNull(submitted).geminiModel.orEmpty()
            screen++
        }
        compose.onNodeWithText("Saved Gemini model: gemini-b").assertExists()
        coVerify(exactly = 0) { f.settings.saveGeminiModel(any(), any()) }
        button("Save Changes").performClick()
        compose.runOnIdle { assertNull(requireNotNull(submitted).geminiModel) }
    }

    @Test fun invalidModelBlocksSaveAndVaultSwitchDiscardsDraft() {
        val f = Fixture()
        var vault by mutableStateOf("vault-a")
        var submitted: AiConfigSaveRequest? = null
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.key(vault) {
                AiConfigForm(SettingsUiState(), {}, emptyMap(), { _, _, _ -> }, { submitted = it },
                    geminiModelPanel = { enabled, editor -> GeminiModelPanel(vault, enabled, editor, f.vm) },
                    savedGeminiModel = if (vault == "vault-a") "gemini-a" else "gemini-b")
            }
        } }
        compose.onNodeWithTag("ai_config_gemini_model").performScrollTo().performTextReplacement("invalid")
        button("Save Changes").assertIsNotEnabled()
        compose.runOnIdle { vault = "vault-b" }
        compose.onNodeWithText("Saved Gemini model: gemini-b").assertExists()
        button("Save Changes").assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(requireNotNull(submitted).geminiModel) }
    }

    @Test fun providerGroupsSeparatePanelsAndDiscardOnlyUnsavedEdits() {
        val f = Fixture()
        val saved = SettingsUiState(aiConfig = AiConfig(textProvider = AiTextProvider.GEMINI,
            cloudflareUrl = "https://fixture.invalid/image", autoSaveRecaps = true,
            gatewayUrl = "https://fixture.tailnet.ts.net:8443/v1", gatewayModel = "local-chat"))
        var submitted: AiConfigSaveRequest? = null
        var provider by mutableStateOf(AiTextProvider.GEMINI)
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.key(provider) {
            AiConfigForm(saved, {}, AiManagedSlot.entries.associateWith { AiCredentialStatus.SAVED },
                { _, _, _ -> error("Unexpected credential mutation") }, { submitted = it },
                provider = provider,
                geminiModelPanel = { enabled, editor -> GeminiModelPanel("vault-fixture", enabled, editor, f.vm) },
                savedGeminiModel = "gemini-a")
            }
        } }
        compose.onNodeWithTag("ai_config_gemini_panel").assertExists()
        compose.onNodeWithTag("ai_config_cloudflare_panel").assertExists()
        compose.onNodeWithTag("ai_config_internal_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_gemini_model").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag("ai_config_cloudflare_url").performScrollTo().performTextReplacement("https://unsaved.invalid/image")
        compose.onNodeWithTag("ai_config_auto_save").performScrollTo().performClick()
        compose.onNodeWithTag("ai_credential_cloudflare_draft").performScrollTo().performTextReplacement("synthetic-unsaved")
        button("Save Changes").assertIsNotEnabled()
        compose.runOnIdle { provider = AiTextProvider.PRIVATE_GATEWAY }
        compose.onNodeWithTag("ai_config_internal_panel").assertExists()
        compose.onNodeWithTag("ai_config_gemini_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_cloudflare_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_auto_save").assertDoesNotExist()
        button("Save Changes").assertIsEnabled().performClick()
        compose.runOnIdle {
            val request = requireNotNull(submitted)
            assertEquals(AiTextProvider.PRIVATE_GATEWAY, request.gateway.provider)
            assertEquals("https://fixture.invalid/image", request.cloudflareUrl)
            assertTrue(request.autoSaveRecaps)
            assertNull(request.geminiModel)
            assertEquals("", request.cloudflareToken)
        }
        compose.onNodeWithTag("ai_config_gateway_url").performScrollTo().performTextReplacement("http://unsaved.invalid")
        compose.onNodeWithTag("ai_credential_gateway_draft").performScrollTo().performTextReplacement("synthetic-unsaved")
        compose.runOnIdle { provider = AiTextProvider.GEMINI }
        compose.onNodeWithTag("ai_config_internal_panel").assertDoesNotExist()
        compose.onNodeWithTag("ai_config_gemini_panel").assertExists()
        compose.onNodeWithTag("ai_config_cloudflare_panel").assertExists()
        compose.onNodeWithTag("ai_config_gemini_model").performScrollTo().assertTextContains("gemini-a")
        compose.onNodeWithTag("ai_config_cloudflare_url").performScrollTo().assertTextContains("https://fixture.invalid/image")
        compose.onNodeWithTag("ai_config_auto_save").performScrollTo().assertIsOn()
        button("Save Changes").assertIsEnabled().performClick()
        compose.runOnIdle {
            val request = requireNotNull(submitted)
            assertEquals(AiTextProvider.GEMINI, request.gateway.provider)
            assertEquals("https://fixture.tailnet.ts.net:8443/v1", request.gateway.url)
            assertEquals("local-chat", request.gateway.model)
            assertEquals("", request.gateway.token)
            assertNull(request.geminiModel)
        }
    }

}
