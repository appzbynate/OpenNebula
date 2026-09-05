package org.opennebula.air.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSculptTest {
    @Test
    fun round_box_center_is_inside() {
        val d = IconSculpt.sdRoundBox(0f, 0f, 0f, 0.74f, 0.74f, 0.16f, 0.16f)
        assertTrue(d < 0f)
    }

    @Test
    fun round_box_far_corner_is_outside() {
        val d = IconSculpt.sdRoundBox(2f, 2f, 2f, 0.74f, 0.74f, 0.16f, 0.16f)
        assertTrue(d > 1f)
    }

    @Test
    fun raster_has_opaque_core_and_clear_corners() {
        val spec = IconSculpt.Spec(0.88f, 0.12f, 0.22f, glyph = IconSculpt.Glyph.YOUTUBE)
        val px = IconSculpt.raster(spec, 48)
        val center = px[24 * 48 + 24]
        val corner = px[0]
        val centerA = (center ushr 24) and 0xFF
        val cornerA = (corner ushr 24) and 0xFF
        assertTrue(centerA > 200)
        assertTrue(cornerA < 40)
        val r = (center shr 16) and 0xFF
        val g = (center shr 8) and 0xFF
        val b = center and 0xFF
        assertTrue(r > g + 20)
        assertTrue(r + g + b > 80)
        val edge = px[24 * 48 + 45]
        assertTrue(((edge ushr 24) and 0xFF) > 180)
    }

    @Test
    fun two_d_round_rect_matches_box_slice() {
        val d = IconSculpt.sdRoundBox2(0f, 0f, 0.5f, 0.5f, 0.1f)
        assertTrue(d < 0f)
        assertEquals(IconSculpt.SIZE, 192)
    }
}
