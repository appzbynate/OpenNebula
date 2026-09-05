package org.opennebula.air.shell

object SpatialLayout {
    const val TEX_W = 1280
    const val TEX_H = 720
    const val PANEL_W = 1.72f
    const val PANEL_H = 0.97f
    const val PANEL_X = 0f
    const val PANEL_Y = 0.08f
    /** Default panel / icon depth (meters forward = −Z). Was −2.35; pushed out to ease near-field jitter. */
    const val PANEL_Z = -3.80f
    /** Home icon field at the same depth as app panels. */
    const val ICON_Z = PANEL_Z
    /** World-space ground used to sit the icon grid one empty row up. */
    const val FLOOR_Y = -1.05f
    const val BEZEL = 0.045f
    const val CURSOR = 0.038f
    // Faster panel recapture reduces perceived smear/blur while turning head.
    const val CAPTURE_PERIOD_MS = 33L
}
