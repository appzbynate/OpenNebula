package org.opennebula.air.browser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/** Fallback if Presentation.show() fails on a given device. */
class SpatialBrowserActivity : Activity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            SpatialBrowser.configure(this)
        }
        setContentView(view)
        SpatialBrowser.attach(view)
        webView = view
        val url = intent.getStringExtra(SpatialBrowser.EXTRA_URL)
            ?.trim()
            ?.ifBlank { null }
            ?: SpatialBrowser.lastUrl
        SpatialBrowser.rememberUrl(url)
        SpatialBrowser.applyUserAgent(view, url)
        view.loadUrl(url)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(SpatialBrowser.EXTRA_URL)?.trim()?.ifBlank { null }?.let { url ->
            SpatialBrowser.rememberUrl(url)
            webView?.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        val view = webView
        SpatialBrowser.detach(view)
        view?.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
            return
        }
        super.onBackPressed()
    }
}
