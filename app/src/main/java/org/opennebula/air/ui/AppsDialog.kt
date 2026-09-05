package org.opennebula.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import org.opennebula.air.browser.WebApps
import org.opennebula.air.shell.LauncherPins
import org.opennebula.air.shell.ShellApps

@Composable
fun AppsDialog(
    viewModel: GlassesViewModel,
    onClose: () -> Unit,
) {
    val pinned by viewModel.pinnedIds.collectAsStateWithLifecycle()
    val pinSet = pinned.toSet()
    val onGlasses = LauncherPins.gridOrder(pinned)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Icons", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onClose, shape = RoundedCornerShape(24.dp)) { Text("Done") }
                }
                Text(
                    "Tiles on the glasses. Catalog below — Add to put one on home, Remove to take it off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        SectionLabel("On glasses")
                    }
                    if (onGlasses.isEmpty()) {
                        item {
                            Text(
                                "None yet — add any app below.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    items(onGlasses, key = { "pin-$it" }) { component ->
                        AppRow(
                            name = appName(component),
                            detail = "On home",
                            pinned = true,
                            onToggle = { viewModel.setIconPinned(component, false) },
                        )
                    }
                    WebApps.categories().forEach { category ->
                        val apps = WebApps.catalog.filter { it.category == category }
                        item { SectionLabel(category) }
                        items(apps, key = { it.component }) { app ->
                            val on = app.component in pinSet
                            AppRow(
                                name = app.name,
                                detail = app.url.removePrefix("https://").removePrefix("www."),
                                pinned = on,
                                onToggle = { viewModel.setIconPinned(app.component, !on) },
                            )
                        }
                    }
                    item { SectionLabel("Home") }
                    items(ShellApps.catalog, key = { it.component }) { item ->
                        val on = item.component in pinSet
                        AppRow(
                            name = item.name,
                            detail = when {
                                ShellApps.isScreen(item.component) -> "Phone mirror"
                                ShellApps.isSteamVr(item.component) -> "PC SteamVR bridge"
                                ShellApps.isDesktop(item.component) -> "Windows desktop (clickable)"
                                else -> "App grid"
                            },
                            pinned = on,
                            onToggle = { viewModel.setIconPinned(item.component, !on) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun AppRow(
    name: String,
    detail: String,
    pinned: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        if (pinned) {
            OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(20.dp)) { Text("Remove") }
        } else {
            Button(onClick = onToggle, shape = RoundedCornerShape(20.dp)) { Text("Add") }
        }
    }
}

private fun appName(component: String): String {
    ShellApps.catalog.firstOrNull { it.component == component }?.let { return it.name }
    return WebApps.find(component)?.name ?: component
}
