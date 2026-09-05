package org.opennebula.air.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opennebula.air.render.StereoConfig

class SpatialWindowTest {
    @Test
    fun default_matches_layout() {
        val w = SpatialWindow()
        assertEquals(SpatialLayout.PANEL_Z, w.z, 1e-5f)
        assertEquals(-SpatialLayout.PANEL_Z, w.distance, 1e-5f)
    }

    @Test
    fun distance_control_sets_negative_z() {
        val w = SpatialWindow.fromControls(4f, 0.1f, 1f)
        assertEquals(-4f, w.z, 1e-5f)
        assertEquals(4f, StereoConfig.convergenceMeters(w.z), 1e-5f)
    }

    @Test
    fun offset_sets_panel_x() {
        val w = SpatialWindow.fromControls(2.35f, 0f, 1f, offsetX = 0.4f)
        assertEquals(0.4f, w.x, 1e-5f)
    }

    @Test
    fun scale_changes_panel_size() {
        val w = SpatialWindow.fromControls(2.35f, SpatialLayout.PANEL_Y, 2f)
        assertEquals(SpatialLayout.PANEL_W * SpatialWindow.SCALE_MAX, w.width, 1e-4f)
    }

    @Test
    fun portrait_aspect_keeps_height_and_corrects_width() {
        val aspect = 9f / 20f
        val w = SpatialWindow.fromControls(2.35f, SpatialLayout.PANEL_Y, 1f, contentAspect = aspect)
        assertEquals(SpatialLayout.PANEL_H, w.height, 1e-4f)
        assertEquals(SpatialLayout.PANEL_H * aspect, w.width, 1e-4f)
    }

    @Test
    fun local_to_world_identity_when_unrotated() {
        val w = SpatialWindow(x = 0.4f, y = 0.1f, z = -2.35f)
        val p = w.localToWorld(0.2f, -0.1f, 0.03f)
        assertEquals(0.6f, p[0], 1e-4f)
        assertEquals(0.0f, p[1], 1e-4f)
        assertEquals(-2.32f, p[2], 1e-4f)
        val back = w.worldToLocal(p[0], p[1], p[2])
        assertEquals(0.2f, back[0], 1e-4f)
        assertEquals(-0.1f, back[1], 1e-4f)
        assertEquals(0.03f, back[2], 1e-4f)
    }

    @Test
    fun yaw_moves_local_x_in_z() {
        val w = SpatialWindow(yaw = 0.4f)
        val right = w.localToWorld(0.5f, 0f, 0f)
        assertTrue(right[2] > w.z)
        val n = w.normal()
        assertTrue(kotlin.math.abs(n[2]) < 1f)
    }

    @Test
    fun facing_user_is_identity_on_default_panel() {
        val w = SpatialWindow()
        val face = SpatialWindow.facingUser(w.x, w.y, w.z)
        assertEquals(0f, face.first, 0.08f)
    }

    @Test
    fun facing_user_yaws_toward_origin_when_offset() {
        val face = SpatialWindow.facingUser(0.8f, 0f, -2.35f)
        assertTrue(face.first > 0.2f)
    }

    @Test
    fun facing_user_normal_points_toward_origin() {
        val x = 0.7f
        val y = 0.35f
        val z = -2.1f
        val (yaw, pitch) = SpatialWindow.facingUser(x, y, z)
        val w = SpatialWindow(x = x, y = y, z = z, yaw = yaw, pitch = pitch, roll = 0f)
        val n = w.normal()
        val len = kotlin.math.sqrt(x * x + y * y + z * z)
        val dot = n[0] * (-x / len) + n[1] * (-y / len) + n[2] * (-z / len)
        assertTrue(dot > 0.98f)
        assertTrue(n[1] < 0f)
    }
}
