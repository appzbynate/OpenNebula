package org.opennebula.air.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.opennebula.air.xr.RemoteDesktopUiState
import org.opennebula.air.xr.XrHost

@Composable
fun RemoteDesktopDialog(
    state: RemoteDesktopUiState,
    onSelect: (XrHost) -> Unit,
    onManualIp: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Remote Desktop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Streams your Windows desktop to a floating panel on the glasses.\n" +
                        "1) Run tools\\xr-bridge\\start_bridge.bat on the PC.\n" +
                        "2) Enter PC IP → Go.\n" +
                        "3) Aim at the panel and tap to click.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                if (state.hosts.isNotEmpty()) {
                    Text("Local PCs", style = MaterialTheme.typography.labelLarge)
                    LazyColumn {
                        items(state.hosts, key = { it.id }) { host ->
                            Text(
                                text = "${host.name}  ${host.address}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(host) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                color = if (state.selected?.id == host.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.manualIp,
                    onValueChange = onManualIp,
                    label = { Text("PC IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (state.connected) {
                OutlinedButton(onClick = onDisconnect, enabled = !state.busy) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect, enabled = !state.busy) {
                    Text(if (state.busy) "…" else "Go")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}
