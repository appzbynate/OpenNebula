package org.opennebula.air.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opennebula.air.shell.SpatialWindow

class PanelPickTest {
    @Test
    fun forward_ray_hits_default_panel() {
        val window = SpatialWindow()
        val dir = PanelPick.rayFromYawPitchDeg(0f, 0f)
        assertEquals(0f, dir[0], 1e-4f)
        assertEquals(-1f, dir[2], 1e-4f)
        val hit = PanelPick.pick(listOf("home" to window), dir)
        assertNotNull(hit)
        assertEquals(0.5f, hit!!.u, 0.08f)
        assertTrue(hit.v in 0f..1f)
        assertEquals(window.z, hit.z, 1e-3f)
    }

    @Test
    fun yawed_panel_still_hits_near_center() {
        val window = SpatialWindow(yaw = 0.25f)
        val hit = PanelPick.pick(listOf("home" to window), PanelPick.rayFromYawPitchDeg(0f, 0f))
        assertNotNull(hit)
        assertTrue(hit!!.u in 0.2f..0.8f)
        assertTrue(hit.v in 0f..1f)
    }

    @Test
    fun closer_window_wins_when_they_overlap() {
        val far = SpatialWindow(x = 0f, y = 0f, z = -3.0f, width = 1.72f, height = 0.97f)
        val near = SpatialWindow(x = 0f, y = 0f, z = -1.8f, width = 1.72f, height = 0.97f)
        val hit = PanelPick.pick(
            listOf("far" to far, "near" to near),
            PanelPick.rayFromYawPitchDeg(0f, 0f),
        )
        assertNotNull(hit)
        assertEquals("near", hit!!.layerId)
        assertTrue(hit.t < 2.2f)
    }
}
