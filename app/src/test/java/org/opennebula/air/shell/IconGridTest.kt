package org.opennebula.air.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconGridTest {
    @Test
    fun page_holds_twenty_apps_in_4_6_6_4_rows() {
        assertEquals(20, IconGrid.PAGE)
        assertEquals(4, IconGrid.rowCount(0))
        assertEquals(6, IconGrid.rowCount(1))
        assertEquals(6, IconGrid.rowCount(2))
        assertEquals(4, IconGrid.rowCount(3))
        var sum = 0
        for (row in 0 until 4) sum += IconGrid.rowCount(row)
        assertEquals(20, sum)
    }

    @Test
    fun page_count_rounds_up() {
        assertEquals(1, IconGrid.pageCount(0))
        assertEquals(1, IconGrid.pageCount(20))
        assertEquals(2, IconGrid.pageCount(21))
        assertEquals(3, IconGrid.pageCount(41))
    }

    @Test
    fun slot_maps_to_row_and_column() {
        val first = IconGrid.slotOf(0)!!
        assertEquals(0, first.first)
        assertEquals(0, first.second)
        assertEquals(4, first.third)
        val mid = IconGrid.slotOf(4)!!
        assertEquals(1, mid.first)
        assertEquals(0, mid.second)
        assertEquals(6, mid.third)
        val last = IconGrid.slotOf(19)!!
        assertEquals(3, last.first)
        assertEquals(3, last.second)
        assertEquals(4, last.third)
    }

    @Test
    fun all_rows_use_the_same_horizontal_gap() {
        assertEquals(IconGrid.GAP_X, IconGrid.gapX(4), 0f)
        assertEquals(IconGrid.GAP_X, IconGrid.gapX(6), 0f)
        val pitch4 = IconGrid.localX(1, 4) - IconGrid.localX(0, 4)
        val pitch6 = IconGrid.localX(1, 6) - IconGrid.localX(0, 6)
        assertEquals(IconGrid.FACE + IconGrid.GAP_X, pitch4, 1e-4f)
        assertEquals(pitch4, pitch6, 1e-4f)
    }

    @Test
    fun swipe_past_halfway_snaps_to_next_page() {
        val stride = IconGrid.pageStride()
        val (page, pan) = IconGrid.clampPage(0, -stride * 0.6f, 3)
        assertEquals(1, page)
        assertEquals(0f, pan, 1e-4f)
    }

    @Test
    fun empty_dock_should_show_icons() {
        assertTrue(IconGrid.shouldShowIcons(0))
        assertTrue(!IconGrid.shouldShowIcons(1))
    }

    @Test
    fun ten_web_apps_fill_the_first_two_rows() {
        val apps = (0 until 10).map { IconSource("app$it", null) }
        val field = SpatialWindow(
            z = -2.15f,
            width = IconGrid.fieldWidth(),
            height = IconGrid.fieldHeight(),
        )
        val tiles = IconGrid.sprites(apps, page = 0, pan = 0f, field = field)
        assertEquals(10, tiles.size)
        assertEquals("app0", tiles.first { it.page == 0 && it.slot == 0 }.component)
        assertEquals("app9", tiles.first { it.page == 0 && it.slot == 9 }.component)
    }

    @Test
    fun pan_shows_neighbor_page() {
        val apps = (0 until 25).map { IconSource("app$it", null) }
        val field = SpatialWindow(z = -2.15f, width = IconGrid.fieldWidth(), height = IconGrid.fieldHeight())
        val tiles = IconGrid.sprites(apps, page = 0, pan = 0.2f, field = field)
        assertTrue(tiles.any { it.page == 1 })
    }

    @Test
    fun single_leftover_icon_is_centered() {
        val apps = (0 until 21).map { IconSource("app$it", null) }
        val field = SpatialWindow(
            z = SpatialLayout.ICON_Z,
            width = IconGrid.fieldWidth(),
            height = IconGrid.fieldHeight(),
        )
        val tiles = IconGrid.sprites(apps, page = 1, pan = 0f, field = field)
        val leftover = tiles.first { it.page == 1 }
        assertEquals(0f, leftover.pose.x, 1e-4f)
        assertEquals(1, IconGrid.rowOccupied(1, 0, 21))
    }

    @Test
    fun short_last_row_is_centered() {
        val apps = (0 until 12).map { IconSource("app$it", null) }
        val field = SpatialWindow(
            z = SpatialLayout.ICON_Z,
            width = IconGrid.fieldWidth(),
            height = IconGrid.fieldHeight(),
        )
        val tiles = IconGrid.sprites(apps, page = 0, pan = 0f, field = field)
        assertEquals(2, IconGrid.rowOccupied(0, 2, 12))
        assertEquals(0, IconGrid.rowOccupied(0, 3, 12))
        val a = tiles.first { it.slot == 10 }
        val b = tiles.first { it.slot == 11 }
        assertEquals(IconGrid.localX(0, 2), a.pose.x, 1e-4f)
        assertEquals(IconGrid.localX(1, 2), b.pose.x, 1e-4f)
        assertEquals(0f, (a.pose.x + b.pose.x) / 2f, 1e-4f)
        assertTrue(a.pose.x < 0f)
        assertTrue(b.pose.x > 0f)
        val packedLeft = IconGrid.localX(0, 6)
        assertTrue(a.pose.x > packedLeft + 0.05f)
    }

    @Test
    fun icons_have_breathing_room() {
        assertTrue(IconGrid.GAP_X >= 0.06f)
        assertTrue(IconGrid.GAP_Y >= 0.06f)
        val gap = IconGrid.gapX(6)
        assertTrue(gap / IconGrid.FACE >= 0.45f)
    }

    @Test
    fun grid_sits_one_row_above_the_floor() {
        val y = IconGrid.fieldY()
        val field = SpatialWindow(
            y = y,
            z = SpatialLayout.ICON_Z,
            width = IconGrid.fieldWidth(),
            height = IconGrid.fieldHeight(),
        )
        val apps = (0 until 20).map { IconSource("app$it", null) }
        val tiles = IconGrid.sprites(apps, page = 0, pan = 0f, field = field)
        val lowestBottom = tiles.minOf { it.pose.y - IconGrid.FACE / 2f }
        val rowPitch = IconGrid.cellHeight() + IconGrid.GAP_Y
        val expected = SpatialLayout.FLOOR_Y + IconGrid.FACE + IconGrid.GAP_Y + 2f * rowPitch
        assertEquals(expected, lowestBottom, 0.03f)
        assertTrue(lowestBottom < 0f)
        assertTrue(lowestBottom > SpatialLayout.FLOOR_Y + 0.2f)
    }

    @Test
    fun twelve_apps_keep_full_rows_unshifted() {
        val apps = (0 until 12).map { IconSource("app$it", null) }
        val field = SpatialWindow(z = SpatialLayout.ICON_Z, width = IconGrid.fieldWidth(), height = IconGrid.fieldHeight())
        val tiles = IconGrid.sprites(apps, page = 0, pan = 0f, field = field)
        val first = tiles.first { it.slot == 0 }
        val lastTop = tiles.first { it.slot == 3 }
        assertEquals(IconGrid.localX(0, 4), first.pose.x, 1e-4f)
        assertEquals(IconGrid.localX(3, 4), lastTop.pose.x, 1e-4f)
        val mid = tiles.first { it.slot == 4 }
        assertEquals(IconGrid.localX(0, 6), mid.pose.x, 1e-4f)
    }

    @Test
    fun page_arrow_visibility() {
        assertTrue(!IconGrid.canSwipeLeft(0, 0f, 1))
        assertTrue(!IconGrid.canSwipeRight(0, 0f, 1))
        assertTrue(!IconGrid.canSwipeLeft(0, 0f, 2))
        assertTrue(IconGrid.canSwipeRight(0, 0f, 2))
        assertTrue(IconGrid.canSwipeLeft(1, 0f, 2))
        assertTrue(!IconGrid.canSwipeRight(1, 0f, 2))
        assertTrue(IconGrid.canSwipeLeft(0, 0.2f, 3))
        assertTrue(IconGrid.canSwipeRight(2, -0.2f, 3))
        assertTrue(!IconGrid.canSwipeLeft(0, 0f, 3))
        assertTrue(IconGrid.canSwipeRight(0, 0f, 3))
    }

    @Test
    fun icon_field_matches_panel_depth() {
        assertEquals(SpatialLayout.PANEL_Z, SpatialLayout.ICON_Z, 1e-5f)
        assertEquals(-3.80f, SpatialLayout.ICON_Z, 1e-4f)
    }
}
