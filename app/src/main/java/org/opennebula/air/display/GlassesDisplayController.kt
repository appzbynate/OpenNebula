package org.opennebula.air.display

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opennebula.air.imu.Orientation
import org.opennebula.air.imu.Vec3f
import org.opennebula.air.logging.LogTags
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.mirror.MirrorProjectionService
import org.opennebula.air.render.PointerBeam
import org.opennebula.air.render.StereoConfig
import org.opennebula.air.shell.IconGridState
import org.opennebula.air.shell.SpatialLayer
import org.opennebula.air.shell.SpatialWindow
import org.opennebula.air.shell.WindowChromeState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class GlassesDisplayStatus(
    val summary: String,
    val presenting: Boolean,
)

class GlassesDisplayController(
    private val layers: List<SpatialLayer>,
    private val focusedId: AtomicReference<String>,
    private val showMarkers: AtomicReference<Boolean>,
    private val stereoEnabled: AtomicBoolean,
    private val hardware3d: AtomicBoolean,
    private val ipdMeters: AtomicReference<Float>,
    private val pointerBeam: AtomicReference<PointerBeam>,
    private val mirror: MirrorPipeline,
    private val chrome: WindowChromeState,
    private val iconGrid: IconGridState,
    private val gridOpen: AtomicBoolean,
    private val gridPose: AtomicReference<SpatialWindow>,
    private val browser: MirrorPipeline,
    private val steamVr: MirrorPipeline,
    private val onLog: (tag: String, message: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val poseRef = AtomicReference(Orientation.IDENTITY)
    private val _status = MutableStateFlow(GlassesDisplayStatus("Glasses display: none", false))
    val status: StateFlow<GlassesDisplayStatus> = _status.asStateFlow()

    private var activity: Activity? = null
    private var displayManager: DisplayManager? = null
    @Volatile private var presentation: GlassesPresentation? = null
    private var targetId: Int? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            emit("display added id=$displayId")
            main.post { tryPresent() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            emit("display removed id=$displayId")
            if (targetId == displayId) {
                main.post { dismiss("removed") }
            }
            main.post { tryPresent() }
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun bind(activity: Activity) {
        if (this.activity === activity && displayManager != null) {
            tryPresent()
            return
        }
        unbind()
        this.activity = activity
        val dm = activity.getSystemService(DisplayManager::class.java)
        displayManager = dm
        dm.registerDisplayListener(listener, main)
        dumpDisplays(dm)
        tryPresent()
    }

    fun unbind() {
        displayManager?.unregisterDisplayListener(listener)
        displayManager = null
        dismiss("unbind")
        activity = null
    }

    fun publishPose(pose: Orientation, @Suppress("UNUSED_PARAMETER") gyroRadS: Vec3f = Vec3f(0f, 0f, 0f)) {
        poseRef.set(pose)
    }

    fun requestFrameNow() {
        presentation?.requestFrame()
    }

    fun refreshModeStatus() {
        val shown = presentation ?: return
        if (!shown.isShowing) return
        _status.value = GlassesDisplayStatus(describe(shown.display) + " — " + modeLabel(), true)
    }

    private fun tryPresent() {
        val act = activity ?: return
        val dm = displayManager ?: return
        val target = pickGlassesDisplay(dm)
        if (target == null) {
            dismiss("no external display")
            _status.value = GlassesDisplayStatus(
                "Glasses display: none (phone only / OEM may be mirroring)",
                false,
            )
            return
        }
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) {
            _status.value = GlassesDisplayStatus(describe(target) + " — " + modeLabel(), true)
            return
        }
        dismiss("switch")
        val next = GlassesPresentation(
            act,
            target,
            poseRef,
            layers,
            focusedId,
            showMarkers,
            stereoEnabled,
            hardware3d,
            ipdMeters,
            pointerBeam,
            mirror,
            chrome,
            iconGrid,
            gridOpen,
            gridPose,
            browser,
            steamVr,
        )
        runCatching {
            next.show()
            presentation = next
            targetId = target.displayId
            presentation?.requestFrame()
            val msg = "presenting on ${describe(target)} — ${modeLabel()}"
            emit(msg)
            _status.value = GlassesDisplayStatus(msg, true)
        }.onFailure { err ->
            emit("Presentation.show failed: ${err.message}")
            _status.value = GlassesDisplayStatus("Glasses display: show failed ${err.message}", false)
        }
    }

    private fun dismiss(reason: String) {
        val shown = presentation
        presentation = null
        targetId = null
        if (shown != null) {
            runCatching { shown.dismiss() }
            emit("Presentation dismissed ($reason)")
        }
    }

    private fun modeLabel(): String {
        return if (stereoEnabled.get()) {
            if (hardware3d.get()) {
                "hardware 3D SBS (HalfSBS) IPD ${"%.0f".format(ipdMeters.get() * 1000f)} mm"
            } else {
                "software SBS IPD ${"%.0f".format(ipdMeters.get() * 1000f)} mm FOVy ${"%.2f".format(StereoConfig.FOVY_DEG)}°"
            }
        } else {
            "mono 2D FOVy ${"%.2f".format(StereoConfig.FOVY_DEG)}°"
        }
    }

    private fun pickGlassesDisplay(dm: DisplayManager): Display? {
        val candidates = dm.displays.filter { isGlassesCandidate(it) }
        val presentation = candidates.filter { (it.flags and Display.FLAG_PRESENTATION) != 0 }
        val pool = if (presentation.isNotEmpty()) presentation else candidates
        return pool.maxByOrNull { it.mode.physicalWidth * it.mode.physicalHeight }
    }

    private fun isGlassesCandidate(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY || !d.isValid) return false
        if ((d.flags and Display.FLAG_PRIVATE) != 0) return false
        val name = d.name.lowercase()
        if (name.contains(MirrorProjectionService.DISPLAY_NAME)) return false
        if (name.contains("screen.sharing")) return false
        if (name.contains("virtual")) return false
        if (org.opennebula.air.browser.SpatialBrowser.skipDisplayName(d.name)) return false
        return true
    }

    private fun dumpDisplays(dm: DisplayManager) {
        for (d in dm.displays) {
            emit(
                "display id=${d.displayId} flags=0x${Integer.toHexString(d.flags)} name=${d.name} " +
                    "${d.mode.physicalWidth}x${d.mode.physicalHeight} " +
                    "rot=${d.rotation} default=${d.displayId == Display.DEFAULT_DISPLAY}",
            )
        }
    }

    private fun describe(d: Display): String {
        return "${d.name} ${d.mode.physicalWidth}x${d.mode.physicalHeight} id=${d.displayId}"
    }

    private fun emit(message: String) {
        Log.i(LogTags.DISPLAY, message)
        onLog(LogTags.DISPLAY, message)
    }
}
