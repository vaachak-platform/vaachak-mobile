package org.vaachak.reader.leisure.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.SettingsRepository

class GeminiModelViewModelRegressionTest {
    @Test fun modelAndDiagnosticsAreScopedToExplicitVault() = runTest {
        val settings = mockk<SettingsRepository>()
        val ai = mockk<AiRepository>()
        val model = MutableStateFlow("gemini-flash-latest")
        every { settings.geminiModel } returns model
        coEvery { settings.saveGeminiModel("vault-a", "gemini-flash-latest") } returns Unit
        coEvery { ai.discoverSavedGeminiModels() } coAnswers {
            assertEquals("vault-a", currentCoroutineContext()[AiVaultScope]?.vaultId)
            AiRepository.GeminiCatalogReport(listOf("gemini-flash-latest"), "synthetic catalog")
        }
        coEvery { ai.testSavedGemini() } coAnswers {
            assertEquals("vault-a", currentCoroutineContext()[AiVaultScope]?.vaultId); "text verified"
        }
        coEvery { ai.testSavedCloudflare() } coAnswers {
            assertEquals("vault-b", currentCoroutineContext()[AiVaultScope]?.vaultId); "image verified"
        }
        val vm = GeminiModelViewModel(settings, ai)
        assertEquals("gemini-flash-latest", vm.savedModel.first())
        vm.save("vault-a", "gemini-flash-latest")
        assertEquals(listOf("gemini-flash-latest"), vm.discover("vault-a").models)
        assertEquals("text verified", vm.test("vault-a"))
        assertEquals("image verified", vm.testImage("vault-b"))
        coVerify(exactly = 1) { settings.saveGeminiModel("vault-a", "gemini-flash-latest") }
    }
}
