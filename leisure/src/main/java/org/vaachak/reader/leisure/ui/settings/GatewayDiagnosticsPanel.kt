package org.vaachak.reader.leisure.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import org.vaachak.reader.core.data.repository.GatewayDiagnosticReport
import org.vaachak.reader.leisure.ui.testability.tid

/** No persistence, no automatic retry; changing configuration removes the previous evidence. */
@Composable
internal fun GatewayDiagnosticsPanel(enabled: Boolean, onTest: suspend () -> GatewayDiagnosticReport) {
    var requested by remember(enabled) { mutableStateOf(false) }
    var message by remember(enabled) { mutableStateOf("Not tested in this screen.") }
    LaunchedEffect(enabled, requested) {
        if (enabled && requested) {
            message = "Checking saved gateway model catalog…"
            message = try {
                onTest().message
            } catch (cancelled: CancellationException) {
                message = "Check cancelled. Retry explicitly."
                throw cancelled
            } finally {
                requested = false
            }
        }
    }
    Button(
        enabled = enabled && !requested,
        onClick = { requested = true },
        modifier = Modifier.fillMaxWidth().tid("ai_config_gateway_test")
    ) {
        Text("Test Connection")
    }
    Text(
        if (enabled) message else "Save Gateway settings and disable Offline Mode before testing.",
        modifier = Modifier.tid("ai_config_gateway_test_status")
    )
    Text("Checks the optional model catalog only. No reading text is sent. Not an inference test.")
}
