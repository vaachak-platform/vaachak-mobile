package org.vaachak.reader.leisure.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.vaachak.reader.leisure.testutil.MainDispatcherRule
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.SettingsRepository

class GeminiModelViewModelRegressionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun leavingScreenCancelsViewModelDiagnostic() = runTest {
        val settings = mockk<SettingsRepository>()
        val ai = mockk<AiRepository>()
        every { settings.geminiModel } returns MutableStateFlow("")
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        coEvery { ai.testSavedGemini() } coAnswers {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally { stopped.complete(Unit) }
        }
        val vm = GeminiModelViewModel(settings, ai)
        val operation = vm.test("vault-a")
        assertFalse(operation.isActive)
        val screen = launch { operation.awaitAiOperation() }
        started.await()
        screen.cancelAndJoin()
        stopped.await()
        assertTrue(operation.isCancelled)
    }

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
        vm.save("vault-a", "gemini-flash-latest").awaitAiOperation()
        assertEquals(listOf("gemini-flash-latest"), vm.discover("vault-a").awaitAiOperation().models)
        assertEquals("text verified", vm.test("vault-a").awaitAiOperation())
        assertEquals("image verified", vm.testImage("vault-b").awaitAiOperation())
        coVerify(exactly = 1) { settings.saveGeminiModel("vault-a", "gemini-flash-latest") }
    }
}
