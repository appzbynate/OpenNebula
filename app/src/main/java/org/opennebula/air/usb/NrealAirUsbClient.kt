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
import android.os.Process
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
    fun biasProgress(): Float = fusion.biasProgress()
    fun rateEmaDps(): Float = fusion.rateEmaDps()
    fun gyroDeadzoneDps(): Float = fusion.gyroDeadzoneDps()
    fun noiseFloorDps(): Float = fusion.noiseFloorDps()

    fun restartBiasCalibration() {
        fusion.restartBiasCalibration()
        poseEpoch.incrementAndGet()
        emit(LogTags.IMU, "bias calibration restarted — hold still")
    }

    fun cycleImuAxisMode(): String {
        ImuParser.resetAirAxisMode()
        return ImuParser.AIR_AXIS_LABEL
    }

    private var permissionReceiver: BroadcastReceiver? = null
    private var detachReceiver: BroadcastReceiver? = null
    private var readerJob: Job? = null
    private var readerExecutor: ExecutorService? = null
    private var connection: UsbDeviceConnection? = null
    private var usbDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null
    private var pendingDetachJob: Job? = null
    private val mcuLock = Any()
    @Volatile private var readerRunning = false

    fun connect() {
        scope.launch(Dispatchers.IO) {
            pendingDetachJob?.cancel()
            pendingDetachJob = null
            mutex.withLock {
                val cur = _state.value
                if (cur is GlassesConnectionState.Streaming && connection != null && readerRunning) {
                    emit(LogTags.USB, "Connect ignored: already streaming")
                    return@withLock
                }
                // Re-open from permission/connecting/stale streaming after USB re-enumerate.
                if (cur !is GlassesConnectionState.Disconnected) {
                    emit(LogTags.USB, "Connect reopen from $cur")
                    closeLocked("reconnect")
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
                "iface idx=$i id=${iface.id} class=${iface.interfaceClass} " +
                    "subclass=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} $eps",
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
                    emit(LogTags.USB, "USB detached — debounce (composite re-enum is common)")
                    pendingDetachJob?.cancel()
                    pendingDetachJob = scope.launch {
                        kotlinx.coroutines.delay(DETACH_DEBOUNCE_MS)
                        mutex.withLock { closeLocked("detached") }
                        // Re-enumeration often completes without a second ATTACHED delivery.
                        kotlinx.coroutines.delay(200)
                        if (_state.value is GlassesConnectionState.Disconnected && findAir() != null) {
                            emit(LogTags.USB, "Air still present after detach — reconnecting")
                            connect()
                        }
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
            pendingDetachJob?.cancel()
            pendingDetachJob = null
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
                usbDevice = device
                claimedInterface = iface
                startReader(conn, inEp)
            } catch (t: Throwable) {
                emit(LogTags.USB, "open failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(LogTags.USB, "openAndStream", t)
                closeLocked("open error")
            }
        }
    }

    /**
     * Pre-button claim path, with one safety change: never claim MCU (4) for the IMU
     * stream so brightness/3D can use a short-lived claim on the same connection.
     * Prefer IMU (3), then any other interrupt/bulk-IN interface except MCU.
     */
    private fun claimImuInterface(
        device: UsbDevice,
        conn: UsbDeviceConnection,
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        val mcuId = NrealAirProtocol.MCU_INTERFACE_ID
        val candidates = buildList {
            for (id in NrealAirProtocol.PREFERRED_INTERFACE_IDS) {
                if (id == mcuId) continue
                if (id in 0 until device.interfaceCount) add(device.getInterface(id))
            }
            for (i in 0 until device.interfaceCount) {
                if (i == mcuId) continue
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

    /**
     * Hardware display mode via MCU iface 4 (ar-drivers-rs / nrealAirLinuxDriver):
     * - GET 0x07 → mode at data[1]
     * - SET 0x08 + [mode]; success when reply data[0] == 0
     *
     * Never claimed at connect. One force-claim session for set+verify, then release.
     * Android HID: prefer SET_REPORT; interrupt OUT is fallback.
     */
    fun getDisplayMode(): Int? {
        return withMcuClaimed { conn, iface, inEp, outEp ->
            val data = transactMcu(
                conn, iface, inEp, outEp,
                NrealAirProtocol.MCU_CMD_GET_DISPLAY_MODE,
                ByteArray(0),
            ) ?: return@withMcuClaimed null
            data.getOrNull(1)?.toInt()?.and(0xFF)
        }
    }

    fun setDisplayMode3d(enabled: Boolean, ultrawide: Boolean = false): Boolean {
        mcuDiagClear()
        val targets = if (enabled) {
            if (ultrawide) {
                // SteamVR: 3840×1080 SBS → 1920×1080 per eye (mode 3), fallback HalfSBS 8.
                intArrayOf(
                    NrealAirProtocol.DISPLAY_MODE_3D_SBS_ULTRAWIDE,
                    NrealAirProtocol.DISPLAY_MODE_3D_SBS,
                )
            } else {
                intArrayOf(NrealAirProtocol.DISPLAY_MODE_3D_SBS)
            }
        } else {
            intArrayOf(NrealAirProtocol.DISPLAY_MODE_2D)
        }
        return setDisplayModeTargets(targets, enabled = enabled)
    }

    /**
     * Apply MCU mode for [hz] (60/90/120). Mirror supports 120; SBS tops out at 90.
     * Returns true when SET appears accepted.
     */
    fun setRefreshRateHz(hz: Int, stereo3d: Boolean): Boolean {
        val rate = NrealAirProtocol.snapRefreshHz(hz)
        val want = NrealAirProtocol.displayModeForRefresh(rate, stereo3d)
        mcuDiagClear()
        mcuDiag("setRefreshRateHz hz=$rate stereo=$stereo3d mode=$want")
        emit(LogTags.USB, "MCU refresh ${rate}Hz stereo=$stereo3d → mode=$want")
        val ok = setDisplayModeTargets(intArrayOf(want), enabled = stereo3d)
        if (ok && stereo3d && rate == 120) {
            emit(LogTags.USB, "SBS has no 120Hz — using mode 9 (90Hz SBS)")
        }
        return ok
    }

    private fun setDisplayModeTargets(targets: IntArray, enabled: Boolean): Boolean {
        mcuDiag("setDisplayModeTargets enabled=$enabled targets=${targets.joinToString()}")
        data class McuModeResult(val mode: Int?, val wroteUnverified: Boolean)
        val result = withMcuClaimed { conn, iface, inEp, outEp ->
            var applied: Int? = null
            var wroteUnverified = false
            for (want in targets) {
                mcuDiag("try SET mode=$want")
                emit(LogTags.USB, "MCU SET display mode=$want")
                val ack = transactMcu(
                    conn, iface, inEp, outEp,
                    NrealAirProtocol.MCU_CMD_SET_DISPLAY_MODE,
                    byteArrayOf(want.toByte()),
                )
                if (ack == null) {
                    mcuDiag("SET mode=$want write failed")
                    continue
                }
                val setOk = ack.isNotEmpty() && (ack[0].toInt() and 0xFF) == 0
                mcuDiag("SET mode=$want ack=${ack.toHex(8)} setOk=$setOk")
                emit(LogTags.USB, "MCU SET mode=$want ack=${ack.toHex(8)} setOk=$setOk")
                Thread.sleep(150)
                val got = transactMcu(
                    conn, iface, inEp, outEp,
                    NrealAirProtocol.MCU_CMD_GET_DISPLAY_MODE,
                    ByteArray(0),
                )?.getOrNull(1)?.toInt()?.and(0xFF)
                mcuDiag("GET after SET → $got (want $want)")
                emit(LogTags.USB, "MCU GET display mode → $got (want $want)")
                if (got == want || (setOk && enabled && got != null && got != NrealAirProtocol.DISPLAY_MODE_2D)) {
                    applied = got ?: want
                    wroteUnverified = false
                    break
                }
                if (setOk) {
                    // USB write accepted; GET may be unavailable without interrupt-IN.
                    applied = want
                    wroteUnverified = got == null
                    break
                }
                // 2D high-refresh: accept SET ack even when GET stays on another 2D mode.
                if (setOk && !enabled) {
                    applied = want
                    wroteUnverified = got == null || got != want
                    break
                }
            }
            McuModeResult(applied, wroteUnverified)
        }
        val mode = result?.mode
        val ok = mode != null
        if (result?.wroteUnverified == true) {
            mcuDiag("SET wrote but GET unverified — check glasses for blanking/3D")
        }
        mcuDiag("result mode=$mode ok=$ok")
        emit(LogTags.USB, "MCU display mode result=$mode ok=$ok")
        return ok
    }

    fun getBrightness(): Int? {
        return withMcuClaimed { conn, iface, inEp, outEp ->
            val data = transactMcu(
                conn, iface, inEp, outEp,
                NrealAirProtocol.MCU_CMD_GET_BRIGHTNESS,
                ByteArray(0),
            ) ?: return@withMcuClaimed null
            data.getOrNull(1)?.toInt()?.and(0xFF)?.coerceIn(
                NrealAirProtocol.BRIGHTNESS_MIN,
                NrealAirProtocol.BRIGHTNESS_MAX,
            )
        }
    }

    fun setBrightness(level: Int): Boolean {
        val v = level.coerceIn(NrealAirProtocol.BRIGHTNESS_MIN, NrealAirProtocol.BRIGHTNESS_MAX)
        mcuDiagClear()
        mcuDiag("setBrightness level=$v")
        emit(LogTags.USB, "MCU SET brightness=$v")
        val result = withMcuClaimed { conn, iface, inEp, outEp ->
            val ack = transactMcu(
                conn, iface, inEp, outEp,
                NrealAirProtocol.MCU_CMD_SET_BRIGHTNESS,
                byteArrayOf(v.toByte()),
            )
            if (ack == null) {
                mcuDiag("SET brightness write failed")
                return@withMcuClaimed false
            }
            val setOk = ack.isEmpty() || (ack[0].toInt() and 0xFF) == 0
            mcuDiag("SET brightness ack=${ack.toHex(8)} setOk=$setOk")
            emit(LogTags.USB, "MCU SET brightness=$v ack=${ack.toHex(8)} setOk=$setOk")
            if (!setOk) return@withMcuClaimed false
            Thread.sleep(80)
            val got = transactMcu(
                conn, iface, inEp, outEp,
                NrealAirProtocol.MCU_CMD_GET_BRIGHTNESS,
                ByteArray(0),
            )?.getOrNull(1)?.toInt()?.and(0xFF)
            mcuDiag("GET brightness → $got (want $v)")
            got == null || got == v || setOk
        }
        val ok = result == true
        emit(LogTags.USB, if (ok) "MCU brightness OK level=$v" else "MCU brightness FAILED level=$v")
        return ok
    }

    private fun mcuDiagClear() {
        runCatching {
            diagDir.mkdirs()
            File(diagDir, "mcu_last.txt").writeText("mcu diag ${BuildConfig.VERSION_NAME}\n")
        }
    }

    private fun mcuDiag(line: String) {
        runCatching {
            diagDir.mkdirs()
            File(diagDir, "mcu_last.txt").appendText("${SystemClock.elapsedRealtime()} $line\n")
        }
        Log.i(LogTags.ROOT, "MCU $line")
    }

    private fun <T> withMcuClaimed(
        block: (UsbDeviceConnection, UsbInterface, UsbEndpoint?, UsbEndpoint?) -> T,
    ): T? {
        val conn = connection ?: run {
            mcuDiag("skipped — not connected")
            emit(LogTags.USB, "MCU skipped — not connected")
            return null
        }
        val device = usbDevice ?: return null
        dumpUsbToMcuDiag(device)
        if (claimedInterface?.id == NrealAirProtocol.MCU_INTERFACE_ID) {
            mcuDiag("skipped — IMU holds MCU iface")
            return null
        }
        val iface = resolveMcuInterface(device) ?: run {
            mcuDiag("no MCU HID candidate")
            return null
        }
        val inEp = findEndpoint(iface, UsbConstants.USB_DIR_IN)
            ?: findAnyEndpoint(iface, UsbConstants.USB_DIR_IN)
        val outEp = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            ?: findAnyEndpoint(iface, UsbConstants.USB_DIR_OUT)
        mcuDiag(
            "using iface id=${iface.id} class=${iface.interfaceClass} eps=${iface.endpointCount} " +
                "in=${inEp?.let { "0x%02X".format(it.address) } ?: "none"} " +
                "out=${outEp?.let { "0x%02X".format(it.address) } ?: "none"}",
        )
        return synchronized(mcuLock) {
            val claimed = conn.claimInterface(iface, true)
            mcuDiag("claim force ok=$claimed")
            emit(
                LogTags.USB,
                "MCU claim iface id=${iface.id} force ok=$claimed in=${
                    inEp?.let { "0x%02X".format(it.address) } ?: "none"
                }",
            )
            if (!claimed) {
                emit(LogTags.USB, "MCU iface busy — cannot set display mode")
                return@synchronized null
            }
            try {
                if (inEp != null) drainMcuIn(conn, inEp)
                block(conn, iface, inEp, outEp)
            } finally {
                runCatching { conn.releaseInterface(iface) }
                mcuDiag("released")
                emit(LogTags.USB, "MCU iface released")
            }
        }
    }

    private fun dumpUsbToMcuDiag(device: UsbDevice) {
        mcuDiag("usb ifaces=${device.interfaceCount} imuClaimed=${claimedInterface?.id}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val eps = (0 until iface.endpointCount).joinToString { e ->
                val ep = iface.getEndpoint(e)
                "0x%02X/%s/%s".format(
                    ep.address,
                    xferName(ep.type),
                    if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                )
            }
            mcuDiag(
                "  idx=$i id=${iface.id} class=${iface.interfaceClass}/" +
                    "${iface.interfaceSubclass}/${iface.interfaceProtocol} eps=[$eps]",
            )
        }
    }

    /** Match bInterfaceNumber, not Android list index (they can differ). */
    private fun findInterfaceByNumber(device: UsbDevice, number: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == number) return iface
        }
        return null
    }

    private fun resolveMcuInterface(device: UsbDevice): UsbInterface? {
        val imuId = claimedInterface?.id
        val preferred = findInterfaceByNumber(device, NrealAirProtocol.MCU_INTERFACE_ID)
        if (preferred != null && preferred.id != imuId) {
            val hasHidShape = preferred.interfaceClass == UsbConstants.USB_CLASS_HID ||
                findEndpoint(preferred, UsbConstants.USB_DIR_IN) != null ||
                preferred.endpointCount == 0 // control-only HID still usable via SET_REPORT
            if (hasHidShape || preferred.interfaceClass == UsbConstants.USB_CLASS_HID) {
                mcuDiag("candidate preferred id=${preferred.id} class=${preferred.interfaceClass}")
                return preferred
            }
            mcuDiag(
                "preferred id=${preferred.id} class=${preferred.interfaceClass} " +
                    "eps=${preferred.endpointCount} — still trying (SET_REPORT)",
            )
            return preferred
        }
        // Fall back: any other HID iface (class 3) that isn't the IMU stream.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == imuId) continue
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            mcuDiag("candidate HID fallback id=${iface.id}")
            return iface
        }
        // Last resort: any non-IMU iface with interrupt IN+OUT (hid-like).
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == imuId) continue
            val inEp = findEndpoint(iface, UsbConstants.USB_DIR_IN)
            val outEp = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            if (inEp != null && outEp != null) {
                mcuDiag("candidate INT pair id=${iface.id}")
                return iface
            }
        }
        return null
    }

    private fun drainMcuIn(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        val buf = ByteArray(maxOf(inEp.maxPacketSize, NrealAirProtocol.PACKET_SIZE))
        repeat(8) {
            val n = runCatching { conn.bulkTransfer(inEp, buf, buf.size, 20) }.getOrDefault(-1)
            if (n <= 0) return
        }
    }

    private fun writeMcuFrame(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        outEp: UsbEndpoint?,
        frame: ByteArray,
    ): Pair<String, Int> {
        // On this phone INT_OUT on iface 4 succeeds; SET_REPORT often returns 0.
        if (outEp != null) {
            val packetSize = outEp.maxPacketSize.takeIf { it > 0 } ?: NrealAirProtocol.PACKET_SIZE
            val payload = ByteArray(packetSize)
            frame.copyInto(payload, endIndex = minOf(frame.size, packetSize))
            val n = conn.bulkTransfer(outEp, payload, payload.size, NrealAirProtocol.ACTIVATION_TIMEOUT_MS)
            if (n > 0) return "INT_OUT" to n
        }
        val ctrl = conn.controlTransfer(
            USB_HID_SET_REPORT_TYPE,
            HID_SET_REPORT,
            HID_REPORT_OUTPUT,
            iface.id,
            frame,
            frame.size,
            NrealAirProtocol.ACTIVATION_TIMEOUT_MS,
        )
        if (ctrl > 0) return "SET_REPORT" to ctrl
        val withId = byteArrayOf(0) + frame
        val ctrl2 = conn.controlTransfer(
            USB_HID_SET_REPORT_TYPE,
            HID_SET_REPORT,
            HID_REPORT_OUTPUT,
            iface.id,
            withId,
            withId.size,
            NrealAirProtocol.ACTIVATION_TIMEOUT_MS,
        )
        if (ctrl2 > 0) return "SET_REPORT+id0" to ctrl2
        val feat = conn.controlTransfer(
            USB_HID_SET_REPORT_TYPE,
            HID_SET_REPORT,
            HID_REPORT_FEATURE,
            iface.id,
            frame,
            frame.size,
            NrealAirProtocol.ACTIVATION_TIMEOUT_MS,
        )
        return "SET_REPORT+feat" to feat
    }

    private fun transactMcu(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        inEp: UsbEndpoint?,
        outEp: UsbEndpoint?,
        cmd: Int,
        data: ByteArray,
    ): ByteArray? {
        val frame = NrealAirProtocol.mcuControlFrame(cmd, data)
        val (via, written) = writeMcuFrame(conn, iface, outEp, frame)
        mcuDiag("cmd=0x%04X write via=$via n=$written".format(cmd))
        emit(LogTags.USB, "MCU cmd 0x%04X write via=$via returned $written".format(cmd))
        if (written <= 0) return null

        val deadline = SystemClock.elapsedRealtime() + NrealAirProtocol.MCU_COMMAND_TIMEOUT_MS
        val packetSize = inEp?.maxPacketSize?.takeIf { it > 0 } ?: NrealAirProtocol.PACKET_SIZE
        val buf = ByteArray(packetSize + 1)
        var skipped = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = when {
                inEp != null -> try {
                    conn.bulkTransfer(inEp, buf, buf.size, 100)
                } catch (t: Throwable) {
                    mcuDiag("cmd=0x%04X read threw ${t.javaClass.simpleName}".format(cmd))
                    return null
                }
                else -> {
                    // No interrupt IN — try HID GET_REPORT (Input) on the control pipe.
                    val got = conn.controlTransfer(
                        USB_HID_GET_REPORT_TYPE,
                        HID_GET_REPORT,
                        HID_REPORT_INPUT,
                        iface.id,
                        buf,
                        buf.size,
                        100,
                    )
                    if (got <= 0) {
                        Thread.sleep(20)
                        0
                    } else {
                        got
                    }
                }
            }
            if (n <= 0) continue
            val packet = when {
                n >= 1 && (buf[0].toInt() and 0xFF) == 0xFD -> buf.copyOf(n)
                n >= 2 && (buf[1].toInt() and 0xFF) == 0xFD -> buf.copyOfRange(1, n)
                else -> {
                    skipped++
                    continue
                }
            }
            val reply = NrealAirProtocol.parseMcuReply(packet)
            if (reply == null) {
                skipped++
                continue
            }
            if (reply.first == cmd) {
                mcuDiag("cmd=0x%04X reply data=${reply.second.toHex(8)}".format(cmd))
                return reply.second
            }
            skipped++
        }
        // Write reached the device; without a matching reply, still surface a soft ack for SET
        // so callers can tell "sent" vs "USB failed".
        if (cmd == NrealAirProtocol.MCU_CMD_SET_DISPLAY_MODE && data.isNotEmpty()) {
            mcuDiag("cmd=0x%04X no reply — soft-ack after write (skipped=$skipped)".format(cmd))
            return byteArrayOf(0)
        }
        mcuDiag("cmd=0x%04X timed out skipped=$skipped".format(cmd))
        emit(LogTags.USB, "MCU cmd 0x%04X timed out (skipped $skipped)".format(cmd))
        return null
    }

    private fun findAnyEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == direction) return ep
        }
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
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
            onPacket(buffer, n, stats)

            // DRAIN BACKLOG: If packets queued up in the kernel FIFO (e.g. from GC, UI events,
            // or thread scheduling), drain them immediately with 1ms timeout. When packets are
            // already waiting, bulkTransfer returns immediately (<25 µs), eliminating pipeline
            // lag and keeping IMU orientation locked to real-time.
            while (readerRunning && coroutineContext.isActive) {
                val nextN = try {
                    conn.bulkTransfer(inEp, buffer, packetSize, 1)
                } catch (t: Throwable) {
                    break
                }
                if (nextN <= 0) break
                onPacket(buffer, nextN, stats)
            }
        }
    }

    private fun onPacket(packet: ByteArray, length: Int, stats: RateTracker) {
        stats.onPacket()
        val hz = stats.maybePacketsPerSec()
        if (hz != null) {
            _state.value = GlassesConnectionState.Streaming(hz)
        }
        if (stats.total <= 3) {
            emit(LogTags.IMU_RAW, "pkt#${stats.total} ${length}B ${packet.toHex(length)}")
        }
        if (ImuParser.isImuControlFrame(packet, length)) {
            if (stats.total <= 8) {
                emit(LogTags.IMU, "control frame ${packet.toHex(minOf(16, length))}")
            }
            return
        }
        val sample = ImuParser.parse(packet, length) ?: return
        // Factory flash cal removes most static gyro bias (XReal community: ~1°/s →
        // near-zero still drift when applied). Live Madgwick warmup then eats residual.
        // Skipping apply left world-lock crawling even after Anchor.
        val calibrated = imuBias.apply(sample)
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
                "${fusion.clockLogLine()} fusion=x-io gain=${MadgwickImu.GAIN} " +
                    "display=raw depth=${"%.2f".format(-org.opennebula.air.shell.SpatialLayout.PANEL_Z)}m " +
                    "axis=${ImuParser.AIR_AXIS_LABEL} factory=${if (imuBias.isZero) "off" else "on"} " +
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
        usbDevice = null
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
        // Also plain tag — some ROMs drop tags with '/'.
        Log.i(LogTags.ROOT, "$tag $message")
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
        private const val DETACH_DEBOUNCE_MS = 600L
        private const val HUD_PERIOD_MS = 50L // 20 Hz phone HUD
        private const val UI_PERIOD_MS = 200L // 5 Hz log lines
        private const val USB_HID_SET_REPORT_TYPE = 0x21 // host→device class
        private const val USB_HID_GET_REPORT_TYPE = 0xA1 // device→host class
        private const val HID_SET_REPORT = 0x09
        private const val HID_GET_REPORT = 0x01
        private const val HID_REPORT_INPUT = 0x0100
        private const val HID_REPORT_OUTPUT = 0x0200
        private const val HID_REPORT_FEATURE = 0x0300
    }
}

sealed interface GlassesConnectionState {
    data object Disconnected : GlassesConnectionState
    data object Connecting : GlassesConnectionState
    data object AwaitingPermission : GlassesConnectionState
    data class Streaming(val packetsPerSec: Int) : GlassesConnectionState
}
