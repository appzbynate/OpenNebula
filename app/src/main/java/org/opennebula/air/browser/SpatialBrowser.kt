package org.opennebula.air.browser

import android.hardware.display.DisplayManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process spatial browser. The WebView lives on a private VirtualDisplay
 * owned by this app, so trackpad input can be dispatched directly — no Shizuku.
 */
object SpatialBrowser {
    const val ID = "browser"
    const val DISPLAY_NAME = "opennebula-browser"
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 160
    const val ASPECT = WIDTH / HEIGHT.toFloat()
    const val DEFAULT_URL = "https://www.google.com"
    const val EXTRA_URL = "org.opennebula.air.browser.URL"
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val IME_BRIDGE = "OpenNebulaIme"
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"

    /**
     * Private presentation display for in-app WebView.
     * Never PUBLIC (glasses hijack) and never AUTO_MIRROR.
     */
    val VD_FLAGS: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            FLAG_DESTROY_CONTENT_ON_REMOVAL

    val webView = AtomicReference<WebView?>(null)

    @Volatile
    var lastUrl: String = DEFAULT_URL
        private set

    fun isId(id: String): Boolean = id == ID

    fun skipDisplayName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains(DISPLAY_NAME) || n.contains("opennebula-browser")
    }

    fun pixel(u: Float, v: Float, width: Int, height: Int): Pair<Float, Float> {
        return u.coerceIn(0f, 1f) * width to v.coerceIn(0f, 1f) * height
    }

    fun rememberUrl(url: String) {
        val next = url.trim()
        if (next.isNotBlank()) lastUrl = next
    }

    fun attach(view: WebView) {
        webView.set(view)
    }

    fun detach(view: WebView?) {
        if (view != null) webView.compareAndSet(view, null)
        flushCookies()
    }

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun jsSetValue(text: String): String {
        val quoted = jsString(text)
        return """
            (function(){
              var e=document.activeElement;
              if(!e) return;
              var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:
                        (e.tagName==='INPUT'?HTMLInputElement.prototype:null);
              if(proto){
                var desc=Object.getOwnPropertyDescriptor(proto,'value');
                if(desc&&desc.set) desc.set.call(e,$quoted); else e.value=$quoted;
              } else if('value' in e){
                e.value=$quoted;
              } else if(e.isContentEditable){
                e.innerText=$quoted;
              }
              try{e.dispatchEvent(new InputEvent('input',{bubbles:true,data:$quoted,inputType:'insertReplacementText'}));}catch(x){
                e.dispatchEvent(new Event('input',{bubbles:true}));
              }
              e.dispatchEvent(new Event('change',{bubbles:true}));
            })();
        """.trimIndent()
    }

    fun jsString(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                '<' -> out.append("\\u003c")
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    const val FOCUS_HOOK = """
        (function(){
          function tag(el){
            var n=0;
            while(el && n++<10){
              if(!el.tagName){ el=el.parentElement; continue; }
              var t=el.tagName;
              if(t==='INPUT'){
                var ty=(el.type||'text').toLowerCase();
                if(ty==='button'||ty==='submit'||ty==='checkbox'||ty==='radio'||ty==='file'||ty==='hidden'||ty==='image'){
                  el=el.parentElement; continue;
                }
                return true;
              }
              if(t==='TEXTAREA'||el.isContentEditable===true||el.getAttribute('role')==='textbox') return true;
              el=el.parentElement;
            }
            return false;
          }
          function val(el){
            if(!el) return '';
            if('value' in el) return el.value||'';
            return el.innerText||'';
          }
          window.__opennebulaImeTag=tag;
          window.__opennebulaImeVal=val;
          if(window.__opennebulaIme) return;
          window.__opennebulaIme=true;
          document.addEventListener('focusin', function(e){
            if(tag(e.target) && window.OpenNebulaIme) OpenNebulaIme.onFocus(val(e.target));
          }, true);
          document.addEventListener('click', function(e){
            if(tag(e.target) && window.OpenNebulaIme) OpenNebulaIme.onFocus(val(e.target));
          }, true);
        })();
    """

    const val IME_PROBE = """
        (function(){
          var tag=window.__opennebulaImeTag;
          var val=window.__opennebulaImeVal;
          if(!tag) return;
          var e=document.activeElement;
          if(window.OpenNebulaIme && tag(e)) OpenNebulaIme.onFocus(val(e));
        })();
    """

    @Suppress("DEPRECATION")
    fun configure(
        view: WebView,
        onLog: (String) -> Unit = {},
        onImeFocus: (String) -> Unit = {},
        onImeBlur: () -> Unit = {},
    ) {
        view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        runCatching {
            android.view.View::class.java
                .getMethod("setShowSoftInputOnFocus", java.lang.Boolean.TYPE)
                .invoke(view, false)
        }
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.loadsImagesAutomatically = true
        DesktopChrome.apply(view, inject = true)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.removeJavascriptInterface(IME_BRIDGE)
        view.addJavascriptInterface(ImeBridge(onImeFocus, onImeBlur), IME_BRIDGE)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                view.post { req.grant(req.resources) }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                val host = view ?: return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = host
                resultMsg.sendToTarget()
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val next = request?.url?.toString() ?: return false
                view?.let { applyUserAgent(it, next) }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let {
                    rememberUrl(it)
                    view?.let { v -> applyUserAgent(v, it) }
                }
                onLog("page start $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { rememberUrl(it) }
                view?.evaluateJavascript(FOCUS_HOOK, null)
                flushCookies()
                onLog("page done $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onLog("page error ${error?.description} ${request.url}")
                }
            }
        }
    }

    fun applyUserAgent(view: WebView, url: String) {
        DesktopChrome.apply(view, inject = false)
    }

    class ImeBridge(
        private val onFocus: (String) -> Unit,
        private val onBlur: () -> Unit,
    ) {
        @JavascriptInterface
        fun onFocus(text: String) {
            onFocus(text)
        }

        @JavascriptInterface
        fun onBlur() {
            onBlur()
        }
    }
}
