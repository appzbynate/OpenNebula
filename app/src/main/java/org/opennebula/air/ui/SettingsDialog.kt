package org.opennebula.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opennebula.air.render.StereoConfig
import org.opennebula.air.shell.SpatialWindow

@Composable
fun SettingsDialog(
    viewModel: GlassesViewModel,
    onClose: () -> Unit,
) {
    val stereo by viewModel.stereo.collectAsStateWithLifecycle()
    val distance by viewModel.distance.collectAsStateWithLifecycle()
    val panelHeight by viewModel.panelHeight.collectAsStateWithLifecycle()
    val panelScale by viewModel.panelScale.collectAsStateWithLifecycle()
    val offsetX by viewModel.offsetX.collectAsStateWithLifecycle()
    val ipd by viewModel.ipd.collectAsStateWithLifecycle()
    val moveScreen by viewModel.moveScreen.collectAsStateWithLifecycle()
    val markers by viewModel.showMarkers.collectAsStateWithLifecycle()
    val pose by viewModel.orientation.collectAsStateWithLifecycle()
    val sample by viewModel.latestSample.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onClose) { Text("Done") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = stereo, onCheckedChange = viewModel::setStereo)
                    Text(
                        if (stereo) "Stereo SBS — OpenNebula sets hardware 3D automatically."
                        else "Mono — single camera",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                val brightness by viewModel.brightness.collectAsStateWithLifecycle()
                Text("Brightness $brightness / 7", fontSize = 13.sp)
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { viewModel.setBrightness(it.toInt()) },
                    valueRange = 0f..7f,
                    steps = 6,
                )
                if (stereo) {
                    Text("IPD ${"%.0f".format(ipd * 1000f)} mm", fontSize = 13.sp)
                    Slider(
                        value = ipd,
                        onValueChange = viewModel::setIpd,
                        valueRange = StereoConfig.IPD_MIN..StereoConfig.IPD_MAX,
                    )
                }
                Text("Distance ${"%.2f".format(distance)} m", fontSize = 13.sp)
                Slider(
                    value = distance,
                    onValueChange = viewModel::setDistance,
                    valueRange = SpatialWindow.DIST_MIN..SpatialWindow.DIST_MAX,
                )
                Text("Height ${"%+.2f".format(panelHeight)} m", fontSize = 13.sp)
                Slider(
                    value = panelHeight,
                    onValueChange = viewModel::setPanelHeight,
                    valueRange = SpatialWindow.HEIGHT_MIN..SpatialWindow.HEIGHT_MAX,
                )
                Text("Offset ${"%+.2f".format(offsetX)} m", fontSize = 13.sp)
                Slider(
                    value = offsetX,
                    onValueChange = viewModel::setOffsetX,
                    valueRange = SpatialWindow.OFFSET_MIN..SpatialWindow.OFFSET_MAX,
                )
                Text("Size ${"%.0f".format(panelScale * 100f)}%", fontSize = 13.sp)
                Slider(
                    value = panelScale,
                    onValueChange = viewModel::setPanelScale,
                    valueRange = SpatialWindow.SCALE_MIN..SpatialWindow.SCALE_MAX,
                )
                OutlinedButton(onClick = viewModel::resetWindow) { Text("Reset screen") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = moveScreen, onCheckedChange = viewModel::setMoveScreen)
                    Text(
                        if (moveScreen) "Move screen — drag the trackpad to place, two fingers = distance."
                        else "Trackpad moves the cursor.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = markers, onCheckedChange = { viewModel.toggleMarkers() })
                    Text(
                        if (markers) "Depth cubes on"
                        else "Depth cubes off",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "yaw ${"%.1f".format(pose.yawDeg)}°   " +
                        "pitch ${"%.1f".format(pose.pitchDeg)}°   " +
                        "roll ${"%.1f".format(pose.rollDeg)}°",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                val imu = sample
                if (imu != null) {
                    Text(
                        "gyro ${imu.gyroDps} °/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
