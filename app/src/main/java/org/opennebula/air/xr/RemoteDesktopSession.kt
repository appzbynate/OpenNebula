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
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import java.net.InetSocketAddress
import java.net.Socket

data class RemoteDesktopUiState(
    val open: Boolean = false,
    val hosts: List<XrHost> = emptyList(),
    val selected: XrHost? = null,
    val manualIp: String = "",
    val status: String = "On your PC run:  tools\\xr-bridge\\start_bridge.bat",
    val connected: Boolean = false,
    val busy: Boolean = false,
)

/**
 * Floating Windows desktop panel over the XR bridge (not immersive SteamVR).
 * Video :38404, pointer/keys on control :38403.
 */
class RemoteDesktopSession(
    context: android.content.Context,
    private val scope: CoroutineScope,
    val pipeline: MirrorPipeline,
    private val onLog: (String) -> Unit,
    private val onExitToHome: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val _ui = MutableStateFlow(RemoteDesktopUiState())
    val ui: StateFlow<RemoteDesktopUiState> = _ui.asStateFlow()

    private val discovery = XrDiscovery(
        context = appContext,
        scope = scope,
        onHosts = { list -> _ui.value = _ui.value.copy(hosts = list) },
        onLog = onLog,
    )
    private val video = XrVideoClient(
        scope = scope,
        pipeline = pipeline,
        onLog = onLog,
        onStatus = { msg -> _ui.value = _ui.value.copy(status = msg) },
        onFailed = { err ->
            scope.launch(Dispatchers.Main) { failConnect(err) }
        },
    )
    private val input = XrInputClient(scope = scope, onLog = onLog)
    private var connectJob: Job? = null
    @Volatile private var buttons = 0

    fun openDialog() {
        if (!_ui.value.connected) clearLayer()
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
            status = "Checking $ip:${XrBridgeProtocol.DESKTOP_VIDEO_PORT}…",
        )
        connectJob = scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, XrBridgeProtocol.DESKTOP_VIDEO_PORT), 2500)
                    }
                    true
                }.getOrDefault(false)
            }
            if (!reachable) {
                clearLayer()
                _ui.value = _ui.value.copy(
                    busy = false,
                    connected = false,
                    open = true,
                    status = "No desktop stream at $ip — run start_bridge.bat on the PC",
                )
                onLog("Desktop: no video at $ip:${XrBridgeProtocol.DESKTOP_VIDEO_PORT}")
                onExitToHome()
                return@launch
            }
            val host = _ui.value.selected?.takeIf { it.address == ip }
                ?: XrHost(name = ip, address = ip)
            pipeline.pose.set(
                SpatialWindow.fromControls(
                    -SpatialLayout.PANEL_Z,
                    SpatialLayout.PANEL_Y,
                    1.25f,
                    -1.60f,
                    9f / 16f,
                ),
            )
            showStatusPanel("Windows Desktop", "Linking to ${host.name}…")
            pipeline.setActive(true)
            input.connect(host.address, host.controlPort)
            video.start(host.address, XrBridgeProtocol.DESKTOP_VIDEO_PORT)
            _ui.value = _ui.value.copy(
                connected = true,
                busy = false,
                selected = host,
                open = false,
                status = "Connected — aim + tap to click",
            )
            onLog("Remote Desktop connected ${host.address}")
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        buttons = 0
        video.stop()
        input.disconnect()
        clearLayer()
        discovery.stop()
        _ui.value = _ui.value.copy(
            connected = false,
            busy = false,
            open = false,
            status = "Disconnected — run start_bridge.bat to reconnect",
        )
        onLog("Remote Desktop stopped")
    }

    fun clearLayer() {
        pipeline.setActive(false)
        pipeline.closeCapture()
    }

    fun isConnected(): Boolean = _ui.value.connected

    fun movePointer(u: Float, v: Float) {
        if (!_ui.value.connected) return
        input.pointer(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), buttons, 0)
    }

    fun pointerDown(u: Float, v: Float, button: Int = 0) {
        if (!_ui.value.connected) return
        buttons = buttons or (1 shl button.coerceIn(0, 2))
        input.pointer(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), buttons, 0)
    }

    fun pointerUp(u: Float, v: Float, button: Int = 0) {
        if (!_ui.value.connected) return
        buttons = buttons and (1 shl button.coerceIn(0, 2)).inv()
        input.pointer(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), buttons, 0)
    }

    fun click(u: Float, v: Float, button: Int = 0) {
        if (!_ui.value.connected) return
        input.click(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), button)
    }

    private fun failConnect(err: String) {
        connectJob?.cancel()
        connectJob = null
        buttons = 0
        video.stop()
        input.disconnect()
        clearLayer()
        discovery.stop()
        _ui.value = _ui.value.copy(
            connected = false,
            busy = false,
            open = true,
            status = err,
        )
        onLog("Desktop video lost: $err")
        onExitToHome()
    }

    private fun showStatusPanel(title: String, body: String) {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1A2332.toInt())
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
