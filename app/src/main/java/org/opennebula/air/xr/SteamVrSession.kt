package org.opennebula.air.xr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opennebula.air.imu.Orientation
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import java.net.InetSocketAddress
import java.net.Socket

data class SteamVrUiState(
    val open: Boolean = false,
    val hosts: List<XrHost> = emptyList(),
    val selected: XrHost? = null,
    val manualIp: String = "",
    val heightM: Float = 1.60f,
    val status: String = "On your PC run:  tools\\xr-bridge\\start_bridge.bat",
    val connected: Boolean = false,
    val busy: Boolean = false,
)

/**
 * Nebula-style SteamVR session. Only covers the glasses after the user taps
 * SteamVR → Go. Failures return to the home icon grid (never stick on "Video lost").
 */
class SteamVrSession(
    context: android.content.Context,
    private val scope: CoroutineScope,
    val pipeline: MirrorPipeline,
    private val onLog: (String) -> Unit,
    private val onExitToHome: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val _ui = MutableStateFlow(SteamVrUiState())
    val ui: StateFlow<SteamVrUiState> = _ui.asStateFlow()

    private val discovery = XrDiscovery(
        context = appContext,
        scope = scope,
        onHosts = { list -> _ui.value = _ui.value.copy(hosts = list) },
        onLog = onLog,
    )
    private val pose = XrPoseStreamer().also { it.onLog = onLog }
    private val video = XrVideoClient(
        scope = scope,
        pipeline = pipeline,
        onLog = onLog,
        onStatus = { msg -> _ui.value = _ui.value.copy(status = msg) },
        onFailed = { err ->
            scope.launch(Dispatchers.Main) {
                failConnect(err)
            }
        },
    )
    private var connectJob: Job? = null

    fun openDialog() {
        // Never leave a stale immersive layer from a previous attempt.
        if (!_ui.value.connected) {
            clearGlassesLayer()
        }
        _ui.value = _ui.value.copy(open = true)
        discovery.start()
    }

    fun closeDialog() {
        _ui.value = _ui.value.copy(open = false)
        if (!_ui.value.connected) discovery.stop()
    }

    fun setManualIp(ip: String) {
        _ui.value = _ui.value.copy(manualIp = ip)
    }

    fun setHeight(m: Float) {
        val h = m.coerceIn(1.0f, 2.2f)
        pose.heightM = h
        _ui.value = _ui.value.copy(heightM = h)
    }

    fun select(host: XrHost) {
        _ui.value = _ui.value.copy(selected = host, manualIp = host.address)
    }

    fun connect() {
        val ip = _ui.value.manualIp.trim().ifBlank {
            _ui.value.selected?.address.orEmpty()
        }
        if (ip.isBlank()) {
            _ui.value = _ui.value.copy(status = "Enter your PC IP address")
            return
        }
        connectJob?.cancel()
        _ui.value = _ui.value.copy(
            busy = true,
            status = "Checking $ip:${XrBridgeProtocol.VIDEO_PORT}…",
        )
        connectJob = scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, XrBridgeProtocol.VIDEO_PORT), 2500)
                    }
                    true
                }.getOrDefault(false)
            }
            if (!reachable) {
                clearGlassesLayer()
                _ui.value = _ui.value.copy(
                    busy = false,
                    connected = false,
                    open = true,
                    status = "No companion at $ip — run start_bridge.bat on the PC",
                )
                onLog("SteamVR: companion not reachable at $ip:${XrBridgeProtocol.VIDEO_PORT}")
                onExitToHome()
                return@launch
            }
            val host = _ui.value.selected?.takeIf { it.address == ip }
                ?: XrHost(name = ip, address = ip)
            _ui.value = _ui.value.copy(status = "Starting SteamVR on PC…")
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, host.controlPort), 2500)
                        s.getOutputStream().write("LAUNCH\n".toByteArray())
                        s.getOutputStream().flush()
                        s.soTimeout = 3000
                        s.getInputStream().read(ByteArray(128))
                    }
                }
            }
            pose.heightM = _ui.value.heightM
            pose.start(host.address, host.posePort)
            pipeline.pose.set(
                SpatialWindow.fromControls(
                    -SpatialLayout.PANEL_Z,
                    SpatialLayout.PANEL_Y,
                    1.35f,
                    -1.70f,
                    9f / 16f,
                ),
            )
            // Only now cover the glasses — user explicitly started SteamVR.
            showStatusPanel("Linked to ${host.name}", "Opening SteamVR Home on PC…")
            pipeline.setActive(true)
            video.start(host.address, host.videoPort)
            _ui.value = _ui.value.copy(
                connected = true,
                busy = false,
                selected = host,
                open = false,
                status = "Connected — SteamVR launching on PC",
            )
            onLog("SteamVR bridge connected ${host.address}")
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        video.stop()
        pose.stop()
        clearGlassesLayer()
        discovery.stop()
        _ui.value = _ui.value.copy(
            connected = false,
            busy = false,
            open = false,
            status = "Disconnected — run start_bridge.bat on PC to reconnect",
        )
        onLog("SteamVR bridge stopped")
    }

    /** Drop any immersive SteamVR layer so the home icon grid can show. */
    fun clearGlassesLayer() {
        pipeline.setActive(false)
        pipeline.closeCapture()
    }

    fun isConnected(): Boolean = _ui.value.connected

    fun onPose(orientation: Orientation) {
        if (_ui.value.connected) pose.send(orientation)
    }

    private fun failConnect(err: String) {
        connectJob?.cancel()
        connectJob = null
        video.stop()
        pose.stop()
        clearGlassesLayer()
        discovery.stop()
        _ui.value = _ui.value.copy(
            connected = false,
            busy = false,
            open = true,
            status = err,
        )
        onLog("SteamVR video lost: $err")
        onExitToHome()
    }

    private fun showStatusPanel(title: String, body: String) {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF152033.toInt())
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8EEF8.toInt()
            textSize = 54f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C4D6.toInt()
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText(title, 64f, 140f, titlePaint)
        var y = 220f
        for (line in body.split('\n')) {
            canvas.drawText(line, 64f, y, bodyPaint)
            y += 52f
        }
        pipeline.pushBitmap(bmp)
        pipeline.setActive(true)
    }
}
