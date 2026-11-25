package org.vaachak.reader.leisure.ui.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.GatewayDiagnostic
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport

class GatewayDiagnosticsViewModelTest {
    @Test fun diagnosticsPassExactVaultAndPreserveNarrowReport() = runTest {
        val repository = mockk<AiRepository>()
        val vm = GatewayDiagnosticsViewModel(repository)
        coEvery { repository.testSavedGateway() } coAnswers {
            assertEquals("vault-a", currentCoroutineContext()[AiVaultScope]?.vaultId)
            GatewayDiagnosticReport(GatewayDiagnostic.UNSUPPORTED)
        }
        assertEquals(GatewayDiagnostic.UNSUPPORTED, vm.check("vault-a").status)
        coVerify(exactly = 1) { repository.testSavedGateway() }
    }

    @Test fun cancellationFromScreenReachesRepositoryWithoutRetry() = runTest {
        val repository = mockk<AiRepository>()
        val vm = GatewayDiagnosticsViewModel(repository)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        coEvery { repository.testSavedGateway() } coAnswers {
            assertEquals("vault-b", currentCoroutineContext()[AiVaultScope]?.vaultId)
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val task = async { vm.check("vault-b") }
        started.await()
        task.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertTrue(task.isCancelled)
        coVerify(exactly = 1) { repository.testSavedGateway() }
    }
}
