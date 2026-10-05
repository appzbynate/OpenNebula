package org.opennebula.air.display

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import org.opennebula.air.imu.Orientation
import org.opennebula.air.imu.Vec3f
import org.opennebula.air.logging.LogTags
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.render.PointerBeam
import org.opennebula.air.render.WorldRenderer
import org.opennebula.air.shell.IconGridState
import org.opennebula.air.shell.SpatialLayer
import org.opennebula.air.shell.SpatialWindow
import org.opennebula.air.shell.WindowChromeState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GlassesPresentation(
    context: Context,
    display: Display,
    poseRef: AtomicReference<Orientation>,
    layers: List<SpatialLayer>,
    focusedId: AtomicReference<String>,
    showMarkers: AtomicReference<Boolean>,
    stereoEnabled: AtomicBoolean,
    hardware3d: AtomicBoolean,
    ipdMeters: AtomicReference<Float>,
    pointerBeam: AtomicReference<PointerBeam>,
    mirror: MirrorPipeline,
    chrome: WindowChromeState,
    iconGrid: IconGridState,
    gridOpen: AtomicBoolean,
    gridPose: AtomicReference<SpatialWindow>,
    browser: MirrorPipeline,
    steamVr: MirrorPipeline,
    desktop: MirrorPipeline,
    gyroRef: AtomicReference<Vec3f> = AtomicReference(Vec3f(0f, 0f, 0f)),
) : Presentation(context, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val preferredRefreshHz = AtomicInteger(90)

    private val renderer = WorldRenderer(
        poseRef, layers, focusedId, showMarkers, stereoEnabled, hardware3d, ipdMeters, pointerBeam, mirror, chrome,
        iconGrid, gridOpen, gridPose, browser, steamVr, desktop,
        preferredRefreshHz,
        gyroRef = gyroRef,
    )
    private val surface = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        // No MSAA — soft edges smear worse on sample-and-hold OLED; saves GPU for 120 FPS.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        // CONTINUOUSLY vsyncs to *this* Presentation's display (glasses), not the phone.
        // Phone Choreographer + WHEN_DIRTY locked us to phone 60/90 while glasses ran 120,
        // so SurfaceFlinger duplicated frames → ghosting / judder while turning the head.
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
    }

    fun requestFrame() {
        surface.requestRender()
    }

    /** Prefer an Android display mode near [hz] (MCU sets the glasses panel separately). */
    fun setPreferredRefreshHz(hz: Int) {
        preferredRefreshHz.set(hz.coerceIn(60, 120))
        preferRefresh(display, preferredRefreshHz.get())
        renderer.setTargetFps(preferredRefreshHz.get())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setFormat(PixelFormat.TRANSLUCENT)
        preferRefresh(display, preferredRefreshHz.get())
        surface.holder.setFormat(PixelFormat.TRANSLUCENT)
        setContentView(surface)
    }

    override fun onStart() {
        super.onStart()
        surface.onResume()
    }

    override fun onStop() {
        surface.onPause()
        super.onStop()
    }

    private fun preferRefresh(display: Display, targetHz: Int) {
        if (Build.VERSION.SDK_INT < 23) return
        val modes = display.supportedModes
        if (modes.isEmpty()) return
        val best = modes.minByOrNull { kotlin.math.abs(it.refreshRate - targetHz) } ?: return
        val lp = window?.attributes ?: return
        lp.preferredDisplayModeId = best.modeId
        if (Build.VERSION.SDK_INT >= 30) {
            lp.preferredRefreshRate = targetHz.toFloat()
        }
        window?.attributes = lp
        Log.i(
            LogTags.DISPLAY,
            "preferRefresh: target=${targetHz}Hz -> selected modeId=${best.modeId} " +
                "(${best.physicalWidth}x${best.physicalHeight}@${"%.1f".format(best.refreshRate)}Hz), " +
                "currentDisplayMode=${display.mode.modeId} (${"%.1f".format(display.mode.refreshRate)}Hz), " +
                "supportedModes=[${modes.joinToString { "${it.modeId}:${"%.0f".format(it.refreshRate)}Hz" }}]",
        )
    }
}
