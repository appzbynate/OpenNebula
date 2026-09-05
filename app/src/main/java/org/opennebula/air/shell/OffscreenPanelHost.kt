package org.opennebula.air.shell

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.opennebula.air.ui.theme.OpenNebulaTheme

class OffscreenPanelHost(
    private val activity: ComponentActivity,
    private val panel: PanelFramebuffer,
    content: @Composable () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var capturing = false
    private var listenerAdded = false

    private val view = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = View.VISIBLE
        alpha = 1f
        setContent {
            val density = Density(2f, 1f)
            CompositionLocalProvider(LocalDensity provides density) {
                OpenNebulaTheme { content() }
            }
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (capturing) main.post { layoutAndCapture() }
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    }

    private val capture = object : Runnable {
        override fun run() {
            if (!capturing) return
            layoutAndCapture()
            main.postDelayed(this, SpatialLayout.CAPTURE_PERIOD_MS)
        }
    }

    fun attach() {
        val parent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (view.parent == null) {
            val lp = FrameLayout.LayoutParams(panel.width, panel.height).apply {
                leftMargin = -panel.width - 64
                topMargin = 0
            }
            view.layoutParams = lp
            parent.addView(view, 0, lp)
        }
        if (!listenerAdded) {
            view.addOnAttachStateChangeListener(attachListener)
            listenerAdded = true
        }
    }

    fun detach() {
        stop()
        if (listenerAdded) {
            view.removeOnAttachStateChangeListener(attachListener)
            listenerAdded = false
        }
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun start() {
        capturing = true
        main.removeCallbacks(capture)
        main.post(capture)
    }

    fun stop() {
        capturing = false
        main.removeCallbacks(capture)
    }

    fun dispatchTap(nx: Float, ny: Float) {
        if (!view.isAttachedToWindow) return
        layoutAndCapture()
        val x = nx.coerceIn(0f, 1f) * panel.width
        val y = ny.coerceIn(0f, 1f) * panel.height
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        layoutAndCapture()
    }

    private fun layoutAndCapture() {
        if (!view.isAttachedToWindow) return
        runCatching {
            val w = View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(panel.height, View.MeasureSpec.EXACTLY)
            view.measure(w, h)
            // Keep this off the phone screen. layout(0,0,…) covers the HUD and eats Anchor.
            val left = -panel.width - 64
            view.layout(left, 0, left + panel.width, panel.height)
            panel.drawFrom(view)
        }
    }
}
