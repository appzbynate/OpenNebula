package org.opennebula.air.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowChromeTest {
    @Test
    fun collapsed_hub_is_centered_under_window() {
        val w = SpatialWindow(x = 0.4f, y = 0.1f, z = -2.35f, width = 1.72f, height = 0.97f)
        assertEquals(0f, WindowChrome.localIconX(ChromeHandle.HUB, false), 1e-4f)
        assertTrue(WindowChrome.localIconY(w) < 0f)
        assertEquals(-w.height / 2f - WindowChrome.GAP, WindowChrome.localIconY(w), 1e-4f)
    }

    @Test
    fun expanded_icons_spread_left_and_right() {
        val w = SpatialWindow()
        val offset = WindowChrome.localIconX(ChromeHandle.OFFSET, true)
        val dist = WindowChrome.localIconX(ChromeHandle.DISTANCE, true)
        assertTrue(offset < 0f)
        assertTrue(dist > 0f)
        assertEquals(0f, WindowChrome.localIconX(ChromeHandle.HUB, true), 1e-4f)
        assertTrue(WindowChrome.localIconX(ChromeHandle.PITCH, true) < 0f)
        assertTrue(WindowChrome.localIconX(ChromeHandle.YAW, true) > 0f)
        assertTrue(WindowChrome.localIconX(ChromeHandle.OFFSET, true) < WindowChrome.localIconX(ChromeHandle.PITCH, true))
        assertTrue(WindowChrome.localIconX(ChromeHandle.DISTANCE, true) > WindowChrome.localIconX(ChromeHandle.SIZE, true))
    }

    @Test
    fun grab_handle_sits_below_the_glass_pill() {
        val w = SpatialWindow()
        val pillY = WindowChrome.localIconY(ChromeHandle.HEIGHT, w, expanded = true)
        val hubY = WindowChrome.localIconY(ChromeHandle.HUB, w, expanded = true)
        assertTrue(hubY < pillY)
        assertTrue(WindowChrome.pillWidth() > WindowChrome.ICON * 3f)
    }

    @Test
    fun hit_at_hub_when_collapsed() {
        val w = SpatialWindow()
        val x = WindowChrome.localIconX(ChromeHandle.HUB, false)
        val y = WindowChrome.localIconY(w)
        assertEquals(ChromeHandle.HUB, WindowChrome.hitAt(w, false, x, y, false))
        assertNull(WindowChrome.hitAt(w, false, x + 0.5f, y, false))
        assertEquals(ChromeHandle.HUB, WindowChrome.hitAt(w, false, x, y + 0.03f, false))
    }

    @Test
    fun cursor_near_bottom_maps_to_dock() {
        val w = SpatialWindow()
        assertTrue(WindowChrome.approachingDock(0.92f))
        val yawX = WindowChrome.localIconX(ChromeHandle.YAW, true, w)
        val yawY = WindowChrome.localIconY(ChromeHandle.YAW, w, true)
        val u = yawX / w.width + 0.5f
        val v = 0.5f - yawY / w.height
        assertEquals(ChromeHandle.YAW, WindowChrome.handleFromCursor(w, true, u, v))
        val hubY = WindowChrome.localIconY(ChromeHandle.HUB, w, true)
        val hv = 0.5f - hubY / w.height
        assertEquals(ChromeHandle.HUB, WindowChrome.handleFromCursor(w, true, 0.5f, hv))
    }

    @Test
    fun keep_alive_covers_gaps_between_icons() {
        val w = SpatialWindow()
        val pitchX = WindowChrome.localIconX(ChromeHandle.PITCH, true)
        val yawX = WindowChrome.localIconX(ChromeHandle.YAW, true)
        val midX = (pitchX + yawX) / 2f
        val y = WindowChrome.localIconY(ChromeHandle.HEIGHT, w, expanded = true)
        assertNull(WindowChrome.hitAt(w, true, midX, y, false))
        assertTrue(WindowChrome.insideKeepAlive(w, midX, y))
    }

    @Test
    fun tool_spacing_is_wide_enough_for_hover() {
        assertTrue(WindowChrome.SPACING in 0.18f..0.22f)
        val pitch = WindowChrome.localIconX(ChromeHandle.PITCH, true)
        val yaw = WindowChrome.localIconX(ChromeHandle.YAW, true)
        val idle = WindowChrome.hitHalfX(ChromeHandle.PITCH, true, false, false)
        val hover = WindowChrome.hitHalfX(ChromeHandle.PITCH, true, false, true)
        assertTrue(hover > idle)
        assertTrue(pitch + idle < yaw - idle)
        assertTrue(WindowChrome.pillWidth() <= 1.72f)
        assertTrue(WindowChrome.handleVisualWidth(true, false) > WindowChrome.HANDLE_W * 1.9f)
        assertTrue(WindowChrome.glyphScale(true, false) == 2f)
        assertTrue(WindowChrome.toolPlateWidth(true, false) > WindowChrome.toolPlateHeight(true, false))
        assertTrue(WindowChrome.closeVisual(true, false) > WindowChrome.closeVisual(false, false) * 1.9f)
        val w = SpatialWindow()
        val hubX = WindowChrome.localIconX(ChromeHandle.HUB, false)
        val hubY = WindowChrome.localIconY(w)
        assertEquals(ChromeHandle.HUB, WindowChrome.hitAt(w, false, hubX, hubY, false))
    }

    @Test
    fun back_sits_top_left_opposite_close() {
        val w = SpatialWindow()
        val backX = WindowChrome.localIconX(ChromeHandle.BACK, false, w)
        val closeX = WindowChrome.localIconX(ChromeHandle.CLOSE, false, w)
        val y = WindowChrome.localIconY(ChromeHandle.BACK, w)
        assertTrue(backX < 0f)
        assertTrue(closeX > 0f)
        assertEquals(-closeX, backX, 1e-4f)
        assertEquals(WindowChrome.localIconY(ChromeHandle.CLOSE, w), y, 1e-4f)
        assertEquals(ChromeHandle.BACK, WindowChrome.hitAt(w, false, backX, y, false))
        assertEquals(ChromeHandle.CLOSE, WindowChrome.hitAt(w, false, closeX, y, false))
        assertTrue(ChromeHandle.BACK in WindowChrome.handles(false))
    }

    @Test
    fun keep_alive_ends_well_past_the_rail() {
        val w = SpatialWindow()
        val y = WindowChrome.localIconY(w)
        assertTrue(WindowChrome.insideKeepAlive(w, 0f, y))
        assertTrue(!WindowChrome.insideKeepAlive(w, 0f, y - WindowChrome.DISMISS - 0.2f))
    }

    @Test
    fun grab_lockout_blocks_same_icon_until_hover_leaves() {
        val chrome = WindowChromeState()
        assertTrue(chrome.canGrab(ChromeHandle.HUB, "home"))
        chrome.lockAfterRelease(ChromeHandle.HUB, "home")
        assertFalse(chrome.canGrab(ChromeHandle.HUB, "home"))
        chrome.noteHover(ChromeHandle.HUB, "home")
        assertFalse(chrome.canGrab(ChromeHandle.HUB, "home"))
        chrome.noteHover(null, "home")
        assertTrue(chrome.canGrab(ChromeHandle.HUB, "home"))
        chrome.lockAfterRelease(ChromeHandle.HUB, "home")
        chrome.noteHover(ChromeHandle.SIZE, "home")
        assertTrue(chrome.canGrab(ChromeHandle.SIZE, "home"))
        chrome.lockAfterRelease(ChromeHandle.SIZE, "home")
        assertTrue(chrome.canGrab(ChromeHandle.SIZE, "home"))
    }
}
