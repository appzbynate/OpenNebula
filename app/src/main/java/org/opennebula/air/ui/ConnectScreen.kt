package org.opennebula.air.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opennebula.air.input.InputMode
import org.opennebula.air.input.TrackpadCmd
import org.opennebula.air.input.TrackpadFinger
import org.opennebula.air.input.TrackpadTouch
import org.opennebula.air.usb.GlassesConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectScreen(viewModel: GlassesViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val displayStatus by viewModel.displayStatus.collectAsStateWithLifecycle()
    val cursorX by viewModel.cursorX.collectAsStateWithLifecycle()
    val cursorY by viewModel.cursorY.collectAsStateWithLifecycle()
    val note by viewModel.noteText.collectAsStateWithLifecycle()
    val showLog by viewModel.showLog.collectAsStateWithLifecycle()
    val imuDebug by viewModel.imuDebug.collectAsStateWithLifecycle()
    val worldLocked by viewModel.worldLocked.collectAsStateWithLifecycle()
    val stereo by viewModel.stereo.collectAsStateWithLifecycle()
    val brightness by viewModel.brightness.collectAsStateWithLifecycle()
    val moveScreen by viewModel.moveScreen.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val inputMode by viewModel.inputMode.collectAsStateWithLifecycle()
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val pointerAiming by viewModel.pointerAiming.collectAsStateWithLifecycle()
    val imeVisible by viewModel.imeVisible.collectAsStateWithLifecycle()
    val imeText by viewModel.imeText.collectAsStateWithLifecycle()
    val promptConnectMode by viewModel.promptConnectMode.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedIds.collectAsStateWithLifecycle()
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    val steamVr by viewModel.steamVrUi.collectAsStateWithLifecycle()
    val remoteDesktop by viewModel.remoteDesktopUi.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        ConnectScreenContent(
            state = state,
            displayStatus = displayStatus.summary,
            presenting = displayStatus.presenting,
            worldLocked = worldLocked,
            stereo = stereo,
            brightness = brightness,
            mirroring = mirroring,
            moveScreen = moveScreen,
            focused = focused,
            inputMode = inputMode,
            logs = logs,
            showLog = showLog,
            imuDebug = imuDebug,
            note = note,
            cursorX = cursorX,
            cursorY = cursorY,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            onAnchorOrRecenter = viewModel::recenter,
            onCalibrate = { viewModel.startCalibration(fromAuto = false) },
            onStereo = viewModel::setStereo,
            onBrightness = viewModel::setBrightness,
            onMirroring = viewModel::setMirroring,
            onFocus = viewModel::focus,
            onClear = viewModel::clearLogs,
            onToggleLog = viewModel::toggleLog,
            onCycleAxis = viewModel::cycleImuAxisMode,
            onNote = viewModel::setNote,
            onNudge = viewModel::nudgePad,
            onDown = viewModel::padDown,
            onUp = viewModel::padUp,
            onCancel = viewModel::padCancel,
            onTap = viewModel::tapCursor,
            onZoom = viewModel::zoomDistance,
            onScroll = viewModel::padScroll,
            onPinch = viewModel::padPinch,
            onThreeSwipe = viewModel::padThreeSwipe,
            onInputMode = viewModel::setInputMode,
            onSettings = { showSettings = true },
            onApps = { showApps = true },
            imeVisible = imeVisible && inputMode == InputMode.CONTROLS,
            imeText = imeText,
            onImeText = viewModel::setImeText,
            onToggleIme = viewModel::toggleIme,
            onHideIme = viewModel::hideIme,
        )
        if (showSettings) {
            SettingsDialog(
                viewModel = viewModel,
                onClose = { showSettings = false },
            )
        }
        if (showApps) {
            AppsDialog(
                viewModel = viewModel,
                onClose = { showApps = false },
            )
        }
        if (promptConnectMode) {
            ConnectModeDialog(
                shortcuts = ConnectMode.extendShortcuts(pinnedIds),
                onMirror = viewModel::chooseMirror,
                onExtend = viewModel::chooseExtend,
            )
        }
        if (calibration.phase != CalibrationPhase.Hidden) {
            CalibrationDialog(
                state = calibration,
                onDismiss = viewModel::dismissCalibration,
                onRetry = { viewModel.startCalibration(fromAuto = false) },
                onFinish = viewModel::finishCalibration,
            )
        }
        if (steamVr.open) {
            SteamVrDialog(
                state = steamVr,
                onSelect = viewModel::selectSteamVrHost,
                onManualIp = viewModel::setSteamVrIp,
                onHeight = viewModel::setSteamVrHeight,
                onConnect = viewModel::connectSteamVr,
                onDisconnect = viewModel::disconnectSteamVr,
                onClose = viewModel::closeSteamVrDialog,
            )
        }
        if (remoteDesktop.open) {
            RemoteDesktopDialog(
                state = remoteDesktop,
                onSelect = viewModel::selectRemoteDesktopHost,
                onManualIp = viewModel::setRemoteDesktopIp,
                onConnect = viewModel::connectRemoteDesktop,
                onDisconnect = viewModel::disconnectRemoteDesktop,
                onClose = viewModel::closeRemoteDesktopDialog,
            )
        }
        if (inputMode != InputMode.CONTROLS && !promptConnectMode) {
            val connected = state is GlassesConnectionState.Streaming ||
                state is GlassesConnectionState.Connecting ||
                state is GlassesConnectionState.AwaitingPermission
            InputOverlay(
                mode = inputMode,
                worldLocked = worldLocked,
                connected = connected,
                cursorX = cursorX,
                cursorY = cursorY,
                pointerAiming = pointerAiming,
                presenting = displayStatus.presenting,
                displayStatus = displayStatus.summary,
                imeVisible = imeVisible,
                imeText = imeText,
                imuDebug = imuDebug,
                onMode = viewModel::setInputMode,
                onPointerAim = viewModel::setPointerAiming,
                onCalibrate = viewModel::calibratePointer,
                onAnchor = viewModel::recenter,
                onNudge = viewModel::nudgePad,
                onDown = viewModel::padDown,
                onUp = viewModel::padUp,
                onCancel = viewModel::padCancel,
                onZoom = viewModel::zoomDistance,
                onScroll = viewModel::padScroll,
                onPinch = viewModel::padPinch,
                onThreeSwipe = viewModel::padThreeSwipe,
                onImeText = viewModel::setImeText,
                onToggleIme = viewModel::toggleIme,
                onHideIme = viewModel::hideIme,
                onCycleAxis = viewModel::cycleImuAxisMode,
            )
        }
    }
}

@Composable
fun ConnectScreenContent(
    state: GlassesConnectionState,
    displayStatus: String,
    presenting: Boolean,
    worldLocked: Boolean,
    stereo: Boolean,
    brightness: Int,
    mirroring: Boolean,
    moveScreen: Boolean,
    focused: String,
    inputMode: InputMode,
    logs: List<LogEntry>,
    showLog: Boolean,
    imuDebug: String,
    note: String,
    cursorX: Float,
    cursorY: Float,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAnchorOrRecenter: () -> Unit,
    onCalibrate: () -> Unit,
    onStereo: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    onMirroring: (Boolean) -> Unit,
    onFocus: (String) -> Unit,
    onClear: () -> Unit,
    onToggleLog: () -> Unit,
    onCycleAxis: () -> Unit,
    onNote: (String) -> Unit,
    onNudge: (Float, Float, Float) -> Unit,
    onDown: () -> Unit,
    onUp: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onThreeSwipe: (Float) -> Unit,
    onInputMode: (InputMode) -> Unit,
    onSettings: () -> Unit,
    onApps: () -> Unit,
    imeVisible: Boolean,
    imeText: String,
    onImeText: (String) -> Unit,
    onToggleIme: () -> Unit,
    onHideIme: () -> Unit,
) {
    val connected = state is GlassesConnectionState.Streaming ||
        state is GlassesConnectionState.Connecting ||
        state is GlassesConnectionState.AwaitingPermission
    // Only treat live IMU as "connected" for Connect/Disconnect — permission/connecting
    // used to hide Connect Glasses and leave the UI stuck.
    val streaming = state is GlassesConnectionState.Streaming

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("OpenNebula", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    !connected -> "Connect the Air. You’ll get a hold-still calibration, then Anchor."
                    !worldLocked -> "Hold still for Calibrate, or look forward and tap Anchor."
                    else -> "Phone = trackpad or pointer. Glasses = floating apps. Calibrate if icons crawl."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
            Text(
                displayStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "IMU: $imuDebug",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = stereo, onCheckedChange = onStereo)
                Text(
                    if (stereo) "Hardware 3D SBS — toggle off for 2D."
                    else "2D mirror — toggle on for hardware 3D.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Brightness $brightness / 7",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = brightness.toFloat(),
                onValueChange = { onBrightness(it.toInt()) },
                valueRange = 0f..7f,
                steps = 6,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = mirroring, onCheckedChange = onMirroring)
                Text(
                    if (mirroring) "Screen capture on — a phone-mirror window sits beside Home."
                    else "Screen capture off",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onFocus(GlassesViewModel.HOME) },
                    enabled = focused != GlassesViewModel.HOME,
                ) { Text("Apps") }
                Button(
                    onClick = { onFocus(GlassesViewModel.SCREEN) },
                    enabled = focused != GlassesViewModel.SCREEN && mirroring,
                ) { Text("Screen") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (streaming) {
                    Button(onClick = onDisconnect) { Text("Disconnect") }
                    Button(onClick = onCalibrate) { Text("Calibrate") }
                } else {
                    Button(onClick = onConnect) { Text("Connect Glasses") }
                    if (connected) {
                        Button(onClick = onDisconnect) { Text("Cancel") }
                    }
                }
                OutlinedButton(onClick = onToggleLog) { Text(if (showLog) "Hide log" else "Log") }
                OutlinedButton(onClick = onCycleAxis) { Text("Axis") }
                OutlinedButton(onClick = onSettings) { Text("Settings") }
                OutlinedButton(onClick = onApps) { Text("Icons") }
            }
            ModeChrome(
                mode = inputMode,
                worldLocked = worldLocked,
                connected = connected,
                onMode = onInputMode,
                onAnchor = onAnchorOrRecenter,
                onTap = onTap,
                compact = true,
            )
            if (connected) {
                OutlinedTextField(
                    value = note,
                    onValueChange = onNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note on glasses") },
                    singleLine = false,
                    minLines = 2,
                )
                Text(
                    if (moveScreen) "Move screen is on in Settings — drag to place, two fingers = distance."
                    else if (presenting) "One finger = pointer. Tap = click. Two fingers = scroll. Pinch = zoom. Three fingers = back / forward."
                    else "Glasses display not separate — OEM may be mirroring the phone.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                SpatialImeBar(
                    visible = imeVisible,
                    text = imeText,
                    onText = onImeText,
                    onToggle = onToggleIme,
                    onHide = onHideIme,
                )
                Trackpad(
                    cursorX = cursorX,
                    cursorY = cursorY,
                    moveScreen = moveScreen,
                    onNudge = onNudge,
                    onDown = onDown,
                    onUp = onUp,
                    onCancel = onCancel,
                    onZoom = onZoom,
                    onScroll = onScroll,
                    onPinch = onPinch,
                    onThreeSwipe = onThreeSwipe,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            if (showLog || !connected) {
                if (connected) {
                    OutlinedButton(onClick = onClear) { Text("Clear log") }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (connected) Modifier.height(140.dp) else Modifier.weight(1f))
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    items(logs, key = { it.id }) { entry ->
                        Text(formatLog(entry), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Trackpad(
    cursorX: Float,
    cursorY: Float,
    moveScreen: Boolean,
    onNudge: (Float, Float, Float) -> Unit,
    onDown: () -> Unit,
    onUp: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onZoom: (Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onThreeSwipe: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = remember { TrackpadTouch() }
    val disallow = remember { RequestDisallowInterceptTouchEvent() }
    var padW by remember { mutableFloatStateOf(1f) }
    var padH by remember { mutableFloatStateOf(1f) }
    val moveScreenState = rememberUpdatedState(moveScreen)
    val onNudgeState = rememberUpdatedState(onNudge)
    val onDownState = rememberUpdatedState(onDown)
    val onUpState = rememberUpdatedState(onUp)
    val onCancelState = rememberUpdatedState(onCancel)
    val onZoomState = rememberUpdatedState(onZoom)
    val onScrollState = rememberUpdatedState(onScroll)
    val onPinchState = rememberUpdatedState(onPinch)
    val onThreeSwipeState = rememberUpdatedState(onThreeSwipe)
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .onSizeChanged {
                padW = it.width.toFloat().coerceAtLeast(1f)
                padH = it.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInteropFilter(requestDisallowInterceptTouchEvent = disallow) { event ->
                disallow.invoke(true)
                session.width = padW
                session.height = padH
                session.moveScreen = moveScreenState.value
                val cmds = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        session.reset()
                        session.update(fingersFrom(event))
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                        session.update(fingersFrom(event))
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        session.update(fingersFrom(event, skipIndex = event.actionIndex))
                    }
                    MotionEvent.ACTION_UP -> session.lift()
                    MotionEvent.ACTION_CANCEL -> session.abort()
                    else -> emptyList()
                }
                for (cmd in cmds) {
                    when (cmd) {
                        TrackpadCmd.Down -> onDownState.value()
                        TrackpadCmd.Cancel -> onCancelState.value()
                        is TrackpadCmd.Move -> onNudgeState.value(cmd.dxNorm, cmd.dyNorm, cmd.pixels)
                        is TrackpadCmd.Scroll -> onScrollState.value(cmd.dxNorm, cmd.dyNorm)
                        is TrackpadCmd.Pinch -> onPinchState.value(cmd.factor)
                        is TrackpadCmd.Distance -> onZoomState.value(cmd.dyNorm)
                        is TrackpadCmd.ThreeSwipe -> onThreeSwipeState.value(cmd.dir)
                        is TrackpadCmd.Up -> onUpState.value(cmd.click)
                    }
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!moveScreen) {
            Canvas(Modifier.fillMaxSize()) {
                val x = cursorX * size.width
                val y = (cursorY.coerceIn(0f, 1.2f) / 1.2f) * size.height
                val path = arrowCursor(Offset(x, y), 26f)
                drawPath(path, Color(0xF5F4F7FA), style = Fill)
                drawPath(
                    path,
                    Color(0xE0121418),
                    style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Text(if (moveScreen) "Move screen" else "Trackpad", color = MaterialTheme.colorScheme.secondary)
    }
}

private fun arrowCursor(origin: Offset, size: Float): Path {
    val s = size
    return Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x, origin.y + s * 0.92f)
        lineTo(origin.x + s * 0.28f, origin.y + s * 0.70f)
        lineTo(origin.x + s * 0.62f, origin.y + s * 1.08f)
        lineTo(origin.x + s * 0.76f, origin.y + s * 0.96f)
        lineTo(origin.x + s * 0.42f, origin.y + s * 0.58f)
        lineTo(origin.x + s * 0.72f, origin.y + s * 0.50f)
        close()
    }
}

private fun fingersFrom(event: MotionEvent, skipIndex: Int = -1): List<TrackpadFinger> {
    return buildList {
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            add(TrackpadFinger(event.getPointerId(i), event.getX(i), event.getY(i)))
        }
    }
}

private fun statusText(state: GlassesConnectionState): String = when (state) {
    GlassesConnectionState.Disconnected -> "Status: disconnected — plug glasses in, then Connect"
    GlassesConnectionState.Connecting -> "Status: connecting…"
    GlassesConnectionState.AwaitingPermission -> "Status: waiting for USB permission — tap Allow"
    is GlassesConnectionState.Streaming ->
        "Status: streaming ${state.packetsPerSec} Hz"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatLog(entry: LogEntry): String {
    val time = timeFormat.format(Date(entry.timeMs))
    return "$time  ${entry.tag}  ${entry.message}"
}
