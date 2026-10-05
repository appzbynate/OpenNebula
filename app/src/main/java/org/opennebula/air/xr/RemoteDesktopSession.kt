package org.opennebula.air.xr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        onFrameSize = { w, h -> applyContentSize(w, h) },
    )
    private val input = XrInputClient(scope = scope, onLog = onLog)
    private var connectJob: Job? = null
    @Volatile private var buttons = 0
    private var lastContentW = 0
    private var lastContentH = 0

    /** Native-ish content aspect from stream; rebuilds panel if monitor mode changes. */
    private fun applyContentSize(pixelW: Int, pixelH: Int) {
        val w = pixelW.coerceAtLeast(2)
        val h = pixelH.coerceAtLeast(2)
        if (w == lastContentW && h == lastContentH) return
        lastContentW = w
        lastContentH = h
        val aspect = w.toFloat() / h.toFloat()
        val prev = pipeline.pose.get()
        val scale = if (aspect >= 1f) {
            (prev.width / SpatialLayout.PANEL_W).coerceIn(SpatialWindow.SCALE_MIN, SpatialWindow.SCALE_MAX)
        } else {
            (prev.height / SpatialLayout.PANEL_H).coerceIn(SpatialWindow.SCALE_MIN, SpatialWindow.SCALE_MAX)
        }
        val width: Float
        val heightM: Float
        if (aspect >= 1f) {
            width = SpatialLayout.PANEL_W * scale
            heightM = width / aspect.coerceIn(0.30f, 4.0f)
        } else {
            heightM = SpatialLayout.PANEL_H * scale
            width = heightM * aspect.coerceIn(0.30f, 4.0f)
        }
        // Size only — never rewrite x/y/z/yaw/pitch (that fought the pose lock / head).
        pipeline.pose.set(prev.copy(width = width, height = heightM))
        onLog("desktop panel ${w}x$h aspect=${"%.3f".format(aspect)}")
    }

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
                    0f,
                    16f / 9f,
                ),
            )
            lastContentW = 0
            lastContentH = 0
            showStatusPanel("Windows Desktop", "Linking to ${host.name}…")
            pipeline.setActive(true)
            input.connect(host.address, host.controlPort)
            video.start(host.address, XrBridgeProtocol.DESKTOP_VIDEO_PORT)
            val inputUp = withContext(Dispatchers.IO) {
                repeat(40) {
                    if (input.isConnected()) return@withContext true
                    delay(50)
                }
                input.isConnected()
            }
            if (inputUp) {
                // Visible proof the control path works (cursor wiggles once).
                input.moveRel(48, 0, buttons = -1)
                delay(80)
                input.moveRel(-48, 0, buttons = -1)
                onLog("mouse pulse OK — swipe trackpad to move PC cursor")
            } else {
                onLog("control socket DOWN — bridge :${host.controlPort} not accepting input")
            }
            _ui.value = _ui.value.copy(
                connected = true,
                busy = false,
                selected = host,
                open = false,
                status = if (inputUp) {
                    "Connected — trackpad moves the PC mouse"
                } else {
                    "Video up but control socket failed — restart bridge"
                },
            )
            onLog("Remote Desktop connected ${host.address} input=${if (inputUp) "up" else "DOWN"}")
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        buttons = 0
        lastContentW = 0
        lastContentH = 0
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

    fun setRefreshHz(hz: Int) {
        input.setRefreshHz(hz)
    }

    fun isConnected(): Boolean = _ui.value.connected

    fun movePointer(u: Float, v: Float) {
        if (!_ui.value.connected) return
        input.pointer(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), buttons, 0)
    }

    /** Laptop-style trackpad: relative mouse deltas in screen pixels (no button sync). */
    fun moveRel(dx: Int, dy: Int) {
        if (!_ui.value.connected) {
            onLog("trackpad ignored — remote desktop not connected")
            return
        }
        if (dx == 0 && dy == 0) return
        if (!input.isConnected()) {
            onLog("trackpad: control socket down — reconnect Remote Desktop / start_bridge.bat")
            return
        }
        input.moveRel(dx, dy, buttons = -1)
    }

    fun wheel(delta: Int) {
        if (!_ui.value.connected || delta == 0) return
        input.sendWheel(delta)
    }

    fun hWheel(delta: Int) {
        if (!_ui.value.connected || delta == 0) return
        input.sendHWheel(delta)
    }

    fun zoom(ticks: Int) {
        if (!_ui.value.connected || ticks == 0) return
        input.sendZoom(ticks)
    }

    fun chord(vararg vk: Int) {
        if (!_ui.value.connected || vk.isEmpty()) return
        input.sendChord(*vk)
    }

    fun pointerDown(u: Float, v: Float, button: Int = 0) {
        if (!_ui.value.connected) return
        buttons = buttons or (1 shl button.coerceIn(0, 2))
        input.moveRel(0, 0, buttons)
    }

    fun pointerUp(u: Float, v: Float, button: Int = 0) {
        if (!_ui.value.connected) return
        buttons = buttons and (1 shl button.coerceIn(0, 2)).inv()
        input.moveRel(0, 0, buttons)
    }

    fun trackpadClick(button: Int = 0) {
        if (!_ui.value.connected) return
        val bit = 1 shl button.coerceIn(0, 2)
        buttons = buttons or bit
        input.moveRel(0, 0, buttons)
        buttons = buttons and bit.inv()
        input.moveRel(0, 0, buttons)
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
