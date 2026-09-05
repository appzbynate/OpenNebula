package org.opennebula.air.mirror

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * MediaProjection + VirtualDisplay, created on the Activity after the
 * mediaProjection foreground service is up. The result Intent is never
 * parceled into the service.
 */
class MirrorCapture(
    private val context: Context,
    private val projectionManager: MediaProjectionManager,
    private val pipeline: MirrorPipeline,
    private val onStopped: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var boundSurface: Surface? = null
    private var vdW = 0
    private var vdH = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            pipeline.log("MediaProjection stopped")
            main.post { onStopped() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            val (w, h) = MirrorPipeline.captureSize(width, height)
            pipeline.log("captured content resize ${width}x$height -> ${w}x$h")
            pipeline.setCaptureSize(w, h)
        }
    }

    private val displayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            pipeline.log("VirtualDisplay paused")
        }

        override fun onResumed() {
            pipeline.log("VirtualDisplay resumed")
        }

        override fun onStopped() {
            pipeline.log("VirtualDisplay stopped")
        }
    }

    fun start(resultCode: Int, data: Intent) {
        val mp = runCatching { projectionManager.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            pipeline.log("getMediaProjection returned null")
            onStopped()
            return
        }
        projection = mp
        mp.registerCallback(projectionCallback, main)
        pipeline.setActive(true)
        pipeline.onSurfaceReady = { surface -> attach(surface) }
        pipeline.openCapture()
        pipeline.log("projection ready ${pipeline.bufferW.get()}x${pipeline.bufferH.get()}")
    }

    fun stop() {
        pipeline.onSurfaceReady = null
        pipeline.setActive(false)
        releaseDisplay()
        pipeline.closeCapture()
        projection?.unregisterCallback(projectionCallback)
        runCatching { projection?.stop() }
        projection = null
    }

    private fun attach(surface: Surface?) {
        if (surface == null || !surface.isValid) {
            if (virtualDisplay != null) {
                runCatching { virtualDisplay?.setSurface(null) }
            }
            boundSurface = null
            pipeline.log("capture surface missing")
            return
        }
        val w = pipeline.bufferW.get()
        val h = pipeline.bufferH.get()
        val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        val existing = virtualDisplay
        if (existing != null) {
            if (vdW != w || vdH != h) {
                runCatching { existing.resize(w, h, dpi) }
                    .onSuccess { pipeline.log("VirtualDisplay resized ${w}x$h") }
                    .onFailure { err -> pipeline.log("VirtualDisplay.resize failed: ${err.message}") }
                vdW = w
                vdH = h
            }
            if (boundSurface !== surface) {
                runCatching { existing.setSurface(surface) }
                    .onSuccess { pipeline.log("VirtualDisplay surface rebound") }
                    .onFailure { err -> pipeline.log("VirtualDisplay.setSurface failed: ${err.message}") }
                boundSurface = surface
            }
            return
        }
        boundSurface = surface
        vdW = w
        vdH = h
        virtualDisplay = runCatching {
            projection?.createVirtualDisplay(
                MirrorProjectionService.DISPLAY_NAME,
                w,
                h,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                displayCallback,
                main,
            )
        }.onFailure { err ->
            pipeline.log("createVirtualDisplay failed: ${err.message}")
        }.getOrNull()
        if (virtualDisplay == null) {
            pipeline.log("VirtualDisplay is null")
        } else {
            pipeline.log("VirtualDisplay created ${w}x$h dpi=$dpi")
        }
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        boundSurface = null
        vdW = 0
        vdH = 0
    }
}
