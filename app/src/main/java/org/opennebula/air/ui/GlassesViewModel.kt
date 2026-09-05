package org.opennebula.air.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import android.view.MotionEvent
import org.opennebula.air.browser.BrandIcons
import org.opennebula.air.browser.SpatialBrowser
import org.opennebula.air.browser.SpatialBrowserSession
import org.opennebula.air.browser.WebApps
import org.opennebula.air.display.GlassesDisplayController
import org.opennebula.air.display.GlassesDisplayStatus
import org.opennebula.air.imu.ImuSample
import org.opennebula.air.imu.Orientation
import org.opennebula.air.imu.Vec3f
import org.opennebula.air.input.InputMode
import org.opennebula.air.input.PhonePointer
import org.opennebula.air.input.TrackballHaptics
import org.opennebula.air.logging.LogTags
import org.opennebula.air.mirror.MirrorCapture
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.mirror.MirrorProjectionService
import org.opennebula.air.render.PanelPick
import org.opennebula.air.render.PointerBeam
import org.opennebula.air.render.PoseSmoother
import org.opennebula.air.render.StereoConfig
import org.opennebula.air.shell.ChromeHandle
import org.opennebula.air.shell.DisplayPrefs
import org.opennebula.air.shell.LauncherPins
import org.opennebula.air.shell.LauncherPrefs
import org.opennebula.air.shell.IconGrid
import org.opennebula.air.shell.IconGridState
import org.opennebula.air.shell.IconSource
import org.opennebula.air.shell.PanelFramebuffer
import org.opennebula.air.shell.ShellApps
import org.opennebula.air.shell.SpatialLayer
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import org.opennebula.air.shell.WindowChrome
import org.opennebula.air.shell.WindowChromeState
import org.opennebula.air.usb.GlassesConnectionState
import org.opennebula.air.usb.NrealAirUsbClient
import org.opennebula.air.xr.RemoteDesktopSession
import org.opennebula.air.xr.SteamVrSession
import org.opennebula.air.xr.XrHost
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

data class LogEntry(
    val id: Long,
    val timeMs: Long,
    val tag: String,
    val message: String,
)

class GlassesViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val nextLogId = AtomicLong(0L)

    val panel = PanelFramebuffer()
    val notesPanel = PanelFramebuffer()
    val windowRef = AtomicReference(
        SpatialWindow(
            y = IconGrid.fieldY(),
            z = SpatialLayout.ICON_Z,
            width = IconGrid.fieldWidth(),
            height = IconGrid.fieldHeight(),
        ),
    )
    val notesPose = AtomicReference(
        SpatialWindow.fromControls(-SpatialLayout.PANEL_Z, SpatialLayout.PANEL_Y, 0.9f, 1.85f),
    )
    val focusedId = AtomicReference(HOME)
    val layers = listOf(
        SpatialLayer(HOME, "Home", panel, windowRef),
        SpatialLayer(NOTES, "Notes", notesPanel, notesPose),
    )
    val markerFlag = AtomicReference(false)
    val stereoFlag = AtomicBoolean(false)
    /** True after MCU HalfSBS mode is applied — drives eye projection aspect. */
    val hardware3dFlag = AtomicBoolean(false)
    val ipdRef = AtomicReference(StereoConfig.IPD_METERS)
    val pointerBeam = AtomicReference(PointerBeam(0f, 0f, -2f, false))
    val mirror = MirrorPipeline()
    val chrome = WindowChromeState()
    val gridOpen = AtomicBoolean(true)
    val iconGrid = IconGridState()
    private val browser = SpatialBrowserSession(
        getApplication(),
        MirrorPipeline(),
    ) { appendLog(LogTags.BROWSER, it) }
    val steamVrPipeline = MirrorPipeline()
    private val steamVr = SteamVrSession(
        context = getApplication(),
        scope = viewModelScope,
        pipeline = steamVrPipeline,
        onLog = { appendLog(LogTags.UI, it) },
        onExitToHome = {
            viewModelScope.launch(Dispatchers.Main) {
                showGrid()
            }
        },
    )
    val steamVrUi = steamVr.ui
    val desktopPipeline = MirrorPipeline()
    private val remoteDesktop = RemoteDesktopSession(
        context = getApplication(),
        scope = viewModelScope,
        pipeline = desktopPipeline,
        onLog = { appendLog(LogTags.UI, it) },
        onExitToHome = {
            viewModelScope.launch(Dispatchers.Main) {
                showGrid()
            }
        },
    )
    val remoteDesktopUi = remoteDesktop.ui
    private val worldLock = AtomicBoolean(false)
    private val poseLock = Any()
    private val autoAnchored = AtomicBoolean(false)
    private var calibrationJob: Job? = null
    private val prefs = DisplayPrefs(application)
    private val launcherPrefs = LauncherPrefs(application)
    private val haptics = TrackballHaptics(application)
    private var phonePointer: PhonePointer? = null
    private var pointerZeroYaw = 0f
    private var pointerZeroPitch = 0f
    private var pointerNeedsCalibrate = true
    private var lastPointerU = 0.5f
    private var lastPointerV = 0.5f
    private var capture: MirrorCapture? = null
    private var stoppingMirror = false

    private val client = NrealAirUsbClient(
        context = application,
        scope = viewModelScope,
        onLog = { tag, message -> appendLog(tag, message) },
    )

    private val displays = GlassesDisplayController(
        layers = layers,
        focusedId = focusedId,
        showMarkers = markerFlag,
        stereoEnabled = stereoFlag,
        hardware3d = hardware3dFlag,
        ipdMeters = ipdRef,
        pointerBeam = pointerBeam,
        mirror = mirror,
        chrome = chrome,
        iconGrid = iconGrid,
        gridOpen = gridOpen,
        gridPose = windowRef,
        browser = browser.pipeline,
        steamVr = steamVrPipeline,
        desktop = desktopPipeline,
        onLog = { tag, message -> appendLog(tag, message) },
    )
    val displayStatus: StateFlow<GlassesDisplayStatus> = displays.status

    private val _imeVisible = MutableStateFlow(false)
    val imeVisible: StateFlow<Boolean> = _imeVisible.asStateFlow()
    private val _imeText = MutableStateFlow("")
    val imeText: StateFlow<String> = _imeText.asStateFlow()
    private val _pinnedIds = MutableStateFlow(launcherPrefs.pinned())
    val pinnedIds: StateFlow<List<String>> = _pinnedIds.asStateFlow()
    private val _cursorX = MutableStateFlow(0.5f)
    private val _cursorY = MutableStateFlow(0.5f)
    val cursorX: StateFlow<Float> = _cursorX.asStateFlow()
    val cursorY: StateFlow<Float> = _cursorY.asStateFlow()

    private val _noteText = MutableStateFlow("")
    val noteText: StateFlow<String> = _noteText.asStateFlow()

    private val _showLog = MutableStateFlow(false)
    val showLog: StateFlow<Boolean> = _showLog.asStateFlow()
    private val _imuDebug = MutableStateFlow("imu: waiting for stream")
    val imuDebug: StateFlow<String> = _imuDebug.asStateFlow()

    private val _showMarkers = MutableStateFlow(false)
    val showMarkers: StateFlow<Boolean> = _showMarkers.asStateFlow()

    private val _worldLocked = MutableStateFlow(false)
    val worldLocked: StateFlow<Boolean> = _worldLocked.asStateFlow()

    private val _calibration = MutableStateFlow(CalibrationUiState())
    val calibration: StateFlow<CalibrationUiState> = _calibration.asStateFlow()

    private val _stereo = MutableStateFlow(false)
    val stereo: StateFlow<Boolean> = _stereo.asStateFlow()
    private val _brightness = MutableStateFlow(5)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()
    /** Stereo preference before SteamVR forced hardware 3D; restored on disconnect. */
    private var stereoBeforeSteamVr: Boolean? = null

    private val _distance = MutableStateFlow(-SpatialLayout.PANEL_Z)
    val distance: StateFlow<Float> = _distance.asStateFlow()

    private val _panelHeight = MutableStateFlow(SpatialLayout.PANEL_Y)
    val panelHeight: StateFlow<Float> = _panelHeight.asStateFlow()

    private val _panelScale = MutableStateFlow(1f)
    val panelScale: StateFlow<Float> = _panelScale.asStateFlow()

    private val _offsetX = MutableStateFlow(SpatialLayout.PANEL_X)
    val offsetX: StateFlow<Float> = _offsetX.asStateFlow()

    private val _ipd = MutableStateFlow(StereoConfig.IPD_METERS)
    val ipd: StateFlow<Float> = _ipd.asStateFlow()

    private val _moveScreen = MutableStateFlow(false)
    val moveScreen: StateFlow<Boolean> = _moveScreen.asStateFlow()

    private val _focused = MutableStateFlow(HOME)
    val focused: StateFlow<String> = _focused.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.CONTROLS)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _mirroring = MutableStateFlow(false)
    val mirroring: StateFlow<Boolean> = _mirroring.asStateFlow()

    private val _promptConnectMode = MutableStateFlow(false)
    val promptConnectMode: StateFlow<Boolean> = _promptConnectMode.asStateFlow()

    private val _pointerAiming = MutableStateFlow(false)
    val pointerAiming: StateFlow<Boolean> = _pointerAiming.asStateFlow()

    var tapHandler: ((Float, Float) -> Unit)? = null
    var startMirrorCapture: (() -> Unit)? = null
    var requestBrowserAudio: (() -> Unit)? = null
    private var screenAspect = 9f / 20f
    private var padHeld = false
    private var grabPitch = 0f
    private var grabYaw = 0f
    private var grabRoll = 0f
    private var lastAimYaw = 0f
    private var lastAimPitch = 0f
    private var lastAimRoll = 0f
    private var grabPoseArmed = false
    private var hubOwnsPointer = false
    private var hubGrabOffX = 0f
    private var hubGrabOffY = 0f
    private var hubGrabWinRoll = 0f
    private var hubGrabPhoneRoll = 0f
    private var lastHubDir: FloatArray? = null
    private var gridPage = 0
    private var gridPan = 0f
    private var promptedConnectMode = false
    private var mirrorHidesGrid = false
    private var promptedCalibration = false
    private var lastConnection: GlassesConnectionState = GlassesConnectionState.Disconnected

    init {
        client.onGlassesPose = { pose ->
            displays.publishPose(pose, client.lastGyroRadS())
            steamVr.onPose(pose)
        }
        client.onImuDebug = { line -> _imuDebug.value = line }
        viewModelScope.launch {
            client.state.collect { state ->
                if (ConnectMode.enteredStreaming(lastConnection, state) && !promptedConnectMode) {
                    promptedConnectMode = true
                    _promptConnectMode.value = true
                }
                if (ConnectMode.enteredStreaming(lastConnection, state) && !promptedCalibration) {
                    promptedCalibration = true
                    startCalibration(fromAuto = true)
                }
                // Fresh glasses plug-in: never keep a stale SteamVR / "Video lost" layer.
                if (ConnectMode.enteredStreaming(lastConnection, state) && !steamVr.isConnected()) {
                    steamVr.disconnect()
                    showGrid()
                }
                if (ConnectMode.enteredStreaming(lastConnection, state)) {
                    applyMcuDisplayPrefs()
                }
                lastConnection = state
                if (state is GlassesConnectionState.Disconnected) {
                    _imuDebug.value = "imu: disconnected"
                    promptedConnectMode = false
                    promptedCalibration = false
                    mirrorHidesGrid = false
                    _promptConnectMode.value = false
                    dismissCalibration()
                    setInputMode(InputMode.CONTROLS)
                    steamVr.disconnect()
                    showGrid()

                    clearAnchor()
                    stopMirroring()
                    browser.stop()
                    gridOpen.set(true)
                    focusedId.set(HOME)
                    _focused.value = HOME
                }
            }
        }
        viewModelScope.launch {
            mirror.active.collect { running ->
                _mirroring.value = running
            }
        }
        mirror.onLog = { appendLog(LogTags.MIRROR, it) }
        mirror.onCaptureSize = { w, h -> applyMirrorAspect(w, h) }
        panel.cursorX.set(0.5f)
        panel.cursorY.set(0.5f)
        notesPanel.cursorX.set(0.5f)
        notesPanel.cursorY.set(0.5f)
        restorePrefs()
        rebuildIcons()
        viewModelScope.launch {
            var wasSteamVr = false
            steamVr.ui.collect { ui ->
                if (ui.connected && !wasSteamVr) {
                    wasSteamVr = true
                    onSteamVrConnected()
                } else if (!ui.connected && wasSteamVr) {
                    wasSteamVr = false
                    onSteamVrDisconnected()
                }
            }
        }
        browser.onImeFocus = { text ->
            _imeText.value = text
            _imeVisible.value = true
        }
        browser.onImeBlur = { }
    }

    val connectionState: StateFlow<GlassesConnectionState> = client.state
    val latestSample: StateFlow<ImuSample?> = client.latestSample
    val orientation: StateFlow<Orientation> = client.orientation

    fun chooseMirror() {
        _promptConnectMode.value = false
        mirrorHidesGrid = true
        setInputMode(InputMode.CONTROLS)
        if (mirror.enabled.get() || _mirroring.value) {
            gridOpen.set(false)
            mirrorHidesGrid = false
            focus(SCREEN)
            displays.requestFrameNow()
            appendLog(LogTags.UI, "connect mode mirror")
            return
        }
        appendLog(LogTags.UI, "connect mode mirror")
        openScreenMirror()
    }

    fun chooseExtend(component: String? = null) {
        _promptConnectMode.value = false
        mirrorHidesGrid = false
        setInputMode(InputMode.TRACKPAD)
        val id = component?.trim().orEmpty()
        if (id.isEmpty() || ShellApps.isExtended(id) || id == HOME) {
            showGrid()
        } else {
            launchFromGrid(id)
        }
        appendLog(LogTags.UI, "connect mode extend ${id.ifBlank { "home" }}")
    }

    fun connect() {
        autoAnchored.set(false)
        promptedCalibration = false
        clearAnchor()
        client.connect()
    }

    fun disconnect() = client.disconnect()

    fun startCalibration(fromAuto: Boolean = false) {
        val phase = _calibration.value.phase
        if (phase == CalibrationPhase.HoldStill || phase == CalibrationPhase.Anchoring) return
        calibrationJob?.cancel()
        client.restartBiasCalibration()
        autoAnchored.set(false)
        _calibration.value = CalibrationUiState(
            phase = CalibrationPhase.HoldStill,
            progress = 0f,
            message = if (fromAuto) {
                "Connected — hold still while we measure gyro bias, then we’ll Anchor."
            } else {
                "Hold still ~1.5 s. Progress only advances while motion stays low."
            },
        )
        appendLog(LogTags.IMU, "calibration started")
        calibrationJob = viewModelScope.launch {
            var stallMs = 0L
            var lastProgress = 0f
            while (isActive) {
                val progress = client.biasProgress()
                val rate = client.rateEmaDps()
                _calibration.value = _calibration.value.copy(
                    phase = CalibrationPhase.HoldStill,
                    progress = progress,
                    rateEmaDps = rate,
                    message = when {
                        rate > 5f -> "Too much motion — freeze your head."
                        progress < 0.05f -> "Hold still… waiting for quiet samples."
                        else -> "Hold still… ${(progress * 100).toInt()}%"
                    },
                )
                if (progress >= 1f && client.biasReady()) {
                    _calibration.value = CalibrationUiState(
                        phase = CalibrationPhase.Anchoring,
                        progress = 1f,
                        message = "Look forward — planting world lock…",
                    )
                    delay(250)
                    recenter()
                    autoAnchored.set(true)
                    val dz = client.gyroDeadzoneDps()
                    val floor = client.noiseFloorDps()
                    _calibration.value = CalibrationUiState(
                        phase = CalibrationPhase.Done,
                        progress = 1f,
                        message = "Bias set, deadzone ${"%.1f".format(dz)}°/s " +
                            "(floor ${"%.1f".format(floor)}°/s + 0.5°). " +
                            "Slow looks should world-lock immediately.",
                    )
                    appendLog(
                        LogTags.POSE,
                        "calibration complete dz=${"%.2f".format(dz)} floor=${"%.2f".format(floor)}",
                    )
                    return@launch
                }
                if (kotlin.math.abs(progress - lastProgress) < 0.001f) {
                    stallMs += 50
                } else {
                    stallMs = 0L
                    lastProgress = progress
                }
                // Soft hint only — quiet-only warmup can sit at 0 while moving.
                if (stallMs > 12_000L && progress < 0.2f) {
                    _calibration.value = _calibration.value.copy(
                        phase = CalibrationPhase.Failed,
                        message = "Still seeing motion. Hold the glasses steady and retry.",
                    )
                    return@launch
                }
                delay(50)
            }
        }
    }

    fun dismissCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        _calibration.value = CalibrationUiState()
    }

    fun finishCalibration() {
        dismissCalibration()
    }

    fun recenter() {
        synchronized(poseLock) {
            worldLock.set(true)
        }
        _worldLocked.value = true
        autoAnchored.set(true)
        client.recenter()
        displays.publishPose(Orientation.IDENTITY)
        displays.requestFrameNow()
        appendLog(LogTags.POSE, "Anchor — world locked")
    }

    fun cycleImuAxisMode() {
        val mode = client.cycleImuAxisMode()
        _imuDebug.value = "axis locked $mode (gyro+accel both ar-drivers)"
        appendLog(LogTags.IMU, "axis locked $mode")
    }

    fun bindDisplay(activity: Activity) {
        displays.bind(activity)
        browser.bind(activity)
        if (pointerActive()) startPhonePointer()
    }

    fun unbindDisplay() {
        phonePointer?.stop()
        browser.unbind()
        displays.unbind()
    }

    fun onActivityPause() {
        phonePointer?.stop()
        SpatialBrowser.flushCookies()
    }

    fun setMirroring(on: Boolean) {
        if (on) {
            if (_mirroring.value) return
            startMirrorCapture?.invoke()
        } else {
            stopMirroring()
        }
    }

    fun onMirrorPermission(activity: Activity, resultCode: Int, data: Intent) {
        val (w, h) = captureSizeFromPhone()
        screenAspect = w.toFloat() / h.toFloat()
        mirror.setCaptureSize(w, h)
        val hideGrid = mirrorHidesGrid
        mirrorHidesGrid = false
        val offset = if (hideGrid) 0f else -1.55f
        val scale = if (hideGrid) 1.28f else 1.1f
        mirror.pose.set(
            SpatialWindow.fromControls(
                -SpatialLayout.PANEL_Z,
                SpatialLayout.PANEL_Y,
                scale,
                offset,
                screenAspect,
            ),
        )
        capture?.stop()
        val mgr = activity.getSystemService(MediaProjectionManager::class.java)
        capture = MirrorCapture(activity.applicationContext, mgr, mirror) { stopMirroring() }
        capture?.start(resultCode, data)
        if (hideGrid) {
            gridOpen.set(false)
            collapseChrome()
        }
        focus(SCREEN)
        appendLog(LogTags.MIRROR, "mirroring ${w}x$h aspect ${"%.2f".format(screenAspect)}")
    }

    fun stopMirroring() {
        if (stoppingMirror) return
        stoppingMirror = true
        capture?.stop()
        capture = null
        MirrorProjectionService.stop(getApplication())
        if (focusedId.get() == SCREEN) focus(HOME)
        stoppingMirror = false
    }

    private fun applyMirrorAspect(width: Int, height: Int) {
        screenAspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        if (focusedId.get() == SCREEN) {
            publishWindow()
            return
        }
        val pose = mirror.pose.get()
        val scale = if (screenAspect >= 1f) {
            pose.width / SpatialLayout.PANEL_W
        } else {
            pose.height / SpatialLayout.PANEL_H
        }
        mirror.pose.set(
            SpatialWindow.fromControls(
                pose.distance,
                pose.y,
                scale,
                pose.x,
                screenAspect,
                pose.yaw,
                pose.pitch,
                pose.roll,
            ),
        )
    }

    private fun captureSizeFromPhone(): Pair<Int, Int> {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            MirrorPipeline.captureSize(bounds.width(), bounds.height())
        } else {
            val metrics = app.resources.displayMetrics
            MirrorPipeline.captureSize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun setNote(text: String) {
        _noteText.value = text
    }

    fun toggleLog() {
        _showLog.value = !_showLog.value
    }

    fun toggleMarkers() {
        val next = !_showMarkers.value
        _showMarkers.value = next
        markerFlag.set(next)
    }

    fun setStereo(enabled: Boolean) {
        _stereo.value = enabled
        stereoFlag.set(enabled)
        if (!enabled) {
            hardware3dFlag.set(false)
        } else {
            // Projection path on immediately; MCU confirm keeps or clears the flag.
            hardware3dFlag.set(true)
        }
        displays.refreshModeStatus()
        displays.requestFrameNow()
        persist()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { client.setDisplayMode3d(enabled) }.getOrDefault(false)
            hardware3dFlag.set(enabled && ok)
            displays.refreshModeStatus()
            displays.requestFrameNow()
            appendLog(
                LogTags.USB,
                if (ok) "hardware ${if (enabled) "3D SBS" else "2D"} applied"
                else "hardware display mode failed (software stereo still ${if (enabled) "on" else "off"})",
            )
        }
    }

    fun setBrightness(level: Int) {
        val v = level.coerceIn(0, 7)
        _brightness.value = v
        persist()
        // MCU USB deferred — do not touch iface 4 until connect is stable again.
    }

    /** Always boot the session in 2D; user toggles hardware 3D explicitly. */
    private fun applyMcuDisplayPrefs() {
        _stereo.value = false
        stereoFlag.set(false)
        hardware3dFlag.set(false)
        displays.refreshModeStatus()
        displays.requestFrameNow()
        persist()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { client.setDisplayMode3d(false) }.getOrDefault(false)
            appendLog(
                LogTags.USB,
                if (ok) "connect → hardware 2D"
                else "connect → hardware 2D request failed (UI stays 2D)",
            )
        }
    }

    /** SteamVR: ultrawide SBS (MCU mode 3 = 3840×1080 → 1920/eye). */
    private fun forceSteamVrHardware3d() {
        if (stereoBeforeSteamVr == null) {
            stereoBeforeSteamVr = _stereo.value
        }
        _stereo.value = true
        stereoFlag.set(true)
        hardware3dFlag.set(true)
        displays.refreshModeStatus()
        displays.requestFrameNow()
        persist()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                client.setDisplayMode3d(enabled = true, ultrawide = true)
            }.getOrDefault(false)
            hardware3dFlag.set(ok)
            displays.refreshModeStatus()
            displays.requestFrameNow()
            appendLog(
                LogTags.USB,
                if (ok) "SteamVR → hardware ultrawide 3D SBS (mode 3/8)"
                else "SteamVR → hardware 3D failed",
            )
        }
    }

    private fun onSteamVrConnected() {
        // Broken physical 3D button — force MCU SBS when SteamVR loads.
        forceSteamVrHardware3d()
    }

    private fun onSteamVrDisconnected() {
        val restore = stereoBeforeSteamVr ?: return
        stereoBeforeSteamVr = null
        setStereo(restore)
    }

    fun setIpd(meters: Float) {
        val ipd = meters.coerceIn(StereoConfig.IPD_MIN, StereoConfig.IPD_MAX)
        _ipd.value = ipd
        ipdRef.set(ipd)
        displays.refreshModeStatus()
        persist()
    }

    fun setDistance(meters: Float) {
        _distance.value = meters.coerceIn(SpatialWindow.DIST_MIN, SpatialWindow.DIST_MAX)
        publishWindow()
    }

    fun setPanelHeight(meters: Float) {
        _panelHeight.value = meters.coerceIn(SpatialWindow.HEIGHT_MIN, SpatialWindow.HEIGHT_MAX)
        publishWindow()
    }

    fun setPanelScale(scale: Float) {
        _panelScale.value = scale.coerceIn(SpatialWindow.SCALE_MIN, SpatialWindow.SCALE_MAX)
        publishWindow()
    }

    fun setOffsetX(meters: Float) {
        _offsetX.value = meters.coerceIn(SpatialWindow.OFFSET_MIN, SpatialWindow.OFFSET_MAX)
        publishWindow()
    }

    fun resetWindow() {
        val homeGrid = focusedId.get() == HOME || gridOpen.get()
        _distance.value = if (homeGrid) -SpatialLayout.ICON_Z else -SpatialLayout.PANEL_Z
        _panelHeight.value = if (homeGrid) IconGrid.fieldY() else SpatialLayout.PANEL_Y
        _panelScale.value = 1f
        _offsetX.value = SpatialLayout.PANEL_X
        publishWindow(yaw = 0f, pitch = 0f, roll = 0f)
    }

    private fun publishWindow(
        yaw: Float? = null,
        pitch: Float? = null,
        roll: Float? = null,
    ) {
        val prev = focusedPose()
        val window = SpatialWindow.fromControls(
            _distance.value,
            _panelHeight.value,
            _panelScale.value,
            _offsetX.value,
            contentAspect = when (focusedId.get()) {
                SCREEN -> screenAspect
                SpatialBrowser.ID -> SpatialBrowser.ASPECT
                DESKTOP -> {
                    val aspect = if (desktopPipeline.bufferW.get() > 0 && desktopPipeline.bufferH.get() > 0) {
                        desktopPipeline.bufferW.get().toFloat() / desktopPipeline.bufferH.get().toFloat()
                    } else {
                        16f / 9f
                    }
                    aspect
                }
                else -> SpatialLayout.PANEL_W / SpatialLayout.PANEL_H
            },
            yaw = yaw ?: prev.yaw,
            pitch = pitch ?: prev.pitch,
            roll = roll ?: prev.roll,
        )
        when (focusedId.get()) {
            SCREEN -> mirror.pose.set(window)
            SpatialBrowser.ID -> browser.pose.set(window)
            DESKTOP -> desktopPipeline.pose.set(window)
            STEAMVR -> steamVrPipeline.pose.set(window)
            HOME -> {
                windowRef.set(
                    window.copy(
                        z = SpatialLayout.ICON_Z,
                        width = IconGrid.fieldWidth(),
                        height = IconGrid.fieldHeight(),
                    ),
                )
                rebuildIcons()
            }
            else -> focusedLayer().pose.set(window)
        }
        persist()
    }

    private fun focusedPose(): SpatialWindow {
        return when (focusedId.get()) {
            SCREEN -> if (mirror.enabled.get()) mirror.pose.get() else SpatialWindow()
            SpatialBrowser.ID -> browser.pose.get()
            STEAMVR -> if (steamVrPipeline.enabled.get()) steamVrPipeline.pose.get() else SpatialWindow()
            DESKTOP -> if (desktopPipeline.enabled.get()) desktopPipeline.pose.get() else SpatialWindow()
            else -> if (gridOpen.get()) windowRef.get() else focusedLayer().pose.get()
        }
    }

    private fun focusedLayer(): SpatialLayer {
        val id = focusedId.get()
        return layers.firstOrNull { it.id == id } ?: layers.first()
    }

    private fun restorePrefs() {
        // Session always starts in 2D; user must toggle hardware 3D.
        _stereo.value = false
        stereoFlag.set(false)
        hardware3dFlag.set(false)
        val ipd = prefs.ipdMeters()
        _brightness.value = prefs.brightness()
        _ipd.value = ipd
        ipdRef.set(ipd)
        _distance.value = prefs.distance()
        _panelHeight.value = prefs.height()
        _panelScale.value = prefs.scale()
        _offsetX.value = prefs.offsetX()
        windowRef.set(
            SpatialWindow(
                y = IconGrid.fieldY(),
                z = SpatialLayout.ICON_Z,
                width = IconGrid.fieldWidth(),
                height = IconGrid.fieldHeight(),
            ),
        )
    }

    private fun persist() {
        prefs.save(
            stereo = _stereo.value,
            brightness = _brightness.value,
            ipdMeters = _ipd.value,
            distance = _distance.value,
            height = _panelHeight.value,
            scale = _panelScale.value,
            offsetX = _offsetX.value,
        )
    }

    fun focus(id: String) {
        if (id == HOME) {
            showGrid()
            return
        }
        if (id == SpatialBrowser.ID) {
            if (!browser.running) {
                openBrowser(SpatialBrowser.lastUrl)
                return
            }
            focusedId.set(SpatialBrowser.ID)
            _focused.value = SpatialBrowser.ID
            val pose = browser.pose.get()
            _distance.value = pose.distance
            _panelHeight.value = pose.y
            _panelScale.value = pose.width / SpatialLayout.PANEL_W
            _offsetX.value = pose.x
            _cursorX.value = browser.cursorX.get()
            _cursorY.value = browser.cursorY.get()
            return
        }
        if (id == SCREEN) {
            focusedId.set(SCREEN)
            _focused.value = SCREEN
            val pose = mirror.pose.get()
            _distance.value = pose.distance
            _panelHeight.value = pose.y
            _panelScale.value = if (screenAspect >= 1f) {
                pose.width / SpatialLayout.PANEL_W
            } else {
                pose.height / SpatialLayout.PANEL_H
            }
            _offsetX.value = pose.x
            _cursorX.value = mirror.cursorX.get()
            _cursorY.value = mirror.cursorY.get()
            return
        }
        if (id == DESKTOP) {
            focusedId.set(DESKTOP)
            _focused.value = DESKTOP
            val pose = desktopPipeline.pose.get()
            _distance.value = pose.distance
            _panelHeight.value = pose.y
            _panelScale.value = pose.width / SpatialLayout.PANEL_W
            _offsetX.value = pose.x
            _cursorX.value = desktopPipeline.cursorX.get()
            _cursorY.value = desktopPipeline.cursorY.get()
            return
        }
        if (id == STEAMVR) {
            focusedId.set(STEAMVR)
            _focused.value = STEAMVR
            val pose = steamVrPipeline.pose.get()
            _distance.value = pose.distance
            _panelHeight.value = pose.y
            _panelScale.value = pose.width / SpatialLayout.PANEL_W
            _offsetX.value = pose.x
            return
        }
        val layer = layers.firstOrNull { it.id == id } ?: return
        focusedId.set(id)
        _focused.value = id
        val pose = layer.pose.get()
        _distance.value = pose.distance
        _panelHeight.value = pose.y
        _panelScale.value = pose.width / SpatialLayout.PANEL_W
        _offsetX.value = pose.x
        _cursorX.value = layer.panel.cursorX.get()
        _cursorY.value = layer.panel.cursorY.get()
    }

    fun showGrid() {
        browser.cancelPointer()
        // Home icons must not be covered by a leftover SteamVR immersive layer.
        if (!steamVr.isConnected()) {
            steamVr.clearGlassesLayer()
        }
        gridOpen.set(true)
        focusedId.set(HOME)
        _focused.value = HOME
        collapseChrome()
        windowRef.set(
            SpatialWindow(
                y = IconGrid.fieldY(),
                z = SpatialLayout.ICON_Z,
                width = IconGrid.fieldWidth(),
                height = IconGrid.fieldHeight(),
            ),
        )
        val pose = windowRef.get()
        _distance.value = pose.distance
        _panelHeight.value = pose.y
        _offsetX.value = pose.x
        rebuildIcons()
        displays.requestFrameNow()
        appendLog(LogTags.UI, "apps grid")
        _imeVisible.value = false
    }

    fun openBrowser(url: String = SpatialBrowser.lastUrl) {
        val target = url.trim().ifBlank { SpatialBrowser.DEFAULT_URL }
        requestBrowserAudio?.invoke()
        gridOpen.set(false)
        browser.pose.set(
            SpatialWindow.fromControls(
                -SpatialLayout.PANEL_Z,
                SpatialLayout.PANEL_Y,
                1.12f,
                0f,
                SpatialBrowser.ASPECT,
            ),
        )
        browser.launch(target)
        focusedId.set(SpatialBrowser.ID)
        _focused.value = SpatialBrowser.ID
        val pose = browser.pose.get()
        _distance.value = pose.distance
        _panelHeight.value = pose.y
        _panelScale.value = pose.width / SpatialLayout.PANEL_W
        _offsetX.value = pose.x
        displays.requestFrameNow()
        appendLog(LogTags.BROWSER, "open $target")
    }

    fun launchFromGrid(component: String) {
        if (ShellApps.isScreen(component)) {
            haptics.click()
            openScreenMirror()
            return
        }
        if (ShellApps.isExtended(component)) {
            haptics.click()
            showGrid()
            return
        }
        if (ShellApps.isSteamVr(component)) {
            haptics.click()
            openSteamVr()
            return
        }
        if (ShellApps.isDesktop(component)) {
            haptics.click()
            openRemoteDesktop()
            return
        }
        val web = WebApps.find(component)
        if (web == null) {
            appendLog(LogTags.UI, "unknown app $component")
            return
        }
        haptics.click()
        openBrowser(web.url)
    }

    fun openSteamVr() {
        browser.cancelPointer()
        collapseChrome()
        if (steamVr.isConnected()) {
            focus(STEAMVR)
            gridOpen.set(false)
            displays.requestFrameNow()
            return
        }
        steamVr.openDialog()
    }

    fun closeSteamVrDialog() = steamVr.closeDialog()
    fun selectSteamVrHost(host: XrHost) = steamVr.select(host)
    fun setSteamVrIp(ip: String) = steamVr.setManualIp(ip)
    fun setSteamVrHeight(m: Float) = steamVr.setHeight(m)
    fun connectSteamVr() {
        // Force glasses ultrawide SBS before video (physical 3D button is broken).
        forceSteamVrHardware3d()
        steamVr.connect()
        viewModelScope.launch {
            val state = steamVr.ui.first { !it.busy }
            if (state.connected) {
                gridOpen.set(false)
                focus(STEAMVR)
                displays.requestFrameNow()
            }
        }
    }
    fun disconnectSteamVr() {
        steamVr.disconnect()
        showGrid()
    }

    fun openRemoteDesktop() {
        browser.cancelPointer()
        collapseChrome()
        if (remoteDesktop.isConnected()) {
            focus(DESKTOP)
            gridOpen.set(false)
            displays.requestFrameNow()
            return
        }
        remoteDesktop.openDialog()
    }

    fun closeRemoteDesktopDialog() = remoteDesktop.closeDialog()
    fun selectRemoteDesktopHost(host: XrHost) = remoteDesktop.select(host)
    fun setRemoteDesktopIp(ip: String) = remoteDesktop.setManualIp(ip)
    fun connectRemoteDesktop() {
        remoteDesktop.connect()
        viewModelScope.launch {
            val state = remoteDesktop.ui.first { !it.busy }
            if (state.connected) {
                gridOpen.set(false)
                focus(DESKTOP)
                displays.requestFrameNow()
            }
        }
    }
    fun disconnectRemoteDesktop() {
        remoteDesktop.disconnect()
        showGrid()
    }

    fun closeBrowser() {
        closeFocusedWindow()
    }

    fun closeFocusedWindow() {
        browser.cancelPointer()
        when (focusedId.get()) {
            SpatialBrowser.ID -> browser.stop()
            SCREEN -> stopMirroring()
            STEAMVR -> steamVr.disconnect()
            DESKTOP -> remoteDesktop.disconnect()
        }
        collapseChrome()
        val remaining = windowList()
        if (remaining.isEmpty()) {
            showGrid()
        } else {
            gridOpen.set(false)
            focus(remaining.last().first)
        }
        displays.requestFrameNow()
    }

    /** Hide the grid and show the phone-mirror spatial window. */
    fun openScreenMirror() {
        browser.cancelPointer()
        collapseChrome()
        if (mirror.enabled.get() || _mirroring.value) {
            focus(SCREEN)
            displays.requestFrameNow()
            appendLog(LogTags.MIRROR, "open screen window")
            return
        }
        appendLog(LogTags.MIRROR, "request screen capture for window")
        setMirroring(true)
    }

    private fun rebuildIcons() {
        val order = LauncherPins.gridOrder(_pinnedIds.value)
        val apps = ArrayList<IconSource>(order.size)
        for (component in order) {
            val shell = ShellApps.catalog.firstOrNull { it.component == component }
            if (shell != null) {
                val bmp = when {
                    ShellApps.isScreen(shell.component) -> BrandIcons.screenMirror()
                    ShellApps.isSteamVr(shell.component) -> BrandIcons.steamVr()
                    ShellApps.isDesktop(shell.component) -> BrandIcons.remoteDesktop()
                    else -> BrandIcons.extendedDisplay()
                }
                apps += IconSource(shell.component, bmp, name = shell.name)
                continue
            }
            val web = WebApps.find(component) ?: continue
            apps += IconSource(web.component, BrandIcons.load(getApplication(), web), name = web.name)
        }
        iconGrid.page.set(gridPage)
        iconGrid.pan.set(gridPan)
        iconGrid.pages.set(IconGrid.pageCount(apps.size))
        iconGrid.tiles.set(IconGrid.sprites(apps, gridPage, gridPan, windowRef.get()))
        iconGrid.generation.incrementAndGet()
    }

    fun setMoveScreen(enabled: Boolean) {
        _moveScreen.value = enabled
    }

    fun setInputMode(mode: InputMode) {
        if (_inputMode.value == mode) return
        _inputMode.value = mode
        if (mode == InputMode.TRACKPAD) {
            _moveScreen.value = false
        }
        if (mode == InputMode.POINTER) {
            setPointerAiming(true)
        } else {
            setPointerAiming(false)
        }
    }

    fun setPointerAiming(on: Boolean) {
        if (_pointerAiming.value == on) {
            if (on) calibratePointer()
            return
        }
        _pointerAiming.value = on
        if (on) {
            pointerNeedsCalibrate = true
            pointerBeam.set(PointerBeam(0f, 0f, SpatialLayout.PANEL_Z, true))
            startPhonePointer()
        } else {
            pointerBeam.set(PointerBeam(0f, 0f, -2f, false))
            if (!hubOwnsPointer) {
                phonePointer?.stop()
            }
            if (chrome.grabHandle.get() == null) {
                collapseChrome()
            }
        }
    }

    fun calibratePointer() {
        pointerNeedsCalibrate = true
    }

    fun onPhoneOrientation(yawDeg: Float, pitchDeg: Float, rollDeg: Float) {
        val hubGrab = chrome.grabHandle.get() == ChromeHandle.HUB && padHeld
        if (!pointerActive() && !hubGrab) return
        if (pointerNeedsCalibrate) {
            pointerZeroYaw = yawDeg
            pointerZeroPitch = pitchDeg
            pointerNeedsCalibrate = false
        }
        lastAimYaw = yawDeg
        lastAimPitch = pitchDeg
        lastAimRoll = rollDeg
        val dyaw = PoseSmoother.wrapDeg(yawDeg - pointerZeroYaw) * 0.78f
        val dpitch = PoseSmoother.wrapDeg(pitchDeg - pointerZeroPitch) * 0.78f
        val dir = PanelPick.rayFromYawPitchDeg(dyaw, dpitch)
        val grab = chrome.grabHandle.get()
        if (grab == ChromeHandle.HUB && padHeld) {
            lastHubDir = dir
            if (!grabPoseArmed) {
                armHubGrab(dir)
                return
            }
            applyHubSixDof()
            return
        }
        if (grab != null && padHeld) {
            val yawDelta = PoseSmoother.wrapDeg(yawDeg - grabYaw)
            val pitchDelta = PoseSmoother.wrapDeg(pitchDeg - grabPitch)
            applyGrab(yawDelta * 0.018f, pitchDelta * 0.028f)
            grabYaw = yawDeg
            grabPitch = pitchDeg
            return
        }
        aimScene(dir)
    }

    private fun aimScene(dir: FloatArray) {
        val windows = windowList()
        val expandedIds = setOfNotNull(chrome.expandedLayer.get())
        val chromeHit = if (windows.isNotEmpty()) {
            WindowChrome.pickMany(
                windows, expandedIds, 0f, 0f, 0f, dir,
                chrome.hoverLayer.get(), chrome.hoverHandle.get(),
            ) ?: WindowChrome.pickMany(
                windows, emptySet(), 0f, 0f, 0f, dir,
                chrome.hoverLayer.get(), chrome.hoverHandle.get(),
            )
        } else {
            null
        }
        val panelHit = if (windows.isNotEmpty()) PanelPick.pick(windows, dir) else null
        val iconSprite = if (gridOpen.get()) IconGrid.hit(iconGrid.tiles.get(), dir) else null
        val iconHit = iconSprite?.let {
            PanelPick.intersect(0f, 0f, 0f, dir, it.component, it.pose)
        }
        val chromeT = chromeHit?.let { hypot(it.x, hypot(it.y, it.z)) } ?: Float.MAX_VALUE
        val panelT = panelHit?.t ?: Float.MAX_VALUE
        val iconT = iconHit?.t ?: Float.MAX_VALUE
        val nearest = minOf(chromeT, panelT, iconT)
        if (nearest == Float.MAX_VALUE) {
            iconGrid.hoverComponent.set(null)
            collapseChrome()
            pointerBeam.set(PointerBeam(dir[0] * 4f, dir[1] * 4f, dir[2] * 4f, true))
            return
        }
        if (chromeHit != null && chromeT <= nearest + 1e-4f) {
            iconGrid.hoverComponent.set(null)
            if (focusedId.get() != chromeHit.layerId) focus(chromeHit.layerId)
            chrome.expandedLayer.set(chromeHit.layerId)
            chrome.hoverLayer.set(chromeHit.layerId)
            chrome.hoverHandle.set(chromeHit.handle)
            chrome.noteHover(chromeHit.handle, chromeHit.layerId)
            pointerBeam.set(PointerBeam(chromeHit.x, chromeHit.y, chromeHit.z, true))
            return
        }
        if (panelHit != null && panelT <= nearest + 1e-4f) {
            iconGrid.hoverComponent.set(null)
            if (focusedId.get() != panelHit.layerId) focus(panelHit.layerId)
            setCursor(panelHit.u, panelHit.v)
            if (panelHit.layerId == DESKTOP && remoteDesktop.isConnected()) {
                remoteDesktop.movePointer(panelHit.u, panelHit.v.coerceIn(0f, 1f))
            }
            val travel = hypot(panelHit.u - lastPointerU, panelHit.v - lastPointerV) * 420f
            haptics.onMove(travel)
            lastPointerU = panelHit.u
            lastPointerV = panelHit.v
            pointerBeam.set(PointerBeam(panelHit.x, panelHit.y, panelHit.z, true))
            val pose = windowOf(panelHit.layerId)
            val keep = if (pose != null) {
                val (lx, ly) = WindowChrome.localFromCursor(pose, panelHit.u, panelHit.v)
                WindowChrome.insideKeepAlive(pose, lx, ly)
            } else {
                false
            }
            if (!keep) collapseChrome()
            return
        }
        if (iconHit != null && iconSprite != null) {
            collapseChrome()
            iconGrid.hoverComponent.set(iconSprite.component)
            setCursor(iconHit.u, iconHit.v)
            pointerBeam.set(PointerBeam(iconHit.x, iconHit.y, iconHit.z, true))
            return
        }
        iconGrid.hoverComponent.set(null)
        collapseChrome()
        pointerBeam.set(PointerBeam(dir[0] * 4f, dir[1] * 4f, dir[2] * 4f, true))
    }

    fun padDown() {
        padHeld = true
        if (iconGrid.hoverComponent.get() != null && chrome.hoverHandle.get() == null) {
            return
        }
        val handle = chrome.hoverHandle.get()
        if (handle == null && focusedId.get() == SpatialBrowser.ID && _cursorY.value <= 1f) {
            if (pointerActive()) {
                browser.injectPointer(
                    MotionEvent.ACTION_DOWN,
                    _cursorX.value,
                    _cursorY.value.coerceIn(0f, 1f),
                )
            }
            return
        }
        if (handle == null && focusedId.get() == DESKTOP && remoteDesktop.isConnected()) {
            remoteDesktop.pointerDown(0f, 0f)
            return
        }
        val layer = chrome.hoverLayer.get() ?: chrome.expandedLayer.get()
        if (!chrome.canGrab(handle, layer)) return
        if (handle != null && layer != null) {
            if (focusedId.get() != layer) focus(layer)
            chrome.expandedLayer.set(layer)
            chrome.grabHandle.set(handle)
            grabPoseArmed = false
            grabYaw = lastAimYaw
            grabPitch = lastAimPitch
            grabRoll = lastAimRoll
            if (handle == ChromeHandle.HUB && !pointerActive()) {
                hubOwnsPointer = true
                pointerNeedsCalibrate = true
                startPhonePointer()
            }
            if (handle == ChromeHandle.HUB) {
                val pose = focusedPose()
                val face = SpatialWindow.facingUser(pose.x, pose.y, pose.z)
                publishWindow(yaw = face.first, pitch = face.second, roll = pose.roll)
            }
            haptics.click()
        }
    }

    fun padCancel() {
        chrome.grabHandle.set(null)
        padHeld = false
        grabPoseArmed = false
        lastHubDir = null
        if (hubOwnsPointer) {
            hubOwnsPointer = false
            if (!pointerActive()) phonePointer?.stop()
        }
        browser.cancelPointer()
        if (remoteDesktop.isConnected()) {
            remoteDesktop.pointerUp(_cursorX.value, _cursorY.value.coerceIn(0f, 1f))
        }
    }

    fun padUp(click: Boolean) {
        val paged = snapIconGridPage()
        val grab = chrome.grabHandle.getAndSet(null)
        padHeld = false
        grabPoseArmed = false
        lastHubDir = null
        val layer = chrome.hoverLayer.get() ?: chrome.expandedLayer.get()
        if (grab != null && layer != null) {
            chrome.lockAfterRelease(grab, layer)
        }
        if (hubOwnsPointer) {
            hubOwnsPointer = false
            if (!pointerActive()) phonePointer?.stop()
        }
        if (browser.pointerIsDown()) {
            browser.injectPointer(
                MotionEvent.ACTION_UP,
                _cursorX.value,
                _cursorY.value.coerceIn(0f, 1f),
            )
            return
        }
        if (focusedId.get() == DESKTOP && remoteDesktop.isConnected()) {
            remoteDesktop.pointerUp(0f, 0f)
            return
        }
        if (grab == ChromeHandle.CLOSE) {
            closeFocusedWindow()
            return
        }
        if (grab == ChromeHandle.BACK) {
            goBackOrHome()
            return
        }
        if (gridOpen.get() && iconGrid.hoverComponent.get() != null) {
            if (click && !paged) {
                val component = iconGrid.hoverComponent.get()
                    ?: IconGrid.nearest(iconGrid.tiles.get(), _cursorX.value, _cursorY.value, windowRef.get())?.component
                component?.let { launchFromGrid(it) }
            }
            return
        }
        if (grab == ChromeHandle.HUB && click) {
            chrome.hoverLayer.get()?.let { focus(it) }
            return
        }
        if (grab != null) return
        if (!click) return
        if (chrome.hoverHandle.get() != null) return
        if (_cursorY.value > 1f) return
        tapCursor()
    }

    fun nudgePad(dxNorm: Float, dyNorm: Float, pixels: Float = 0f) {
        val grab = chrome.grabHandle.get()
        if (grab == ChromeHandle.HUB) {
            _distance.value = (_distance.value - dyNorm * 2.8f)
                .coerceIn(SpatialWindow.DIST_MIN, SpatialWindow.DIST_MAX)
            applyHubSixDof()
            haptics.onMove(pixels)
            return
        }
        if (grab != null) {
            applyGrab(dxNorm, dyNorm)
            haptics.onMove(pixels)
            return
        }
        if (gridOpen.get() && focusedId.get() == HOME && chrome.grabHandle.get() == null) {
            nudgeCursor(dxNorm, dyNorm)
            updateChromeFromCursor()
            iconGrid.hoverComponent.set(
                IconGrid.nearest(iconGrid.tiles.get(), _cursorX.value, _cursorY.value, windowRef.get())?.component,
            )
            haptics.onMove(pixels)
            return
        }
        if (focusedId.get() == SpatialBrowser.ID && !_moveScreen.value) {
            nudgeCursor(dxNorm, dyNorm)
            updateChromeFromCursor()
            if (chrome.hoverHandle.get() != null) {
                haptics.onMove(pixels)
                return
            }
            if (browser.pointerIsDown()) {
                browser.injectPointer(
                    MotionEvent.ACTION_MOVE,
                    _cursorX.value,
                    _cursorY.value.coerceIn(0f, 1f),
                )
            }
            haptics.onMove(pixels)
            return
        }
        // Remote Desktop: phone trackpad = relative Windows mouse (laptop-style).
        if (focusedId.get() == DESKTOP && remoteDesktop.isConnected() && !_moveScreen.value) {
            if (chrome.hoverHandle.get() != null) {
                nudgeCursor(dxNorm, dyNorm)
                updateChromeFromCursor()
                haptics.onMove(pixels)
                return
            }
            val sens = 1100f
            val dx = (dxNorm * sens).toInt()
            val dy = (dyNorm * sens).toInt()
            remoteDesktop.moveRel(dx, dy)
            haptics.onMove(pixels)
            return
        }
        if (_moveScreen.value) {
            setOffsetX(_offsetX.value + dxNorm * 1.5f)
            setPanelHeight(_panelHeight.value - dyNorm * 0.9f)
            return
        }
        if (_pointerAiming.value) {
            return
        }
        nudgeCursor(dxNorm, dyNorm)
        updateChromeFromCursor()
        haptics.onMove(pixels)
    }

    fun padScroll(dxNorm: Float, dyNorm: Float) {
        val focused = focusedId.get()
        if (focused == SpatialBrowser.ID && browser.running) {
            browser.scrollBy(dxNorm * SpatialBrowser.WIDTH, dyNorm * SpatialBrowser.HEIGHT)
            return
        }
        if (focused == DESKTOP && remoteDesktop.isConnected()) {
            // Two-finger style: vertical pad scroll → mouse wheel
            val ticks = (-dyNorm * 8f).toInt()
            if (ticks != 0) remoteDesktop.wheel(ticks)
            return
        }
        if (focused == HOME && gridOpen.get() && chrome.grabHandle.get() == null) {
            panIconGrid(dxNorm)
        }
    }

    private fun panIconGrid(dxNorm: Float) {
        val pages = iconGrid.pages.get().coerceAtLeast(1)
        if (pages <= 1) return
        val stride = IconGrid.pageStride()
        gridPan += dxNorm * stride
        val minPan = if (gridPage >= pages - 1) 0f else -stride
        val maxPan = if (gridPage <= 0) 0f else stride
        gridPan = gridPan.coerceIn(minPan, maxPan)
        rebuildIcons()
        displays.requestFrameNow()
    }

    private fun snapIconGridPage(): Boolean {
        if (!gridOpen.get() || focusedId.get() != HOME) return false
        if (abs(gridPan) < 1e-4f) return false
        val moved = abs(gridPan) > IconGrid.TAP_PAN_MAX
        val pages = iconGrid.pages.get().coerceAtLeast(1)
        val snapped = IconGrid.clampPage(gridPage, gridPan, pages)
        val pageChanged = snapped.first != gridPage
        gridPage = snapped.first
        gridPan = snapped.second
        rebuildIcons()
        displays.requestFrameNow()
        return moved || pageChanged
    }

    fun padPinch(factor: Float) {
        if (focusedId.get() == SpatialBrowser.ID && browser.running) {
            browser.zoomBy(factor)
            return
        }
        zoomDistance((1f - factor) * 1.4f)
    }

    fun padThreeSwipe(dir: Float) {
        if (focusedId.get() != SpatialBrowser.ID || !browser.running) return
        if (dir < 0f) {
            browser.injectKey(android.view.KeyEvent.KEYCODE_BACK)
        } else {
            browser.goForward()
        }
        haptics.click()
    }

    fun setImeText(text: String) {
        _imeText.value = text
        browser.setFocusedText(text)
    }

    fun showIme() {
        _imeVisible.value = true
    }

    fun hideIme() {
        _imeVisible.value = false
    }

    fun toggleIme() {
        _imeVisible.value = !_imeVisible.value
    }

    fun setIconPinned(component: String, pinned: Boolean) {
        _pinnedIds.value = launcherPrefs.setPinned(component, pinned)
        rebuildIcons()
        displays.requestFrameNow()
    }

    fun goBackOrHome() {
        haptics.click()
        if (focusedId.get() == SpatialBrowser.ID && browser.running) {
            browser.goBack { closeFocusedWindow() }
            return
        }
        showGrid()
    }

    fun zoomDistance(dyNorm: Float) {
        _distance.value = (_distance.value - dyNorm * 2.4f)
            .coerceIn(SpatialWindow.DIST_MIN, SpatialWindow.DIST_MAX)
        if (chrome.grabHandle.get() == ChromeHandle.HUB) {
            applyHubSixDof()
            return
        }
        publishWindow()
    }

    fun nudgeCursor(dxNorm: Float, dyNorm: Float) {
        setCursor(
            (_cursorX.value + dxNorm).coerceIn(0f, 1f),
            (_cursorY.value + dyNorm).coerceIn(0f, WindowChrome.CURSOR_V_MAX),
        )
    }

    fun setCursor(u: Float, v: Float) {
        val x = u.coerceIn(0f, 1f)
        val y = v.coerceIn(0f, WindowChrome.CURSOR_V_MAX)
        _cursorX.value = x
        _cursorY.value = y
        val panelV = y
        if (focusedId.get() == SCREEN) {
            mirror.cursorX.set(x)
            mirror.cursorY.set(panelV)
        } else if (focusedId.get() == SpatialBrowser.ID) {
            browser.cursorX.set(x)
            browser.cursorY.set(panelV)
        } else if (focusedId.get() == DESKTOP) {
            desktopPipeline.cursorX.set(x)
            desktopPipeline.cursorY.set(panelV)
        } else {
            val fb = focusedLayer().panel
            fb.cursorX.set(x)
            fb.cursorY.set(panelV)
        }
    }

    fun tapCursor() {
        if (_cursorY.value > 1f) return
        val icon = iconGrid.hoverComponent.get()
        if (gridOpen.get() && icon != null) {
            icon.let { launchFromGrid(it) }
            return
        }
        if (focusedId.get() == SpatialBrowser.ID) {
            haptics.click()
            browser.injectPointer(
                MotionEvent.ACTION_DOWN,
                _cursorX.value,
                _cursorY.value.coerceIn(0f, 1f),
            )
            browser.injectPointer(
                MotionEvent.ACTION_UP,
                _cursorX.value,
                _cursorY.value.coerceIn(0f, 1f),
            )
            return
        }
        if (focusedId.get() == DESKTOP && remoteDesktop.isConnected()) {
            haptics.click()
            remoteDesktop.trackpadClick()
            return
        }
        haptics.click()
        tapHandler?.invoke(_cursorX.value, _cursorY.value.coerceIn(0f, 1f))
    }

    private fun pointerActive(): Boolean = _pointerAiming.value

    private fun windowList(): List<Pair<String, SpatialWindow>> {
        return buildList {
            if (browser.running) add(SpatialBrowser.ID to browser.pose.get())
            if (mirror.enabled.get()) add(SCREEN to mirror.pose.get())
            if (desktopPipeline.enabled.get()) add(DESKTOP to desktopPipeline.pose.get())
            // SteamVR immersive is not a pickable floating panel.
        }
    }

    private fun windowOf(id: String): SpatialWindow? {
        if (id == SCREEN) return if (mirror.enabled.get()) mirror.pose.get() else null
        if (id == SpatialBrowser.ID) return if (browser.running) browser.pose.get() else null
        if (id == DESKTOP) return if (desktopPipeline.enabled.get()) desktopPipeline.pose.get() else null
        if (id == STEAMVR) return if (steamVrPipeline.enabled.get()) steamVrPipeline.pose.get() else null
        return layers.firstOrNull { it.id == id }?.pose?.get()
    }

    private fun updateChromeFromCursor() {
        val id = focusedId.get()
        val window = windowOf(id) ?: return
        val (lx, ly) = WindowChrome.localFromCursor(window, _cursorX.value, _cursorY.value)
        val wasExpanded = chrome.expandedLayer.get() == id
        val onHub = WindowChrome.nearHub(window, lx, ly)
        val keep = wasExpanded && WindowChrome.insideKeepAlive(window, lx, ly)
        val open = onHub || keep || WindowChrome.approachingDock(_cursorY.value)
        val onIcon = WindowChrome.hitAt(
            window, open || wasExpanded, lx, ly, grabbed = false, chrome.hoverHandle.get(),
        )
        if (open || onIcon != null) {
            chrome.expandedLayer.set(id)
            chrome.hoverLayer.set(id)
            chrome.hoverHandle.set(onIcon)
            chrome.noteHover(onIcon, id)
        } else if (chrome.grabHandle.get() == null) {
            collapseChrome()
        }
    }

    private fun collapseChrome() {
        chrome.expandedLayer.set(null)
        chrome.hoverLayer.set(null)
        chrome.hoverHandle.set(null)
        chrome.clearLockout()
    }

    private fun armHubGrab(dir: FloatArray) {
        val pose = focusedPose()
        val dz = dir[2]
        val t = if (abs(dz) < 0.08f) pose.distance else (-pose.z) / dz
        hubGrabOffX = pose.x - dir[0] * t
        hubGrabOffY = pose.y - dir[1] * t
        hubGrabWinRoll = pose.roll
        hubGrabPhoneRoll = lastAimRoll
        grabPoseArmed = true
    }

    private fun applyHubSixDof() {
        val dist = _distance.value
        val dir = lastHubDir
        if (dir != null) {
            val dz = dir[2]
            if (abs(dz) >= 0.08f) {
                val t = -dist / dz
                if (t >= 0.4f) {
                    _offsetX.value = (dir[0] * t + hubGrabOffX)
                        .coerceIn(SpatialWindow.OFFSET_MIN, SpatialWindow.OFFSET_MAX)
                    _panelHeight.value = (dir[1] * t + hubGrabOffY)
                        .coerceIn(SpatialWindow.HEIGHT_MIN, SpatialWindow.HEIGHT_MAX)
                }
            }
        }
        val face = SpatialWindow.facingUser(_offsetX.value, _panelHeight.value, -dist)
        val roll = hubGrabWinRoll -
            PoseSmoother.wrapDeg(lastAimRoll - hubGrabPhoneRoll) * 0.016f
        publishWindow(yaw = face.first, pitch = face.second, roll = roll)
        val pose = focusedPose()
        pointerBeam.set(PointerBeam(pose.x, pose.y, pose.z, true))
    }

    private fun applyGrab(dxNorm: Float, dyNorm: Float) {
        when (chrome.grabHandle.get()) {
            ChromeHandle.SIZE -> setPanelScale(_panelScale.value - dyNorm * 1.4f)
            ChromeHandle.DISTANCE -> setDistance(_distance.value - dyNorm * 2.8f)
            ChromeHandle.HEIGHT -> setPanelHeight(_panelHeight.value - dyNorm * 0.9f)
            ChromeHandle.OFFSET -> setOffsetX(_offsetX.value + dxNorm * 1.6f)
            ChromeHandle.PITCH -> {
                val prev = focusedPose()
                publishWindow(pitch = prev.pitch - dyNorm * 0.55f)
            }
            ChromeHandle.YAW -> {
                val prev = focusedPose()
                publishWindow(yaw = prev.yaw + dxNorm * 0.70f)
            }
            ChromeHandle.CLOSE, ChromeHandle.BACK -> Unit
            ChromeHandle.HUB -> {
                if (lastHubDir != null) {
                    hubGrabOffX += dxNorm * 1.5f
                    _distance.value = (_distance.value - dyNorm * 2.8f)
                        .coerceIn(SpatialWindow.DIST_MIN, SpatialWindow.DIST_MAX)
                    applyHubSixDof()
                } else {
                    setOffsetX(_offsetX.value + dxNorm * 1.5f)
                    setPanelHeight(_panelHeight.value - dyNorm * 0.9f)
                }
            }
            null -> Unit
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun clearAnchor() {
        synchronized(poseLock) {
            worldLock.set(false)
        }
        _worldLocked.value = false
        displays.publishPose(Orientation.IDENTITY)
    }

    private fun appendLog(tag: String, message: String) {
        val entry = LogEntry(
            id = nextLogId.incrementAndGet(),
            timeMs = System.currentTimeMillis(),
            tag = shortTag(tag),
            message = message,
        )
        _logs.update { current ->
            val next = current + entry
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }

    private fun shortTag(tag: String): String {
        val slash = tag.lastIndexOf('/')
        return if (slash >= 0) tag.substring(slash + 1) else tag
    }

    private fun startPhonePointer() {
        if (phonePointer == null) {
            phonePointer = PhonePointer(getApplication()) { yaw, pitch, roll ->
                onPhoneOrientation(yaw, pitch, roll)
            }
        }
        phonePointer?.start()
    }

    override fun onCleared() {
        tapHandler = null
        startMirrorCapture = null
        requestBrowserAudio = null
        mirror.onLog = null
        mirror.onCaptureSize = null
        setPointerAiming(false)
        stopMirroring()
        browser.stop()
        steamVr.disconnect()
        remoteDesktop.disconnect()
        phonePointer?.stop()
        phonePointer = null
        pointerBeam.set(PointerBeam(0f, 0f, -2f, false))
        client.onGlassesPose = null
        displays.unbind()
        client.release()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_LINES = 400
        const val HOME = "home"
        const val NOTES = "notes"
        const val SCREEN = MirrorPipeline.ID
        const val STEAMVR = MirrorPipeline.STEAMVR_ID
        const val DESKTOP = MirrorPipeline.DESKTOP_ID
        const val BROWSER = SpatialBrowser.ID
    }
}
