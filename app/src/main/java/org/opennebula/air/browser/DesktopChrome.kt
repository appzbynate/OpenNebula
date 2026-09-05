package org.opennebula.air.browser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Make the spatial WebView look like desktop Chrome to sites (Parsec, Xbox)
 * that reject Android WebView even when the UA string is spoofed.
 */
object DesktopChrome {
    const val FULL_VERSION = "151.0.7922.199"

    val SHIM = """
        (function(){
          var brands=[
            {brand:'Not=A?Brand',version:'10'},
            {brand:'Chromium',version:'151'},
            {brand:'Google Chrome',version:'151'}
          ];
          var full=[
            {brand:'Not=A?Brand',version:'10.0.0.0'},
            {brand:'Chromium',version:'151.0.7922.199'},
            {brand:'Google Chrome',version:'151.0.7922.199'}
          ];
          var uad={
            brands:brands,
            mobile:false,
            platform:'Windows',
            getHighEntropyValues:function(){
              return Promise.resolve({
                architecture:'x86',
                bitness:'64',
                brands:brands,
                fullVersionList:full,
                mobile:false,
                model:'',
                platform:'Windows',
                platformVersion:'15.0.0',
                uaFullVersion:'151.0.7922.199',
                wow64:false
              });
            },
            toJSON:function(){return {brands:brands,mobile:false,platform:'Windows'};}
          };
          try{Object.defineProperty(navigator,'userAgentData',{configurable:true,enumerable:true,get:function(){return uad;}});}catch(e){}
          try{Object.defineProperty(navigator,'platform',{configurable:true,get:function(){return 'Win32';}});}catch(e){}
          try{Object.defineProperty(navigator,'vendor',{configurable:true,get:function(){return 'Google Inc.';}});}catch(e){}
          try{Object.defineProperty(navigator,'maxTouchPoints',{configurable:true,get:function(){return 0;}});}catch(e){}
          try{Object.defineProperty(navigator,'userAgent',{configurable:true,get:function(){return '${SpatialBrowser.DESKTOP_CHROME_UA}';}});}catch(e){}
          if(!window.chrome) window.chrome={app:{},csi:function(){},loadTimes:function(){},runtime:{}};
        })();
    """.trimIndent()

    fun apply(view: WebView, inject: Boolean = true) {
        val settings = view.settings
        settings.userAgentString = SpatialBrowser.DESKTOP_CHROME_UA
        applyClientHints(settings)
        if (inject) injectBeforePageScripts(view)
    }

    private fun applyClientHints(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val chrome = brand("Google Chrome")
            val chromium = brand("Chromium")
            val grease = brand("Not=A?Brand", "10", "10.0.0.0")
            val meta = UserAgentMetadata.Builder()
                .setBrandVersionList(listOf(grease, chromium, chrome))
                .setFullVersion(FULL_VERSION)
                .setPlatform("Windows")
                .setPlatformVersion("15.0.0")
                .setArchitecture("x86")
                .setModel("")
                .setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, meta)
        }
    }

    private fun injectBeforePageScripts(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, SHIM, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, SpatialBrowser.FOCUS_HOOK, setOf("*"))
        }
    }

    private fun brand(
        name: String,
        major: String = "151",
        full: String = FULL_VERSION,
    ): UserAgentMetadata.BrandVersion {
        return UserAgentMetadata.BrandVersion.Builder()
            .setBrand(name)
            .setMajorVersion(major)
            .setFullVersion(full)
            .build()
    }
}
