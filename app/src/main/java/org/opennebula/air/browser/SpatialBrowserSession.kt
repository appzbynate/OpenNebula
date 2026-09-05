package org.opennebula.air.browser

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import java.lang.ref.WeakReference

/**
 * ImageReader + private Presentation VirtualDisplay. WebView frames are copied
 * to a bitmap and uploaded as GL_TEXTURE_2D on the glasses compositor.
 * Never PUBLIC (glasses hijack) and never AUTO_MIRROR.
 */
class SpatialBrowserSession(
    private val appContext: Context,
    val pipeline: MirrorPipeline,
    private val onLog: (String) -> Unit,
) {
    val enabled get() = pipeline.enabled
    val pose get() = pipeline.pose
    val cursorX get() = pipeline.cursorX
    val cursorY get() = pipeline.cursorY

    @Volatile var onImeFocus: ((String) -> Unit)? = null
    @Volatile var onImeBlur: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: SpatialBrowserPresentation? = null
    private var boundSurface: Surface? = null
    private var pendingUrl: String? = null
    private var activityRef = WeakReference<Activity>(null)
    @Volatile private var pointerDown = false
    private var downTime = 0L

    var displayId: Int = -1
        private set

    val running: Boolean get() = pipeline.enabled.get()

    init {
        pipeline.pose.set(
            SpatialWindow.fromControls(
                -SpatialLayout.PANEL_Z,
                SpatialLayout.PANEL_Y,
                1.12f,
                0f,
                SpatialBrowser.ASPECT,
            ),
        )
        pipeline.onLog = onLog
        pipeline.onSurfaceReady = { surface -> main.post { attach(surface) } }
    }

    fun bind(activity: Activity) {
        activityRef = WeakReference(activity)
        if (virtualDisplay != null && presentation == null && running) {
            main.post { showPresentation(pendingUrl ?: SpatialBrowser.lastUrl) }
        }
    }

    fun unbind() {
        activityRef = WeakReference(null)
    }

    fun launch(url: String = SpatialBrowser.lastUrl) {
        val target = url.trim().ifBlank { SpatialBrowser.DEFAULT_URL }
        SpatialBrowser.rememberUrl(target)
        pendingUrl = target
        if (!running) startCapture()
        val shown = presentation
        if (shown != null) {
            shown.load(target)
            onLog("browser navigate $target")
            return
        }
        val surface = pipeline.surface
        if (surface != null && virtualDisplay == null) {
            attach(surface)
            return
        }
        if (virtualDisplay != null && presentation == null) {
            showPresentation(target)
            return
        }
        onLog("browser waiting for ImageReader surface $target")
    }

    fun stop() {
        pendingUrl = null
        pointerDown = false
        pipeline.onSurfaceReady = null
        pipeline.setActive(false)
        dismissPresentation()
        releaseDisplay()
        pipeline.closeCapture()
        SpatialBrowser.detach(SpatialBrowser.webView.get())
        SpatialBrowser.flushCookies()
        onLog("browser stopped frames=${pipeline.frameCount()}")
    }

    fun injectPointer(action: Int, u: Float, v: Float): Boolean {
        val view = SpatialBrowser.webView.get() ?: return false
        if (v !in 0f..1f) return false
        val width = pipeline.bufferW.get().takeIf { it > 1 } ?: SpatialBrowser.WIDTH
        val height = pipeline.bufferH.get().takeIf { it > 1 } ?: SpatialBrowser.HEIGHT
        val (x, y) = SpatialBrowser.pixel(u, v, width, height)
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = SystemClock.uptimeMillis()
            pointerDown = true
        }
        if (!pointerDown && action != MotionEvent.ACTION_DOWN) return false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.post {
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pointerDown = false
            if (action == MotionEvent.ACTION_UP) {
                view.post {
                    view.evaluateJavascript(SpatialBrowser.FOCUS_HOOK, null)
                }
                view.postDelayed({
                    view.evaluateJavascript(SpatialBrowser.IME_PROBE, null)
                }, 180)
            }
        }
        pipeline.cursorX.set(u.coerceIn(0f, 1f))
        pipeline.cursorY.set(v)
        return true
    }

    fun pointerIsDown(): Boolean = pointerDown

    fun cancelPointer() {
        if (!pointerDown) return
        injectPointer(MotionEvent.ACTION_CANCEL, cursorX.get(), cursorY.get().coerceIn(0f, 1f))
    }

    fun scrollBy(dxPx: Float, dyPx: Float) {
        val view = SpatialBrowser.webView.get() ?: return
        val dx = dxPx.toInt()
        val dy = dyPx.toInt()
        view.post {
            view.scrollBy(dx, dy)
            view.evaluateJavascript(
                "(function(){var e=document.scrollingElement||document.documentElement;if(e)e.scrollBy($dx,$dy);})();",
                null,
            )
        }
    }

    fun zoomBy(factor: Float) {
        val view = SpatialBrowser.webView.get() ?: return
        val f = factor.coerceIn(0.55f, 1.8f)
        view.post { view.zoomBy(f) }
    }

    fun goForward() {
        val view = SpatialBrowser.webView.get() ?: return
        view.post { if (view.canGoForward()) view.goForward() }
    }

    fun goBack(onEmpty: (() -> Unit)? = null) {
        val view = SpatialBrowser.webView.get()
        if (view == null) {
            onEmpty?.invoke()
            return
        }
        view.post {
            if (view.canGoBack()) {
                view.goBack()
            } else {
                main.post { onEmpty?.invoke() }
            }
        }
    }

    fun probeIme() {
        val view = SpatialBrowser.webView.get() ?: return
        view.post {
            view.evaluateJavascript(SpatialBrowser.FOCUS_HOOK, null)
            view.evaluateJavascript(SpatialBrowser.IME_PROBE, null)
        }
    }

    fun setFocusedText(text: String) {
        val view = SpatialBrowser.webView.get() ?: return
        val js = SpatialBrowser.jsSetValue(text)
        view.post { view.evaluateJavascript(js, null) }
    }

    fun injectKey(keyCode: Int): Boolean {
        val view = SpatialBrowser.webView.get() ?: return false
        view.post {
            if (keyCode == KeyEvent.KEYCODE_BACK && view.canGoBack()) {
                view.goBack()
                return@post
            }
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return true
    }

    private fun startCapture() {
        pipeline.setCaptureSize(SpatialBrowser.WIDTH, SpatialBrowser.HEIGHT)
        pipeline.setActive(true)
        pipeline.onSurfaceReady = { surface -> main.post { attach(surface) } }
        pipeline.openCapture(SpatialBrowser.WIDTH, SpatialBrowser.HEIGHT)
        onLog("browser ImageReader ${SpatialBrowser.WIDTH}x${SpatialBrowser.HEIGHT}")
    }

    private fun attach(surface: Surface?) {
        if (!enabled.get()) return
        if (surface == null || !surface.isValid) {
            runCatching { virtualDisplay?.setSurface(null) }
            boundSurface = null
            onLog("browser ImageReader surface missing")
            return
        }
        val existing = virtualDisplay
        if (existing != null) {
            if (boundSurface !== surface) {
                runCatching { existing.setSurface(surface) }
                boundSurface = surface
                onLog("browser rebound ImageReader surface")
            }
            if (presentation == null) {
                pendingUrl?.let { showPresentation(it) }
            }
            return
        }
        val w = pipeline.bufferW.get()
        val h = pipeline.bufferH.get()
        val dm = appContext.getSystemService(DisplayManager::class.java)
        virtualDisplay = runCatching {
            dm.createVirtualDisplay(
                SpatialBrowser.DISPLAY_NAME,
                w,
                h,
                SpatialBrowser.DPI,
                surface,
                SpatialBrowser.VD_FLAGS,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() = onLog("browser VirtualDisplay paused")
                    override fun onResumed() = onLog("browser VirtualDisplay resumed")
                    override fun onStopped() = onLog("browser VirtualDisplay stopped")
                },
                main,
            )
        }.onFailure { err ->
            onLog("createVirtualDisplay failed: ${err.message}")
        }.getOrNull()
        val vd = virtualDisplay
        if (vd == null) {
            onLog("browser VirtualDisplay is null")
            return
        }
        boundSurface = surface
        displayId = vd.display.displayId
        onLog(
            "browser VirtualDisplay id=$displayId ${w}x$h " +
                "flags=0x${Integer.toHexString(SpatialBrowser.VD_FLAGS)}",
        )
        pendingUrl?.let { url ->
            main.postDelayed({ showPresentation(url) }, 200)
        }
        main.postDelayed({
            if (enabled.get()) {
                onLog("browser ImageReader frames=${pipeline.frameCount()}")
            }
        }, 2000)
    }

    private fun showPresentation(url: String) {
        val target = url.trim().ifBlank { SpatialBrowser.DEFAULT_URL }
        SpatialBrowser.rememberUrl(target)
        val shown = presentation
        if (shown != null) {
            shown.load(target)
            return
        }
        val activity = activityRef.get()
        val display = virtualDisplay?.display
        if (activity == null || display == null || !display.isValid) {
            onLog("browser presentation deferred (activity=${activity != null} display=${display?.displayId})")
            return
        }
        val next = SpatialBrowserPresentation(
            activity,
            display,
            onLog,
            onImeFocus = { text -> main.post { onImeFocus?.invoke(text) } },
            onImeBlur = { main.post { onImeBlur?.invoke() } },
        )
        runCatching {
            next.show()
            presentation = next
            next.load(target)
            onLog("browser presentation display=${display.displayId} $target")
        }.onFailure { err ->
            onLog("browser presentation failed: ${err.message}")
            fallbackStartActivity(target, display.displayId)
        }
    }

    private fun fallbackStartActivity(url: String, displayId: Int) {
        val activity = activityRef.get() ?: appContext
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        val intent = Intent(activity, SpatialBrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(SpatialBrowser.EXTRA_URL, url)
        }
        runCatching {
            activity.startActivity(intent, options.toBundle())
            onLog("browser startActivity fallback display=$displayId $url")
        }.onFailure { err ->
            onLog("browser startActivity failed: ${err.message}")
        }
    }

    private fun dismissPresentation() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    private fun releaseDisplay() {
        dismissPresentation()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        boundSurface = null
        displayId = -1
    }
}
