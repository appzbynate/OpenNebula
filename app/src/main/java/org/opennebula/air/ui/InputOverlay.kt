package org.opennebula.air.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.opennebula.air.input.InputMode

@Composable
fun InputOverlay(
    mode: InputMode,
    worldLocked: Boolean,
    connected: Boolean,
    cursorX: Float,
    cursorY: Float,
    pointerAiming: Boolean,
    presenting: Boolean,
    displayStatus: String,
    imeVisible: Boolean,
    imeText: String,
    imuDebug: String,
    onMode: (InputMode) -> Unit,
    onPointerAim: (Boolean) -> Unit,
    onCalibrate: () -> Unit,
    onAnchor: () -> Unit,
    onNudge: (Float, Float, Float) -> Unit,
    onDown: () -> Unit,
    onUp: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onZoom: (Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onThreeSwipe: (Float) -> Unit,
    onImeText: (String) -> Unit,
    onToggleIme: () -> Unit,
    onHideIme: () -> Unit,
    onCycleAxis: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .statusBarsPadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChrome(
            mode = mode,
            worldLocked = worldLocked,
            connected = connected,
            onMode = onMode,
            onAnchor = onAnchor,
            onTap = { onUp(true) },
        )
        SpatialImeBar(
            visible = imeVisible,
            text = imeText,
            onText = onImeText,
            onToggle = onToggleIme,
            onHide = onHideIme,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Trackpad(
                cursorX = cursorX,
                cursorY = cursorY,
                moveScreen = false,
                onNudge = onNudge,
                onDown = onDown,
                onUp = onUp,
                onCancel = onCancel,
                onZoom = onZoom,
                onScroll = onScroll,
                onPinch = onPinch,
                onThreeSwipe = onThreeSwipe,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onPointerAim(!pointerAiming) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(if (pointerAiming) "Hide pointer" else "Pointer")
            }
            OutlinedButton(
                onClick = onCalibrate,
                enabled = pointerAiming,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Recenter pointer")
            }
        }
        Text(
            if (presenting) displayStatus else "Glasses display missing — reconnect USB, then Connect.",
            style = MaterialTheme.typography.bodySmall,
            color = if (presenting) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "IMU: $imuDebug",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            OutlinedButton(onClick = onCycleAxis, shape = RoundedCornerShape(18.dp)) {
                Text("Axis")
            }
        }
        Text(
            if (pointerAiming) {
                "Pointer on: aim with the phone, tap the pad to click. Hold the hub and tilt to place a window."
            } else {
                "One finger moves the pointer. Two fingers scroll. Pinch zooms. Three-finger swipe is back or forward."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SpatialImeBar(
    visible: Boolean,
    text: String,
    onText: (String) -> Unit,
    onToggle: () -> Unit,
    onHide: () -> Unit,
) {
    val context = LocalContext.current
    val onTextLatest = rememberUpdatedState(onText)
    val onHideLatest = rememberUpdatedState(onHide)
    val seedLatest = rememberUpdatedState(text)
    val holder = remember { arrayOfNulls<EditText>(1) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Text(if (visible) "Hide keyboard" else "Keyboard")
        }
        if (visible) {
            OutlinedButton(
                onClick = onHide,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("Done")
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                hint = "Type into the window"
                isFocusable = true
                isFocusableInTouchMode = true
                showSoftInputOnFocus = true
                isSingleLine = false
                maxLines = 4
                textSize = 16f
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) {
                        onHideLatest.value()
                        true
                    } else {
                        false
                    }
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (hasFocus()) onTextLatest.value(s?.toString().orEmpty())
                    }
                })
                holder[0] = this
            }
        },
        update = { edit ->
            holder[0] = edit
            edit.visibility = if (visible) View.VISIBLE else View.GONE
        },
        modifier = if (visible) Modifier.fillMaxWidth() else Modifier.height(1.dp),
    )
    LaunchedEffect(visible) {
        val edit = holder[0] ?: return@LaunchedEffect
        if (visible) {
            val seed = seedLatest.value
            if (seed.isNotEmpty() && edit.text.toString() != seed && !edit.hasFocus()) {
                edit.setText(seed)
                edit.setSelection(seed.length.coerceAtMost(edit.text.length))
            }
            delay(16)
            showKeyboard(edit, context)
        } else {
            hideKeyboard(edit, context)
            edit.clearFocus()
        }
    }
}

private fun showKeyboard(edit: EditText, context: Context) {
    edit.visibility = View.VISIBLE
    edit.requestFocus()
    edit.post {
        edit.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        if (Build.VERSION.SDK_INT >= 30) {
            edit.windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }
}

private fun hideKeyboard(edit: EditText, context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(edit.windowToken, 0)
    if (Build.VERSION.SDK_INT >= 30) {
        edit.windowInsetsController?.hide(WindowInsets.Type.ime())
    }
}

@Composable
fun ModeChrome(
    mode: InputMode,
    worldLocked: Boolean,
    connected: Boolean,
    onMode: (InputMode) -> Unit,
    onAnchor: () -> Unit,
    onTap: () -> Unit,
    compact: Boolean = false,
    showAnchor: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeButton("Controls", mode == InputMode.CONTROLS) { onMode(InputMode.CONTROLS) }
            ModeButton("Trackpad", mode == InputMode.TRACKPAD) { onMode(InputMode.TRACKPAD) }
            ModeButton("Pointer", mode == InputMode.POINTER) { onMode(InputMode.POINTER) }
        }
        if (showAnchor || mode == InputMode.POINTER || !compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showAnchor) {
                    Button(onClick = onAnchor) {
                        Text(if (worldLocked) "Recenter" else "Anchor")
                    }
                }
                if (!compact) {
                    Button(onClick = onTap) { Text("Click") }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, fontSize = 13.sp) }
    }
}
