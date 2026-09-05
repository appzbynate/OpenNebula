package org.opennebula.air.display

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import org.opennebula.air.imu.Orientation
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.render.PointerBeam
import org.opennebula.air.render.WorldRenderer
import org.opennebula.air.shell.IconGridState
import org.opennebula.air.shell.SpatialLayer
import org.opennebula.air.shell.SpatialWindow
import org.opennebula.air.shell.WindowChromeState
import java.util.concurrent.atomic.AtomicBoolean
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
) : Presentation(context, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val renderer = WorldRenderer(
        poseRef, layers, focusedId, showMarkers, stereoEnabled, hardware3d, ipdMeters, pointerBeam, mirror, chrome,
        iconGrid, gridOpen, gridPose, browser, steamVr, desktop,
    )
    private val surface = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        // 2× MSAA: 4× on SBS stereo was heavy enough to drop frames (judder ≈ vibration).
        setEGLConfigChooser(MsaaConfigChooser(2))
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
    }

    fun requestFrame() {
        surface.requestRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setFormat(PixelFormat.TRANSLUCENT)
        preferHighestRefresh(display)
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

    private fun preferHighestRefresh(display: Display) {
        if (Build.VERSION.SDK_INT < 23) return
        val best = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
        val lp = window?.attributes ?: return
        lp.preferredDisplayModeId = best.modeId
        window?.attributes = lp
    }
}

private class MsaaConfigChooser(
    private val preferredSamples: Int,
) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val order = intArrayOf(preferredSamples, 4, 2, 0)
        for (samples in order) {
            pick(egl, display, samples)?.let { return it }
        }
        error("No EGL config for OpenGL ES 2")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val spec = ArrayList<Int>(24)
        spec.add(EGL10.EGL_RED_SIZE)
        spec.add(8)
        spec.add(EGL10.EGL_GREEN_SIZE)
        spec.add(8)
        spec.add(EGL10.EGL_BLUE_SIZE)
        spec.add(8)
        spec.add(EGL10.EGL_ALPHA_SIZE)
        spec.add(8)
        spec.add(EGL10.EGL_DEPTH_SIZE)
        spec.add(16)
        spec.add(EGL10.EGL_RENDERABLE_TYPE)
        spec.add(EGL_OPENGL_ES2_BIT)
        if (samples > 0) {
            spec.add(EGL10.EGL_SAMPLE_BUFFERS)
            spec.add(1)
            spec.add(EGL10.EGL_SAMPLES)
            spec.add(samples)
        }
        spec.add(EGL10.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!egl.eglChooseConfig(display, spec.toIntArray(), configs, 1, n) || n[0] < 1) {
            return null
        }
        return configs[0]
    }

    companion object {
        private const val EGL_OPENGL_ES2_BIT = 4
    }
}
