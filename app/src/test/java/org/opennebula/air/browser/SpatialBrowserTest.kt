package org.opennebula.air.browser

import android.hardware.display.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialBrowserTest {
    @Test
    fun virtual_display_is_private_and_not_mirror() {
        assertEquals(0, SpatialBrowser.VD_FLAGS and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC)
        assertEquals(0, SpatialBrowser.VD_FLAGS and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        assertTrue(SpatialBrowser.VD_FLAGS and DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY != 0)
        assertTrue(SpatialBrowser.VD_FLAGS and DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION != 0)
        assertTrue(SpatialBrowser.VD_FLAGS and SpatialBrowser.FLAG_DESTROY_CONTENT_ON_REMOVAL != 0)
    }

    @Test
    fun glasses_picker_skips_browser_display_name() {
        assertTrue(SpatialBrowser.skipDisplayName(SpatialBrowser.DISPLAY_NAME))
        assertTrue(SpatialBrowser.skipDisplayName("opennebula-browser"))
        assertFalse(SpatialBrowser.skipDisplayName("Nreal Air"))
    }

    @Test
    fun component_and_id_are_recognized() {
        assertTrue(SpatialBrowser.isId("browser"))
        assertEquals("browser", SpatialBrowser.ID)
    }

    @Test
    fun landscape_aspect_is_wider_than_tall() {
        assertTrue(SpatialBrowser.ASPECT > 1f)
        assertEquals(1280, SpatialBrowser.WIDTH)
        assertEquals(720, SpatialBrowser.HEIGHT)
    }

    @Test
    fun js_set_value_quotes_user_text() {
        assertEquals("\"it's \\\"quoted\\\"\"", SpatialBrowser.jsString("it's \"quoted\""))
        val js = SpatialBrowser.jsSetValue("it's \"quoted\"")
        assertTrue(js.contains("e.value="))
        assertTrue(js.contains("quoted"))
        assertFalse(js.contains("e.value=it's"))
    }

    @Test
    fun focus_hook_talks_to_ime_bridge() {
        assertTrue(SpatialBrowser.FOCUS_HOOK.contains("OpenNebulaIme.onFocus"))
        assertTrue(SpatialBrowser.IME_PROBE.contains("OpenNebulaIme.onFocus"))
        assertFalse(SpatialBrowser.IME_PROBE.contains("OpenNebulaIme.onBlur"))
        assertEquals("OpenNebulaIme", SpatialBrowser.IME_BRIDGE)
        assertTrue(SpatialBrowser.jsSetValue("hi").contains("HTMLInputElement"))
    }

    @Test
    fun parsec_uses_desktop_chrome_ua() {
        assertTrue(WebApps.wantsDesktopChrome("https://web.parsec.app"))
        assertTrue(WebApps.find("parsec")!!.desktopChrome)
        assertTrue(SpatialBrowser.DESKTOP_CHROME_UA.contains("Chrome/"))
        assertTrue(SpatialBrowser.DESKTOP_CHROME_UA.contains("Windows"))
        assertTrue(WebApps.wantsDesktopChrome("https://www.youtube.com"))
        assertEquals(160, SpatialBrowser.DPI)
    }

    @Test
    fun desktop_chrome_shim_looks_like_windows_chrome() {
        assertTrue(DesktopChrome.SHIM.contains("Google Chrome"))
        assertTrue(DesktopChrome.SHIM.contains("userAgentData"))
        assertTrue(DesktopChrome.SHIM.contains("Windows"))
        assertTrue(DesktopChrome.SHIM.contains("window.chrome"))
        assertFalse(DesktopChrome.SHIM.contains("Android"))
    }
}
