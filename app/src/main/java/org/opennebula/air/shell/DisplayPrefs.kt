package org.opennebula.air.shell

import android.content.Context
import org.opennebula.air.render.StereoConfig

class DisplayPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Always false until the user toggles 3D; old `stereo=true` prefs are ignored. */
    fun stereo(): Boolean = false

    fun brightness(): Int = prefs.getInt(BRIGHTNESS, 5).coerceIn(0, 7)

    fun ipdMeters(): Float = prefs.getFloat(IPD, StereoConfig.IPD_METERS)
        .coerceIn(StereoConfig.IPD_MIN, StereoConfig.IPD_MAX)

    fun distance(): Float = prefs.getFloat(DIST, -SpatialLayout.PANEL_Z)
        .coerceIn(SpatialWindow.DIST_MIN, SpatialWindow.DIST_MAX)

    fun height(): Float = prefs.getFloat(HEIGHT, SpatialLayout.PANEL_Y)
        .coerceIn(SpatialWindow.HEIGHT_MIN, SpatialWindow.HEIGHT_MAX)

    fun scale(): Float = prefs.getFloat(SCALE, 1f)
        .coerceIn(SpatialWindow.SCALE_MIN, SpatialWindow.SCALE_MAX)

    fun offsetX(): Float = prefs.getFloat(OFFSET, SpatialLayout.PANEL_X)
        .coerceIn(SpatialWindow.OFFSET_MIN, SpatialWindow.OFFSET_MAX)

    fun save(
        stereo: Boolean,
        brightness: Int,
        ipdMeters: Float,
        distance: Float,
        height: Float,
        scale: Float,
        offsetX: Float,
    ) {
        prefs.edit()
            .putBoolean(STEREO, stereo)
            .putInt(BRIGHTNESS, brightness.coerceIn(0, 7))
            .putFloat(IPD, ipdMeters)
            .putFloat(DIST, distance)
            .putFloat(HEIGHT, height)
            .putFloat(SCALE, scale)
            .putFloat(OFFSET, offsetX)
            .apply()
    }

    companion object {
        private const val NAME = "opennebula_display"
        private const val STEREO = "stereo"
        private const val BRIGHTNESS = "brightness"
        private const val IPD = "ipd"
        // v2 invalidates old 2.35 m defaults so the new farther layout applies once.
        private const val DIST = "distance_v2"
        private const val HEIGHT = "height"
        private const val SCALE = "scale"
        private const val OFFSET = "offset_x"
    }
}
