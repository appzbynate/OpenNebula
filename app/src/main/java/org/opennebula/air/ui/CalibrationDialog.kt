package org.opennebula.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalibrationDialog(
    state: CalibrationUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    if (state.phase == CalibrationPhase.Hidden) return
    AlertDialog(
        onDismissRequest = {
            if (state.phase == CalibrationPhase.Done || state.phase == CalibrationPhase.Failed) {
                onDismiss()
            }
        },
        title = {
            Text(
                when (state.phase) {
                    CalibrationPhase.HoldStill -> "Calibrate — hold still"
                    CalibrationPhase.Anchoring -> "Anchoring…"
                    CalibrationPhase.Done -> "World locked"
                    CalibrationPhase.Failed -> "Calibration paused"
                    CalibrationPhase.Hidden -> ""
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    state.message.ifBlank {
                        when (state.phase) {
                            CalibrationPhase.HoldStill ->
                                "Keep your head and the glasses still for ~1.5 s. " +
                                    "Progress only moves while you’re quiet."
                            CalibrationPhase.Anchoring ->
                                "Look forward. Planting the world lock…"
                            CalibrationPhase.Done ->
                                "Bias set and Anchored. If icons crawl later, tap Calibrate again."
                            CalibrationPhase.Failed ->
                                "Too much motion — hold still and retry."
                            else -> ""
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.phase == CalibrationPhase.HoldStill) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(state.progress * 100).toInt()}%  ·  motion ${"%.1f".format(state.rateEmaDps)} °/s",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    CalibrationPhase.Done -> {
                        Button(onClick = onFinish) { Text("Done") }
                    }
                    CalibrationPhase.Failed -> {
                        Button(onClick = onRetry) { Text("Retry") }
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    CalibrationPhase.HoldStill, CalibrationPhase.Anchoring -> {
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    else -> {}
                }
            }
        },
    )
}
