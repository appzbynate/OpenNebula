package org.opennebula.air.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opennebula.air.ui.GlassesViewModel

@Composable
fun SpatialNotes(viewModel: GlassesViewModel) {
    val note by viewModel.noteText.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Notes", style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp))
            Text(
                "Type on the phone. This panel is a second world-locked window.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                note.ifBlank { "Empty — open Notes on the phone and type." },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                fontSize = 28.sp,
                lineHeight = 36.sp,
            )
        }
    }
}
