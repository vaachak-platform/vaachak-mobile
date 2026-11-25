package org.vaachak.reader.leisure.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withContext
import org.vaachak.reader.core.data.repository.AiRepository
import org.vaachak.reader.core.data.repository.AiVaultScope
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport
import javax.inject.Inject

/** The Compose caller owns the diagnostic job; leaving the screen cancels it. */
@HiltViewModel
class GatewayDiagnosticsViewModel @Inject constructor(private val repository: AiRepository) : ViewModel() {
    suspend fun check(vaultId: String): GatewayDiagnosticReport = withContext(AiVaultScope(vaultId)) {
        repository.testSavedGateway()
    }
}
