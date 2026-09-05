package org.opennebula.air.input

import kotlin.math.abs
import kotlin.math.hypot

data class TrackpadFinger(val id: Int, val x: Float, val y: Float)

sealed class TrackpadCmd {
    data object Down : TrackpadCmd()
    data object Cancel : TrackpadCmd()
    data class Move(val dxNorm: Float, val dyNorm: Float, val pixels: Float) : TrackpadCmd()
    data class Scroll(val dxNorm: Float, val dyNorm: Float) : TrackpadCmd()
    data class Pinch(val factor: Float) : TrackpadCmd()
    data class Distance(val dyNorm: Float) : TrackpadCmd()
    data class ThreeSwipe(val dir: Float) : TrackpadCmd()
    data class Up(val click: Boolean) : TrackpadCmd()
}

object TrackpadGestures {
    const val TAP_SLOP_PX = 18f
    const val PINCH_VS_PAN = 0.6f
    const val THREE_SWIPE_FRAC = 0.08f
    const val CONFIRM_MOVE_PX = 0.5f

    fun isPinch(spanDelta: Float, panMag: Float): Boolean =
        spanDelta > panMag * PINCH_VS_PAN && spanDelta > 6f

    fun threeSwipeDir(dx: Float, width: Float): Float? {
        val threshold = width * THREE_SWIPE_FRAC
        if (abs(dx) < threshold) return null
        return if (dx > 0f) 1f else -1f
    }

    fun centroidX(fingers: List<TrackpadFinger>): Float {
        if (fingers.isEmpty()) return 0f
        return fingers.map { it.x }.average().toFloat()
    }

    fun centroidY(fingers: List<TrackpadFinger>): Float {
        if (fingers.isEmpty()) return 0f
        return fingers.map { it.y }.average().toFloat()
    }

    fun span(fingers: List<TrackpadFinger>): Float {
        if (fingers.size < 2) return 0f
        val a = fingers[0]
        val b = fingers[1]
        return hypot(b.x - a.x, b.y - a.y)
    }
}

/**
 * Pointer-count state machine for the on-phone trackpad. Does not call Down
 * until a gesture is confirmed as one finger (move or lift). A second finger
 * cancels that Down so chrome grab / browser click are not stolen by pinch.
 */
class TrackpadTouch(
    var width: Float = 1f,
    var height: Float = 1f,
    var moveScreen: Boolean = false,
) {
    private var downConfirmed = false
    private var usedMulti = false
    private var sawThree = false
    private var travel = 0f
    private var lastOneX: Float? = null
    private var lastOneY: Float? = null
    private var lastSpan: Float? = null
    private var lastCentroidX: Float? = null
    private var lastCentroidY: Float? = null
    private var threeStartX: Float? = null
    private var threeLastX = 0f

    fun reset() {
        downConfirmed = false
        usedMulti = false
        sawThree = false
        travel = 0f
        lastOneX = null
        lastOneY = null
        lastSpan = null
        lastCentroidX = null
        lastCentroidY = null
        threeStartX = null
        threeLastX = 0f
    }

    fun update(fingers: List<TrackpadFinger>): List<TrackpadCmd> {
        if (fingers.isEmpty()) return lift()
        val cmds = ArrayList<TrackpadCmd>(4)
        val n = fingers.size
        if (n >= 3 && !moveScreen) {
            cancelDown(cmds)
            usedMulti = true
            sawThree = true
            val cx = TrackpadGestures.centroidX(fingers)
            if (threeStartX == null) threeStartX = cx
            threeLastX = cx
            clearTwoFinger()
            return cmds
        }
        if (n >= 2) {
            cancelDown(cmds)
            usedMulti = true
            if (sawThree) {
                clearTwoFinger()
                return cmds
            }
            val cx = TrackpadGestures.centroidX(fingers)
            val cy = TrackpadGestures.centroidY(fingers)
            val span = TrackpadGestures.span(fingers)
            val prevCx = lastCentroidX
            val prevCy = lastCentroidY
            val prevSpan = lastSpan
            lastCentroidX = cx
            lastCentroidY = cy
            lastSpan = span
            if (prevCx != null && prevCy != null && prevSpan != null && prevSpan > 1f) {
                val panX = cx - prevCx
                val panY = cy - prevCy
                val panMag = hypot(panX, panY)
                val spanDelta = abs(span - prevSpan)
                val w = width.coerceAtLeast(1f)
                val h = height.coerceAtLeast(1f)
                if (moveScreen) {
                    cmds += TrackpadCmd.Distance(panY / h)
                } else if (TrackpadGestures.isPinch(spanDelta, panMag)) {
                    cmds += TrackpadCmd.Pinch((span / prevSpan).coerceIn(0.7f, 1.4f))
                } else {
                    cmds += TrackpadCmd.Scroll(panX / w, panY / h)
                }
            }
            return cmds
        }
        if (usedMulti) return cmds
        val finger = fingers.first()
        val prevX = lastOneX
        val prevY = lastOneY
        lastOneX = finger.x
        lastOneY = finger.y
        clearTwoFinger()
        if (prevX != null && prevY != null) {
            val dx = finger.x - prevX
            val dy = finger.y - prevY
            val pixels = hypot(dx, dy)
            if (pixels > TrackpadGestures.CONFIRM_MOVE_PX) {
                confirmDown(cmds)
                val w = width.coerceAtLeast(1f)
                val h = height.coerceAtLeast(1f)
                cmds += TrackpadCmd.Move(dx / w * 1.35f, dy / h * 1.35f, pixels)
                travel += pixels
            }
        }
        return cmds
    }

    fun lift(): List<TrackpadCmd> {
        val cmds = ArrayList<TrackpadCmd>(4)
        if (!usedMulti) {
            confirmDown(cmds)
            cmds += TrackpadCmd.Up(!moveScreen && travel < TrackpadGestures.TAP_SLOP_PX)
        } else {
            if (sawThree && !moveScreen) {
                TrackpadGestures.threeSwipeDir(
                    threeLastX - (threeStartX ?: threeLastX),
                    width.coerceAtLeast(1f),
                )?.let { cmds += TrackpadCmd.ThreeSwipe(it) }
            }
            cmds += TrackpadCmd.Up(false)
        }
        reset()
        return cmds
    }

    fun abort(): List<TrackpadCmd> {
        val cmds = ArrayList<TrackpadCmd>(2)
        cancelDown(cmds)
        cmds += TrackpadCmd.Up(false)
        reset()
        return cmds
    }

    private fun confirmDown(cmds: MutableList<TrackpadCmd>) {
        if (downConfirmed || usedMulti) return
        cmds += TrackpadCmd.Down
        downConfirmed = true
    }

    private fun cancelDown(cmds: MutableList<TrackpadCmd>) {
        if (!downConfirmed) return
        cmds += TrackpadCmd.Cancel
        downConfirmed = false
    }

    private fun clearTwoFinger() {
        lastSpan = null
        lastCentroidX = null
        lastCentroidY = null
    }
}
