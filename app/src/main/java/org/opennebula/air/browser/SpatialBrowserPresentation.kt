package org.opennebula.air.browser

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

class SpatialBrowserPresentation(
    context: Context,
    display: Display,
    private val onLog: (String) -> Unit,
    private val onImeFocus: (String) -> Unit = {},
    private val onImeBlur: () -> Unit = {},
) : Presentation(context, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val view = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            SpatialBrowser.configure(this, onLog, onImeFocus, onImeBlur)
            SpatialBrowser.applyUserAgent(this, SpatialBrowser.lastUrl)
        }
        setContentView(view)
        SpatialBrowser.attach(view)
        webView = view
        view.loadUrl(SpatialBrowser.lastUrl)
        onLog("browser presentation shown display=${display.displayId}")
    }

    fun load(url: String) {
        val target = url.trim().ifBlank { SpatialBrowser.DEFAULT_URL }
        SpatialBrowser.rememberUrl(target)
        val view = webView ?: return
        SpatialBrowser.applyUserAgent(view, target)
        view.post { view.loadUrl(target) }
    }

    override fun onStart() {
        super.onStart()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onStop() {
        webView?.onPause()
        super.onStop()
    }

    override fun onDisplayRemoved() {
        dismiss()
    }

    override fun dismiss() {
        val view = webView
        SpatialBrowser.detach(view)
        view?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.dismiss()
    }
}
