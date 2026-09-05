package org.opennebula.air.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opennebula.air.xr.SteamVrUiState
import org.opennebula.air.xr.XrHost

@Composable
fun SteamVrDialog(
    state: SteamVrUiState,
    onSelect: (XrHost) -> Unit,
    onManualIp: (String) -> Unit,
    onHeight: (Float) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("SteamVR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SteamVR Home needs Desktop Mirror on the PC (Status window is not the home).\n" +
                        "1) Start SteamVR Home.\n" +
                        "2) SteamVR Settings → Desktop → enable Mirror.\n" +
                        "3) Run start_bridge.bat — it should say capturing 'Mirror…'.\n" +
                        "4) Use a 192.168.x IP → Go (fullscreen on glasses).",
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
                Text("HMD height  ${"%.2f".format(state.heightM)} m")
                Slider(
                    value = state.heightM,
                    onValueChange = onHeight,
                    valueRange = 1.0f..2.2f,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                Button(onClick = onConnect, enabled = !state.busy) {
                    Text(if (state.busy) "Connecting…" else if (state.connected) "Reconnect" else "Go")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}
