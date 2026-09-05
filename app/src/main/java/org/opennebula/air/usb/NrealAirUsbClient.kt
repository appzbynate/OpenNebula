package org.opennebula.air.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opennebula.air.BuildConfig
import org.opennebula.air.imu.AirImuBias
import org.opennebula.air.imu.ImuParser
import org.opennebula.air.imu.ImuTrace
import org.opennebula.air.imu.ImuSample
import org.opennebula.air.imu.MadgwickImu
import org.opennebula.air.imu.Orientation
import org.opennebula.air.imu.Vec3f
import org.opennebula.air.imu.toHex
import org.opennebula.air.imu.toLogLine
import org.opennebula.air.logging.LogTags
import org.opennebula.air.protocol.NrealAirProtocol
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class NrealAirUsbClient(
    context: Context,
    private val scope: CoroutineScope,
    private val onLog: (tag: String, message: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Disconnected)
    val state: StateFlow<GlassesConnectionState> = _state.asStateFlow()

    private val _latestSample = MutableStateFlow<ImuSample?>(null)
    val latestSample: StateFlow<ImuSample?> = _latestSample.asStateFlow()

    private val _orientation = MutableStateFlow(Orientation.IDENTITY)
    val orientation: StateFlow<Orientation> = _orientation.asStateFlow()

    private val fusion = MadgwickImu()
    private val diagDir = File(appContext.getExternalFilesDir(null), "diag")
    private val imuTrace = ImuTrace(diagDir, BuildConfig.VERSION_NAME)
    @Volatile private var imuBias = AirImuBias.ZERO
    @Volatile var onGlassesPose: ((Orientation) -> Unit)? = null
    @Volatile var onImuDebug: ((String) -> Unit)? = null
    private val poseEpoch = AtomicInteger(0)

    fun lastGyroRadS(): Vec3f = fusion.lastGyroRadS()
    fun biasReady(): Boolean = fusion.biasReady()
    fun cycleImuAxisMode(): String {
        ImuParser.resetAirAxisMode()
        return ImuParser.AIR_AXIS_LABEL
    }

    private var permissionReceiver: BroadcastReceiver? = null
    private var detachReceiver: BroadcastReceiver? = null
    private var readerJob: Job? = null
    private var readerExecutor: ExecutorService? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    @Volatile private var readerRunning = false

    fun connect() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (_state.value !is GlassesConnectionState.Disconnected) {
                    emit(LogTags.USB, "Connect ignored: already ${_state.value}")
                    return@withLock
                }
                _state.value = GlassesConnectionState.Connecting
            }
            try {
                ensureReceivers()
                val device = findAir()
                if (device == null) {
                    emit(LogTags.USB, "Nreal Air not found (VID=0x%04X PID=0x%04X). Devices: %s".format(
                        NrealAirProtocol.VENDOR_ID,
                        NrealAirProtocol.PRODUCT_ID,
                        describeDeviceList(),
                    ))
                    _state.value = GlassesConnectionState.Disconnected
                    return@launch
                }
                emit(
                    LogTags.USB,
                    "Found ${device.deviceName} ${runCatching { device.productName }.getOrNull() ?: "Nreal Air"} " +
                        "vid=0x%04X pid=0x%04X ifaces=${device.interfaceCount}".format(
                            device.vendorId,
                            device.productId,
                        ),
                )
                dumpInterfaces(device)
                if (!usbManager.hasPermission(device)) {
                    emit(LogTags.PERM, "Requesting USB permission")
                    _state.value = GlassesConnectionState.AwaitingPermission
                    requestPermission(device)
                    return@launch
                }
                openAndStream(device)
            } catch (t: Throwable) {
                emit(LogTags.USB, "connect failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(LogTags.USB, "connect", t)
                _state.value = GlassesConnectionState.Disconnected
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { closeLocked("user disconnect") }
        }
    }

    fun recenter() {
        fusion.recenter()
        poseEpoch.incrementAndGet()
        _orientation.value = Orientation.IDENTITY
        onGlassesPose?.invoke(Orientation.IDENTITY)
        emit(LogTags.POSE, "recentered")
    }

    fun release() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { closeLocked("release") }
            unregisterReceivers()
        }
    }

    private fun findAir(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { isNrealAir(it) }
    }

    private fun isNrealAir(device: UsbDevice): Boolean {
        return device.vendorId == NrealAirProtocol.VENDOR_ID &&
            device.productId == NrealAirProtocol.PRODUCT_ID
    }

    private fun describeDeviceList(): String {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) return "(none)"
        return devices.joinToString { d ->
            "${d.deviceName} vid=0x%04X pid=0x%04X".format(d.vendorId, d.productId)
        }
    }

    private fun dumpInterfaces(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val eps = (0 until iface.endpointCount).joinToString { e ->
                val ep = iface.getEndpoint(e)
                "ep=0x%02X %s %s max=%d int=%d".format(
                    ep.address,
                    xferName(ep.type),
                    if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                    ep.maxPacketSize,
                    ep.interval,
                )
            }
            emit(
                LogTags.USB,
                "iface $i class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} " +
                    "proto=${iface.interfaceProtocol} $eps",
            )
        }
    }

    private fun xferName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
        else -> "T$type"
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(appContext.packageName)
        }
        val flags = PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pending)
    }

    private fun ensureReceivers() {
        if (permissionReceiver == null) {
            permissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = extraDevice(intent)
                    emit(LogTags.PERM, "Permission result granted=$granted device=${device?.deviceName}")
                    if (!granted || device == null || !isNrealAir(device)) {
                        _state.value = GlassesConnectionState.Disconnected
                        return
                    }
                    scope.launch(Dispatchers.IO) { openAndStream(device) }
                }
            }
            register(permissionReceiver!!, IntentFilter(ACTION_USB_PERMISSION))
        }
        if (detachReceiver == null) {
            detachReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                    val device = extraDevice(intent) ?: return
                    if (!isNrealAir(device)) return
                    emit(LogTags.USB, "USB detached")
                    scope.launch(Dispatchers.IO) {
                        mutex.withLock { closeLocked("detached") }
                    }
                }
            }
            register(detachReceiver!!, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        }
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceivers() {
        permissionReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        detachReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        permissionReceiver = null
        detachReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun extraDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private suspend fun openAndStream(device: UsbDevice) {
        mutex.withLock {
            closeLocked("reopen")
            try {
                val conn = usbManager.openDevice(device)
                if (conn == null) {
                    emit(LogTags.USB, "openDevice returned null")
                    _state.value = GlassesConnectionState.Disconnected
                    return
                }
                val claimed = claimImuInterface(device, conn)
                if (claimed == null) {
                    conn.close()
                    _state.value = GlassesConnectionState.Disconnected
                    return
                }
                val (iface, inEp, outEp) = claimed
                runCatching { loadFactoryBias(conn, iface, inEp, outEp) }
                    .onFailure { err ->
                        imuBias = AirImuBias.ZERO
                        emit(LogTags.IMU, "factory IMU config failed: ${err.message}")
                    }
                sendActivation(conn, iface, outEp)
                connection = conn
                claimedInterface = iface
                startReader(conn, inEp)
            } catch (t: Throwable) {
                emit(LogTags.USB, "open failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(LogTags.USB, "openAndStream", t)
                closeLocked("open error")
            }
        }
    }

    private fun claimImuInterface(
        device: UsbDevice,
        conn: UsbDeviceConnection,
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        val candidates = buildList {
            for (id in NrealAirProtocol.PREFERRED_INTERFACE_IDS) {
                if (id in 0 until device.interfaceCount) add(device.getInterface(id))
            }
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (none { it.id == iface.id }) add(iface)
            }
        }
        for (iface in candidates) {
            val inEp = findEndpoint(iface, UsbConstants.USB_DIR_IN) ?: continue
            val outEp = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            val claimed = conn.claimInterface(iface, true)
            emit(
                LogTags.USB,
                "claim iface=${iface.id} force=true ok=$claimed in=0x%02X out=${
                    outEp?.let { "0x%02X".format(it.address) } ?: "none"
                }".format(inEp.address),
            )
            if (claimed) {
                return Triple(iface, inEp, outEp)
            }
        }
        emit(LogTags.USB, "No HID interrupt-IN interface could be claimed")
        return null
    }

    private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction != direction) continue
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            ) {
                return ep
            }
        }
        return null
    }

    private fun loadFactoryBias(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        inEp: UsbEndpoint,
        outEp: UsbEndpoint?,
    ) {
        imuBias = AirImuBias.ZERO
        transactImu(conn, iface, inEp, outEp, NrealAirProtocol.IMU_CMD_ENABLE_STREAM, byteArrayOf(0x00))
        val lenBytes = transactImu(
            conn,
            iface,
            inEp,
            outEp,
            NrealAirProtocol.IMU_CMD_GET_CONFIG_LEN,
            ByteArray(0),
        )
        if (lenBytes == null || lenBytes.size < 4) {
            emit(LogTags.IMU, "factory IMU config length missing — using live rest bias only")
            return
        }
        val len = (lenBytes[0].toInt() and 0xFF) or
            ((lenBytes[1].toInt() and 0xFF) shl 8) or
            ((lenBytes[2].toInt() and 0xFF) shl 16) or
            ((lenBytes[3].toInt() and 0xFF) shl 24)
        if (len <= 0 || len > NrealAirProtocol.IMU_CONFIG_MAX_BYTES) {
            emit(LogTags.IMU, "factory IMU config length=$len rejected")
            return
        }
        val jsonOut = java.io.ByteArrayOutputStream(len)
        var emptyChunks = 0
        while (jsonOut.size() < len) {
            val chunk = transactImu(
                conn,
                iface,
                inEp,
                outEp,
                NrealAirProtocol.IMU_CMD_GET_CONFIG_CHUNK,
                ByteArray(0),
            )
            if (chunk == null || chunk.isEmpty()) {
                emptyChunks++
                if (emptyChunks >= 3) break
                continue
            }
            emptyChunks = 0
            jsonOut.write(chunk)
        }
        val jsonBytes = jsonOut.toByteArray()
        val json = jsonBytes.decodeToString()
        imuBias = AirImuBias.parseConfigJson(json)
        emit(
            LogTags.IMU,
            "factory config ${jsonBytes.size}/$len B  ${imuBias.toLogLine()}",
        )
    }

    private fun transactImu(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        inEp: UsbEndpoint,
        outEp: UsbEndpoint?,
        cmd: Int,
        data: ByteArray,
    ): ByteArray? {
        val written = writeHid(conn, iface, outEp, NrealAirProtocol.imuControlFrame(cmd, data))
        if (written <= 0) {
            emit(LogTags.USB, "IMU cmd 0x%02X write failed returned $written".format(cmd))
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + NrealAirProtocol.IMU_COMMAND_TIMEOUT_MS
        val buf = ByteArray(maxOf(inEp.maxPacketSize, NrealAirProtocol.PACKET_SIZE))
        var skipped = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = try {
                conn.bulkTransfer(inEp, buf, buf.size, 80)
            } catch (t: Throwable) {
                emit(LogTags.USB, "IMU cmd 0x%02X read threw ${t.javaClass.simpleName}".format(cmd))
                return null
            }
            if (n <= 0) continue
            val packet = buf.copyOf(n)
            val reply = ImuParser.parseImuControlReply(packet)
            if (reply == null) {
                skipped++
                continue
            }
            if (reply.first == cmd) return reply.second
        }
        emit(LogTags.USB, "IMU cmd 0x%02X timed out (skipped $skipped reports)".format(cmd))
        return null
    }

    private fun writeHid(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        outEp: UsbEndpoint?,
        raw: ByteArray,
    ): Int {
        val packetSize = outEp?.maxPacketSize?.takeIf { it > 0 } ?: NrealAirProtocol.PACKET_SIZE
        val payload = ByteArray(packetSize)
        raw.copyInto(payload, endIndex = minOf(raw.size, packetSize))
        return if (outEp != null) {
            conn.bulkTransfer(outEp, payload, payload.size, NrealAirProtocol.ACTIVATION_TIMEOUT_MS)
        } else {
            conn.controlTransfer(
                USB_HID_SET_REPORT_TYPE,
                HID_SET_REPORT,
                HID_REPORT_OUTPUT,
                iface.id,
                payload,
                payload.size,
                NrealAirProtocol.ACTIVATION_TIMEOUT_MS,
            )
        }
    }

    private fun sendActivation(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        outEp: UsbEndpoint?,
    ) {
        val raw = NrealAirProtocol.ACTIVATION_PAYLOAD
        emit(LogTags.USB, "Sending activation ${raw.size}B: ${raw.toHex(16)}...")
        val written = writeHid(conn, iface, outEp, raw)
        val via = if (outEp != null) "interrupt/bulk OUT" else "HID SET_REPORT"
        emit(LogTags.USB, "Activation write via $via returned $written")
    }

    private fun startReader(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NrealImu").apply { isDaemon = true }
        }
        readerExecutor = executor
        readerRunning = true
        fusion.reset()
        poseEpoch.incrementAndGet()
        _orientation.value = Orientation.IDENTITY
        onGlassesPose?.invoke(Orientation.IDENTITY)
        _state.value = GlassesConnectionState.Streaming(packetsPerSec = 0)
        emit(
            LogTags.USB,
            "diag logging → ${diagDir.absolutePath} " +
                "(imu_trace.csv, imu_events.csv, imu_summary.txt) v=${BuildConfig.VERSION_NAME}",
        )
        emit(
            LogTags.USB,
            "IMU reader started on ep=0x%02X max=${inEp.maxPacketSize} (bulkTransfer)".format(inEp.address),
        )
        readerJob = scope.launch(executor.asCoroutineDispatcher()) {
            try {
                bulkLoop(conn, inEp)
            } catch (t: Throwable) {
                emit(LogTags.USB, "IMU reader stopped: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(LogTags.USB, "IMU reader crashed", t)
                _state.value = GlassesConnectionState.Disconnected
            }
        }
    }

    /**
     * Interrupt IN via [UsbDeviceConnection.bulkTransfer].
     *
     * Do not use [UsbDeviceConnection.requestWait]: on API 26+ a quiet endpoint throws
     * [java.util.concurrent.TimeoutException] instead of returning null, which killed the
     * process on Android 16 (Nubia NX789J) before any IMU bytes arrived.
     */
    private suspend fun bulkLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        val packetSize = maxOf(inEp.maxPacketSize, NrealAirProtocol.PACKET_SIZE)
        val buffer = ByteArray(packetSize)
        val stats = RateTracker()
        var timeouts = 0
        while (readerRunning && coroutineContext.isActive) {
            val n = try {
                conn.bulkTransfer(
                    inEp,
                    buffer,
                    packetSize,
                    NrealAirProtocol.USB_TRANSFER_TIMEOUT_MS,
                )
            } catch (t: Throwable) {
                emit(LogTags.USB, "bulkTransfer threw ${t.javaClass.simpleName}: ${t.message}")
                break
            }
            if (n <= 0) {
                timeouts++
                if (timeouts == 1 || timeouts % 20 == 0) {
                    emit(LogTags.USB, "interrupt IN timeout/empty n=$n (waiting for IMU stream)")
                }
                continue
            }
            timeouts = 0
            onPacket(buffer.copyOf(n), stats)
        }
    }

    private fun onPacket(packet: ByteArray, stats: RateTracker) {
        stats.onPacket()
        val hz = stats.maybePacketsPerSec()
        if (hz != null) {
            _state.value = GlassesConnectionState.Streaming(hz)
        }
        if (stats.total <= 3) {
            emit(LogTags.IMU_RAW, "pkt#${stats.total} ${packet.size}B ${packet.toHex()}")
        }
        if (ImuParser.isImuControlFrame(packet)) {
            if (stats.total <= 8) {
                emit(LogTags.IMU, "control frame ${packet.toHex(16)}")
            }
            return
        }
        val sample = runCatching { ImuParser.parse(packet) }.getOrNull() ?: return
        // Factory JSON bias signs disagree with live rest on this unit (trace still
        // showed ~−2.5°/s Y after apply). Rest-bias warmup alone matches the gyro.
        val calibrated = sample
        val epoch = poseEpoch.get()
        val pose = fusion.update(calibrated)
        if (epoch != poseEpoch.get()) return
        onGlassesPose?.invoke(pose)
        imuTrace.onSample(calibrated, pose, fusion)
        if (hz != null) {
            emit(
                LogTags.IMU_STATS,
                "rate=${hz}Hz packets=${stats.total} ts=${calibrated.timestampNs} " +
                    "${fusion.clockLogLine()} " +
                    "gyro_dps=${calibrated.gyroDps} gyro_rads=${calibrated.gyroRadS}",
            )
        }
        if (stats.shouldPublishHud()) {
            _latestSample.value = calibrated
            _orientation.value = pose
            onImuDebug?.invoke(
                "${fusion.clockLogLine()} gravity=rest-only display=raw depth=${"%.2f".format(-org.opennebula.air.shell.SpatialLayout.PANEL_Z)}m " +
                    "axis=${ImuParser.AIR_AXIS_LABEL} " +
                    "gyro=${"%.1f".format(calibrated.gyroDps.x)},${"%.1f".format(calibrated.gyroDps.y)},${"%.1f".format(calibrated.gyroDps.z)} " +
                    "q=${"%.3f".format(pose.qw)},${"%.3f".format(pose.qx)},${"%.3f".format(pose.qy)},${"%.3f".format(pose.qz)} " +
                    "${fusion.biasLogLine()}",
            )
        }
        if (stats.shouldPublishUi()) {
            emit(LogTags.IMU, calibrated.toLogLine())
            emit(LogTags.POSE, pose.toLogLine() + " " + fusion.biasLogLine())
        }
    }

    private suspend fun closeLocked(reason: String) {
        val hadConnection = connection != null
        readerRunning = false
        readerJob?.cancel()
        readerJob?.cancelAndJoin()
        readerJob = null
        readerExecutor?.shutdownNow()
        readerExecutor = null
        val iface = claimedInterface
        val conn = connection
        claimedInterface = null
        connection = null
        if (conn != null && iface != null) {
            runCatching { conn.releaseInterface(iface) }
        }
        conn?.close()
        imuBias = AirImuBias.ZERO
        _latestSample.value = null
        imuTrace.flush()
        fusion.reset()
        poseEpoch.incrementAndGet()
        _orientation.value = Orientation.IDENTITY
        onGlassesPose?.invoke(Orientation.IDENTITY)
        if (hadConnection) {
            emit(LogTags.USB, "Disconnected ($reason)")
        }
        _state.value = GlassesConnectionState.Disconnected
    }

    private fun emit(tag: String, message: String) {
        when {
            tag.endsWith("/Perm") -> Log.i(tag, message)
            else -> Log.i(tag, message)
        }
        onLog(tag, message)
    }

    private class RateTracker {
        var total: Long = 0
            private set
        private var windowStart = SystemClock.elapsedRealtime()
        private var windowCount = 0
        private var lastUiMs = 0L
        private var lastHudMs = 0L

        fun onPacket() {
            total++
            windowCount++
        }

        fun maybePacketsPerSec(): Int? {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart < 1_000L) return null
            val hz = windowCount
            windowCount = 0
            windowStart = now
            return hz
        }

        fun shouldPublishHud(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHudMs < HUD_PERIOD_MS) return false
            lastHudMs = now
            return true
        }

        fun shouldPublishUi(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUiMs < UI_PERIOD_MS) return false
            lastUiMs = now
            return true
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "org.opennebula.air.USB_PERMISSION"
        private const val HUD_PERIOD_MS = 50L // 20 Hz phone HUD
        private const val UI_PERIOD_MS = 200L // 5 Hz log lines
        private const val USB_HID_SET_REPORT_TYPE = 0x21
        private const val HID_SET_REPORT = 0x09
        private const val HID_REPORT_OUTPUT = 0x0200
    }
}

sealed interface GlassesConnectionState {
    data object Disconnected : GlassesConnectionState
    data object Connecting : GlassesConnectionState
    data object AwaitingPermission : GlassesConnectionState
    data class Streaming(val packetsPerSec: Int) : GlassesConnectionState
}
