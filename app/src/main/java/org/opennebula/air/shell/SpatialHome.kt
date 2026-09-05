package org.opennebula.air.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.opennebula.air.ui.GlassesViewModel
import org.opennebula.air.usb.GlassesConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpatialHome(viewModel: GlassesViewModel) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val display by viewModel.displayStatus.collectAsStateWithLifecycle()
    val note by viewModel.noteText.collectAsStateWithLifecycle()
    val markers by viewModel.showMarkers.collectAsStateWithLifecycle()
    val worldLocked by viewModel.worldLocked.collectAsStateWithLifecycle()
    val pose by viewModel.orientation.collectAsStateWithLifecycle()
    val stereo by viewModel.stereo.collectAsStateWithLifecycle()
    val distance by viewModel.distance.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val clock = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val seconds = remember { SimpleDateFormat("ss", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    val hz = (state as? GlassesConnectionState.Streaming)?.packetsPerSec
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("OpenNebula", style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            clock.format(Date(now)),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 72.sp),
                        )
                        Text(
                            seconds.format(Date(now)),
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 28.dp),
                        )
                    }
                    Text(date.format(Date(now)), fontSize = 22.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusCard(
                        if (hz != null) "IMU $hz Hz" else statusLabel(state),
                    )
                    StatusCard(display.summary)
                    StatusCard(
                        "screen ${"%.2f".format(distance)} m  " +
                            if (stereo) "stereo SBS" else "mono",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = viewModel::recenter) {
                    Text(if (worldLocked) "Recenter" else "Anchor", fontSize = 20.sp)
                }
                OutlinedButton(onClick = { viewModel.startCalibration(false) }) {
                    Text("Calibrate", fontSize = 18.sp)
                }
                OutlinedButton(onClick = viewModel::toggleMarkers) {
                    Text(if (markers) "Hide markers" else "Show markers", fontSize = 18.sp)
                }
                OutlinedButton(onClick = { viewModel.setStereo(!stereo) }) {
                    Text(if (stereo) "Stereo SBS" else "Mono", fontSize = 18.sp)
                }
            }
            Text(
                "YAW ${"%.0f".format(pose.yawDeg)}   PITCH ${"%.0f".format(pose.pitchDeg)}   ROLL ${"%.0f".format(pose.rollDeg)}",
                fontSize = 36.sp,
                lineHeight = 42.sp,
            )
            Text(
                if (display.presenting) {
                    if (worldLocked) "World locked — use Calibrate if icons crawl after sitting still."
                    else "Open Calibrate on the phone — hold still, then Anchor."
                } else {
                    "NOT presenting on glasses — you may be seeing a phone mirror. IMU cannot plant that."
                },
                fontSize = 20.sp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                Text("Note", color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    note.ifBlank { "Type on the phone. Anchor plants this panel in the room." },
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(text: String) {
    Text(
        text,
        fontSize = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun statusLabel(state: GlassesConnectionState): String = when (state) {
    GlassesConnectionState.Disconnected -> "Glasses disconnected"
    GlassesConnectionState.Connecting -> "Connecting…"
    GlassesConnectionState.AwaitingPermission -> "Waiting for USB permission"
    is GlassesConnectionState.Streaming -> "IMU ${state.packetsPerSec} Hz"
}
