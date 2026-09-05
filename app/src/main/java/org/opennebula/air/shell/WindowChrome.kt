package org.opennebula.air.shell

import org.opennebula.air.render.PanelPick
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ChromeHandle {
    HUB,
    SIZE,
    DISTANCE,
    HEIGHT,
    OFFSET,
    PITCH,
    YAW,
    CLOSE,
    BACK,
}

data class ChromeHit(
    val layerId: String,
    val handle: ChromeHandle,
    val x: Float,
    val y: Float,
    val z: Float,
)

class WindowChromeState {
    val hoverLayer = AtomicReference<String?>(null)
    val hoverHandle = AtomicReference<ChromeHandle?>(null)
    val grabHandle = AtomicReference<ChromeHandle?>(null)
    val expandedLayer = AtomicReference<String?>(null)
    val lockoutHandle = AtomicReference<ChromeHandle?>(null)
    val lockoutLayer = AtomicReference<String?>(null)

    fun canGrab(handle: ChromeHandle?, layer: String?): Boolean {
        if (handle == null || layer == null) return false
        return handle != lockoutHandle.get() || layer != lockoutLayer.get()
    }

    fun lockAfterRelease(handle: ChromeHandle, layer: String) {
        if (handle != ChromeHandle.HUB) return
        lockoutHandle.set(handle)
        lockoutLayer.set(layer)
    }

    fun noteHover(handle: ChromeHandle?, layer: String?) {
        if (handle != lockoutHandle.get() || layer != lockoutLayer.get()) {
            lockoutHandle.set(null)
            lockoutLayer.set(null)
        }
    }

    fun clearLockout() {
        lockoutHandle.set(null)
        lockoutLayer.set(null)
    }
}

/**
 * visionOS-style ornaments: a glass pill of tools under the window, plus a
 * separate thin grab handle below it. Close stays at the top-right.
 */
object WindowChrome {
    const val GAP = 0.092f
    const val ICON = 0.084f
    const val SPACING = 0.20f
    const val CURSOR_V_MAX = 1.68f
    const val DISMISS = 0.26f
    const val HANDLE_W = 0.230f
    const val HANDLE_H = 0.042f
    const val PILL_H = 0.168f
    const val HANDLE_DROP = 0.110f
    const val HOVER_HIT = 1.35f
    const val GLYPH_HOVER = 2f

    fun localIconX(handle: ChromeHandle, expanded: Boolean, window: SpatialWindow = SpatialWindow()): Float {
        return when (handle) {
            ChromeHandle.CLOSE -> window.width / 2f - ICON * 0.85f
            ChromeHandle.BACK -> -window.width / 2f + ICON * 0.85f
            ChromeHandle.HUB -> 0f
            else -> {
                if (!expanded) return 0f
                index(handle) * SPACING
            }
        }
    }

    fun localIconY(handle: ChromeHandle, window: SpatialWindow, expanded: Boolean = false): Float {
        val under = -window.height / 2f - GAP
        return when (handle) {
            ChromeHandle.CLOSE, ChromeHandle.BACK -> window.height / 2f + GAP * 0.50f
            ChromeHandle.HUB -> if (expanded) under - HANDLE_DROP else under
            else -> under
        }
    }

    fun localIconY(window: SpatialWindow): Float = localIconY(ChromeHandle.HUB, window, expanded = false)

    fun pillWidth(): Float = 6f * SPACING + ICON * 0.90f

    fun pillHeight(): Float = PILL_H

    fun glyphScale(hovered: Boolean, grabbed: Boolean): Float =
        if (hovered || grabbed) GLYPH_HOVER else 1f

    fun toolPlateWidth(hovered: Boolean, grabbed: Boolean): Float =
        if (hovered || grabbed) ICON * 2.45f else 0f

    fun toolPlateHeight(hovered: Boolean, grabbed: Boolean): Float =
        if (hovered || grabbed) ICON * 1.30f else 0f

    fun handleVisualWidth(hovered: Boolean, grabbed: Boolean): Float =
        HANDLE_W * if (hovered || grabbed) 2.15f else 1f

    fun handleVisualHeight(hovered: Boolean, grabbed: Boolean): Float =
        HANDLE_H * if (hovered || grabbed) 2f else 1f

    fun closeVisual(hovered: Boolean, grabbed: Boolean): Float =
        ICON * 0.48f * if (hovered || grabbed) 2f else 1f

    fun hitHalfX(
        handle: ChromeHandle,
        expanded: Boolean,
        grabbed: Boolean,
        hovered: Boolean = false,
    ): Float {
        val grow = if (hovered || grabbed) HOVER_HIT else 1f
        return when (handle) {
            ChromeHandle.HUB -> (if (expanded) HANDLE_W * 0.58f else HANDLE_W * 0.62f) * grow
            ChromeHandle.CLOSE, ChromeHandle.BACK -> ICON * 0.78f * grow
            else -> SPACING * 0.48f * grow
        }
    }

    fun hitHalfY(
        handle: ChromeHandle,
        expanded: Boolean,
        grabbed: Boolean,
        hovered: Boolean = false,
    ): Float {
        val grow = if (hovered || grabbed) HOVER_HIT else 1f
        return when (handle) {
            ChromeHandle.HUB -> HANDLE_H * 1.45f * grow
            ChromeHandle.CLOSE, ChromeHandle.BACK -> ICON * 0.78f * grow
            else -> max(ICON * 0.72f, PILL_H * 0.52f) * grow
        }
    }

    fun hitSize(
        handle: ChromeHandle,
        expanded: Boolean,
        grabbed: Boolean,
        hovered: Boolean = false,
    ): Float {
        return hitHalfX(handle, expanded, grabbed, hovered) * 2f
    }

    fun handles(expanded: Boolean): List<ChromeHandle> {
        val tools = if (expanded) {
            listOf(
                ChromeHandle.OFFSET,
                ChromeHandle.HEIGHT,
                ChromeHandle.PITCH,
                ChromeHandle.YAW,
                ChromeHandle.SIZE,
                ChromeHandle.DISTANCE,
            )
        } else {
            emptyList()
        }
        return tools + listOf(ChromeHandle.HUB, ChromeHandle.BACK, ChromeHandle.CLOSE)
    }

    fun pick(
        layerId: String,
        window: SpatialWindow,
        expanded: Boolean,
        originX: Float,
        originY: Float,
        originZ: Float,
        dir: FloatArray,
        hoveredHandle: ChromeHandle? = null,
    ): ChromeHit? {
        val plane = PanelPick.intersect(originX, originY, originZ, dir, layerId, window) ?: return null
        val (lx, ly) = localFromCursor(window, plane.u, plane.v)
        val handle = hitAt(window, expanded, lx, ly, grabbed = false, hoveredHandle) ?: return null
        return ChromeHit(layerId, handle, plane.x, plane.y, plane.z)
    }

    fun pickMany(
        windows: List<Pair<String, SpatialWindow>>,
        expandedIds: Set<String>,
        originX: Float,
        originY: Float,
        originZ: Float,
        dir: FloatArray,
        hoverLayer: String? = null,
        hoveredHandle: ChromeHandle? = null,
    ): ChromeHit? {
        var best: ChromeHit? = null
        for ((id, window) in windows) {
            val hover = if (id == hoverLayer) hoveredHandle else null
            val hit = pick(id, window, id in expandedIds, originX, originY, originZ, dir, hover) ?: continue
            val t = hypotDist(originX, originY, originZ, hit.x, hit.y, hit.z)
            val bestT = best?.let { hypotDist(originX, originY, originZ, it.x, it.y, it.z) }
            if (bestT == null || t < bestT) best = hit
        }
        return best
    }

    fun hitAt(
        window: SpatialWindow,
        expanded: Boolean,
        lx: Float,
        ly: Float,
        grabbed: Boolean,
        hoveredHandle: ChromeHandle? = null,
    ): ChromeHandle? {
        var best: ChromeHandle? = null
        var bestD = Float.MAX_VALUE
        for (handle in handles(expanded)) {
            val cx = localIconX(handle, expanded, window)
            val cy = localIconY(handle, window, expanded)
            val live = hoveredHandle == handle
            val hx = hitHalfX(handle, expanded, grabbed && handle != ChromeHandle.HUB, live)
            val hy = hitHalfY(handle, expanded, grabbed && handle != ChromeHandle.HUB, live)
            val dx = abs(lx - cx)
            val dy = abs(ly - cy)
            if (dx <= hx && dy <= hy) {
                val d = max(dx / hx, dy / hy)
                if (d < bestD) {
                    bestD = d
                    best = handle
                }
            }
        }
        return best
    }

    fun nearHub(window: SpatialWindow, lx: Float, ly: Float): Boolean {
        val cx = localIconX(ChromeHandle.HUB, false)
        val cy = localIconY(ChromeHandle.HUB, window, expanded = false)
        val hx = hitHalfX(ChromeHandle.HUB, false, false) * 1.45f
        val hy = hitHalfY(ChromeHandle.HUB, false, false) * 2.1f
        return abs(lx - cx) <= hx && abs(ly - cy) <= hy
    }

    fun localFromCursor(window: SpatialWindow, u: Float, v: Float): Pair<Float, Float> {
        val lx = (u - 0.5f) * window.width
        val ly = (0.5f - v) * window.height
        return lx to ly
    }

    fun handleFromCursor(window: SpatialWindow, expanded: Boolean, u: Float, v: Float): ChromeHandle? {
        val (lx, ly) = localFromCursor(window, u, v)
        return hitAt(window, expanded, lx, ly, grabbed = false)
    }

    fun insideKeepAlive(window: SpatialWindow, lx: Float, ly: Float): Boolean {
        val left = localIconX(ChromeHandle.OFFSET, true) - DISMISS
        val right = localIconX(ChromeHandle.DISTANCE, true) + DISMISS
        val pillY = localIconY(ChromeHandle.HEIGHT, window, expanded = true)
        val hubY = localIconY(ChromeHandle.HUB, window, expanded = true)
        val top = max(pillY, hubY) + PILL_H / 2f + DISMISS
        val bot = min(pillY, hubY) - HANDLE_H - DISMISS
        return lx in left..right && ly in bot..top
    }

    fun approachingDock(v: Float): Boolean = v >= 0.88f

    private fun index(handle: ChromeHandle): Float = when (handle) {
        ChromeHandle.OFFSET -> -2.5f
        ChromeHandle.HEIGHT -> -1.5f
        ChromeHandle.PITCH -> -0.5f
        ChromeHandle.YAW -> 0.5f
        ChromeHandle.SIZE -> 1.5f
        ChromeHandle.DISTANCE -> 2.5f
        ChromeHandle.HUB, ChromeHandle.CLOSE, ChromeHandle.BACK -> 0f
    }

    private fun hypotDist(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
    ): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }
}
