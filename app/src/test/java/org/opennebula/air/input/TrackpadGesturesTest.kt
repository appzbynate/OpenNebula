package org.opennebula.air.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGesturesTest {
    @Test
    fun pinch_wins_when_span_grows_faster_than_pan() {
        assertTrue(TrackpadGestures.isPinch(40f, 10f))
        assertFalse(TrackpadGestures.isPinch(8f, 20f))
        assertFalse(TrackpadGestures.isPinch(4f, 1f))
    }

    @Test
    fun three_finger_swipe_needs_travel() {
        assertNull(TrackpadGestures.threeSwipeDir(20f, 400f))
        assertEquals(-1f, TrackpadGestures.threeSwipeDir(-80f, 400f))
        assertEquals(1f, TrackpadGestures.threeSwipeDir(80f, 400f))
    }

    @Test
    fun centroid_and_span_use_pointer_positions() {
        val fingers = listOf(
            TrackpadFinger(0, 10f, 20f),
            TrackpadFinger(1, 30f, 40f),
        )
        assertEquals(20f, TrackpadGestures.centroidX(fingers), 1e-3f)
        assertEquals(30f, TrackpadGestures.centroidY(fingers), 1e-3f)
        assertEquals(28.284f, TrackpadGestures.span(fingers), 0.02f)
    }

    @Test
    fun first_finger_down_does_not_emit_down() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        val cmds = pad.update(listOf(TrackpadFinger(0, 10f, 10f)))
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun one_finger_move_emits_down_then_move() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(listOf(TrackpadFinger(0, 10f, 10f)))
        val cmds = pad.update(listOf(TrackpadFinger(0, 40f, 10f)))
        assertEquals(TrackpadCmd.Down, cmds[0])
        assertTrue(cmds[1] is TrackpadCmd.Move)
        val move = cmds[1] as TrackpadCmd.Move
        assertTrue(move.dxNorm > 0f)
        assertEquals(0f, move.dyNorm, 1e-4f)
    }

    @Test
    fun second_finger_before_move_skips_down() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(listOf(TrackpadFinger(0, 10f, 10f)))
        val cmds = pad.update(
            listOf(TrackpadFinger(0, 10f, 10f), TrackpadFinger(1, 80f, 10f)),
        )
        assertTrue(cmds.none { it is TrackpadCmd.Down })
        assertTrue(cmds.none { it is TrackpadCmd.Cancel })
    }

    @Test
    fun second_finger_after_one_finger_move_cancels_down() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(listOf(TrackpadFinger(0, 10f, 10f)))
        pad.update(listOf(TrackpadFinger(0, 40f, 10f)))
        val cmds = pad.update(
            listOf(TrackpadFinger(0, 40f, 10f), TrackpadFinger(1, 90f, 12f)),
        )
        assertEquals(TrackpadCmd.Cancel, cmds.first())
    }

    @Test
    fun two_finger_pan_scrolls_and_pinch_zooms() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(listOf(TrackpadFinger(0, 100f, 100f), TrackpadFinger(1, 160f, 100f)))
        val pan = pad.update(listOf(TrackpadFinger(0, 100f, 140f), TrackpadFinger(1, 160f, 140f)))
        assertTrue(pan.single() is TrackpadCmd.Scroll)
        val scroll = pan.single() as TrackpadCmd.Scroll
        assertEquals(0f, scroll.dxNorm, 1e-4f)
        assertTrue(scroll.dyNorm > 0f)

        val pinchPad = TrackpadTouch(width = 400f, height = 400f)
        pinchPad.update(listOf(TrackpadFinger(0, 100f, 100f), TrackpadFinger(1, 140f, 100f)))
        val pinch = pinchPad.update(listOf(TrackpadFinger(0, 80f, 100f), TrackpadFinger(1, 180f, 100f)))
        assertTrue(pinch.single() is TrackpadCmd.Pinch)
    }

    @Test
    fun three_finger_does_not_scroll_and_swipe_fires_on_lift() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(
            listOf(
                TrackpadFinger(0, 40f, 80f),
                TrackpadFinger(1, 80f, 80f),
                TrackpadFinger(2, 120f, 80f),
            ),
        )
        val moved = pad.update(
            listOf(
                TrackpadFinger(0, 120f, 90f),
                TrackpadFinger(1, 160f, 90f),
                TrackpadFinger(2, 200f, 90f),
            ),
        )
        assertTrue(moved.none { it is TrackpadCmd.Scroll })
        pad.update(listOf(TrackpadFinger(0, 120f, 90f), TrackpadFinger(1, 160f, 90f)))
        val lift = pad.lift()
        val swipe = lift.filterIsInstance<TrackpadCmd.ThreeSwipe>().single()
        assertEquals(1f, swipe.dir)
        val up = lift.filterIsInstance<TrackpadCmd.Up>().single()
        assertFalse(up.click)
    }

    @Test
    fun move_screen_two_finger_is_distance_not_scroll() {
        val pad = TrackpadTouch(width = 400f, height = 400f, moveScreen = true)
        pad.update(listOf(TrackpadFinger(0, 100f, 100f), TrackpadFinger(1, 160f, 100f)))
        val cmds = pad.update(listOf(TrackpadFinger(0, 100f, 140f), TrackpadFinger(1, 160f, 140f)))
        assertTrue(cmds.single() is TrackpadCmd.Distance)
        assertTrue(cmds.none { it is TrackpadCmd.Scroll })
        val dist = cmds.single() as TrackpadCmd.Distance
        assertTrue(dist.dyNorm > 0f)
    }

    @Test
    fun tap_without_travel_emits_down_then_click() {
        val pad = TrackpadTouch(width = 400f, height = 400f)
        pad.update(listOf(TrackpadFinger(0, 12f, 12f)))
        val lift = pad.lift()
        assertEquals(TrackpadCmd.Down, lift[0])
        assertEquals(TrackpadCmd.Up(true), lift[1])
    }
}
