package org.opennebula.air.shell

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class IconSprite(
    val component: String,
    val pose: SpatialWindow,
    val bitmap: Bitmap?,
    val page: Int,
    val slot: Int,
    val rimR: Float = 0.22f,
    val rimG: Float = 0.28f,
    val rimB: Float = 0.38f,
    val name: String = "",
)

data class IconSource(
    val component: String,
    val bitmap: Bitmap?,
    val rimR: Float = 0.22f,
    val rimG: Float = 0.28f,
    val rimB: Float = 0.38f,
    val name: String = "",
)

class IconGridState {
    val tiles = AtomicReference<List<IconSprite>>(emptyList())
    val hoverComponent = AtomicReference<String?>(null)
    val generation = AtomicInteger(0)
    val page = AtomicInteger(0)
    val pan = AtomicReference(0f)
    val pages = AtomicInteger(1)
}

object IconGrid {
    const val PAGE = 20
    val ROWS = intArrayOf(4, 6, 6, 4)
    /** Square icon face; 1/3 larger than 0.11 m. */
    const val FACE = 0.147f
    const val LABEL_H = 0.048f
    const val ICON_LABEL_GAP = 0.014f
    const val GAP_Y = 0.072f
    /** Same horizontal pitch on every row (was tight on 6-wide middle rows). */
    const val GAP_X = 0.16f
    const val GAP_X_WIDE = GAP_X
    const val GAP_X_TIGHT = GAP_X
    const val PAGE_GAP = 0.55f
    const val THICKNESS = 0.016f
    const val PARALLAX = 0.010f
    const val TAP_PAN_MAX = 0.055f
    const val ICON_UV = 128f / 176f

    fun pageCount(appCount: Int): Int {
        if (appCount <= 0) return 1
        return ceil(appCount / PAGE.toFloat()).roundToInt().coerceAtLeast(1)
    }

    fun rowCount(row: Int): Int = ROWS.getOrElse(row) { 0 }

    fun slotOf(indexOnPage: Int): Triple<Int, Int, Int>? {
        if (indexOnPage !in 0 until PAGE) return null
        var remaining = indexOnPage
        for (row in ROWS.indices) {
            val count = ROWS[row]
            if (remaining < count) return Triple(row, remaining, count)
            remaining -= count
        }
        return null
    }

    fun gapX(count: Int): Float = GAP_X

    fun cellHeight(): Float = FACE + ICON_LABEL_GAP + LABEL_H

    fun pageStride(): Float = fieldWidth() + PAGE_GAP

    fun fieldWidth(): Float = 6 * FACE + 5 * GAP_X

    fun fieldHeight(): Float = 4 * cellHeight() + 3 * GAP_Y

    fun pageRange(page: Int, pan: Float, pages: Int): IntRange {
        val last = (pages - 1).coerceAtLeast(0)
        val p = page.coerceIn(0, last)
        if (abs(pan) < 1e-3f) return p..p
        return (p - 1).coerceAtLeast(0)..(p + 1).coerceAtMost(last)
    }

    fun rowOccupied(page: Int, row: Int, appCount: Int): Int {
        if (row !in ROWS.indices || appCount <= 0) return 0
        var startSlot = 0
        for (r in 0 until row) startSlot += ROWS[r]
        val first = page * PAGE + startSlot
        if (first >= appCount) return 0
        return (appCount - first).coerceAtMost(ROWS[row])
    }

    fun localX(col: Int, count: Int): Float {
        if (count <= 0) return 0f
        val gap = gapX(count)
        val span = count * FACE + (count - 1) * gap
        val start = -span / 2f + FACE / 2f
        return start + col * (FACE + gap)
    }

    fun canSwipeLeft(page: Int, pan: Float, pages: Int): Boolean {
        val last = (pages - 1).coerceAtLeast(0)
        if (last <= 0) return false
        val p = page.coerceIn(0, last)
        return p > 0 || pan > TAP_PAN_MAX * 0.25f
    }

    fun canSwipeRight(page: Int, pan: Float, pages: Int): Boolean {
        val last = (pages - 1).coerceAtLeast(0)
        if (last <= 0) return false
        val p = page.coerceIn(0, last)
        return p < last || pan < -TAP_PAN_MAX * 0.25f
    }

    fun localY(row: Int): Float {
        val cell = cellHeight()
        val span = fieldHeight()
        val top = span / 2f - cell / 2f
        val cellY = top - row * (cell + GAP_Y)
        return cellY + (cell / 2f - FACE / 2f)
    }

    /** Field center Y: bottom icons sit *three* empty rows above the floor. */
    fun fieldY(floorY: Float = SpatialLayout.FLOOR_Y): Float {
        val last = ROWS.lastIndex
        val bottomCenter = localY(last)
        val rowPitch = cellHeight() + GAP_Y
        val desired = floorY + FACE / 2f + FACE + GAP_Y + 2f * rowPitch
        return desired - bottomCenter
    }

    fun shouldShowIcons(occupiedSlots: Int): Boolean = occupiedSlots <= 0

    fun clampPage(page: Int, pan: Float, pages: Int): Pair<Int, Float> {
        val stride = pageStride()
        val raw = page - pan / stride
        val snapped = raw.roundToInt().coerceIn(0, (pages - 1).coerceAtLeast(0))
        return snapped to 0f
    }

    fun sprites(
        apps: List<IconSource>,
        page: Int,
        pan: Float,
        field: SpatialWindow,
    ): List<IconSprite> {
        if (apps.isEmpty()) return emptyList()
        val pages = pageCount(apps.size)
        val stride = pageStride()
        val range = pageRange(page, pan, pages)
        val out = ArrayList<IconSprite>(PAGE * 3)
        for (p in range) {
            val originX = (p - page) * stride + pan
            for (slot in 0 until PAGE) {
                val appIndex = p * PAGE + slot
                if (appIndex >= apps.size) break
                val (row, col, _) = slotOf(slot) ?: continue
                val occupied = rowOccupied(p, row, apps.size)
                if (occupied <= 0 || col >= occupied) continue
                val lx = localX(col, occupied) + originX
                val ly = localY(row)
                val world = field.localToWorld(lx, ly, 0f)
                val src = apps[appIndex]
                out += IconSprite(
                    component = src.component,
                    pose = SpatialWindow(
                        x = world[0],
                        y = world[1],
                        z = world[2],
                        width = FACE,
                        height = FACE,
                        yaw = field.yaw,
                        pitch = field.pitch,
                        roll = field.roll,
                    ),
                    bitmap = src.bitmap,
                    page = p,
                    slot = slot,
                    rimR = src.rimR,
                    rimG = src.rimG,
                    rimB = src.rimB,
                    name = src.name,
                )
            }
        }
        return out
    }

    fun hit(tiles: List<IconSprite>, dir: FloatArray): IconSprite? {
        var best: IconSprite? = null
        var bestT = Float.MAX_VALUE
        for (tile in tiles) {
            val hit = org.opennebula.air.render.PanelPick.intersect(
                0f, 0f, 0f, dir, tile.component, tile.pose,
            ) ?: continue
            if (hit.u !in 0f..1f || hit.v !in 0f..1f) continue
            if (hit.t < bestT) {
                bestT = hit.t
                best = tile
            }
        }
        return best
    }

    fun nearest(tiles: List<IconSprite>, u: Float, v: Float, field: SpatialWindow): IconSprite? {
        val lx = (u - 0.5f) * field.width
        val ly = (0.5f - v) * field.height
        var best: IconSprite? = null
        var bestD = Float.MAX_VALUE
        for (tile in tiles) {
            val local = field.worldToLocal(tile.pose.x, tile.pose.y, tile.pose.z)
            val d = abs(local[0] - lx) + abs(local[1] - ly)
            if (d < bestD) {
                bestD = d
                best = tile
            }
        }
        return best
    }
}
