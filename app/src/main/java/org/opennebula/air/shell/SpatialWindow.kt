package org.opennebula.air.shell

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * World-space placement of the floating panel. Units are meters, -Z is forward.
 * [yaw]/[pitch]/[roll] are radians around the window center (Y, X, Z).
 */
data class SpatialWindow(
    val x: Float = SpatialLayout.PANEL_X,
    val y: Float = SpatialLayout.PANEL_Y,
    val z: Float = SpatialLayout.PANEL_Z,
    val width: Float = SpatialLayout.PANEL_W,
    val height: Float = SpatialLayout.PANEL_H,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
) {
    val distance: Float get() = -z

    /** Local +X right, +Y up, +Z toward the user when unrotated. */
    fun localToWorld(lx: Float, ly: Float, lz: Float): FloatArray {
        val r = rotateYxz(lx, ly, lz, yaw, pitch, roll)
        return floatArrayOf(x + r[0], y + r[1], z + r[2])
    }

    fun worldToLocal(wx: Float, wy: Float, wz: Float): FloatArray {
        return rotateYxzInverse(wx - x, wy - y, wz - z, yaw, pitch, roll)
    }

    fun normal(): FloatArray = rotateYxz(0f, 0f, 1f, yaw, pitch, roll)

    companion object {
        const val DIST_MIN = 1.2f
        const val DIST_MAX = 6.0f
        const val HEIGHT_MIN = -0.55f
        const val HEIGHT_MAX = 0.75f
        const val SCALE_MIN = 0.55f
        const val SCALE_MAX = 1.8f
        const val OFFSET_MIN = -2.4f
        const val OFFSET_MAX = 2.4f
        const val YAW_MAX = 1.05f
        const val PITCH_MAX = 0.70f
        const val ROLL_MAX = 0.55f

        /** Yaw/pitch so the panel faces the origin (user), roll 0. */
        fun facingUser(x: Float, y: Float, z: Float): Pair<Float, Float> {
            val yaw = atan2(x, -z)
            val horiz = hypot(x, z).coerceAtLeast(1e-4f)
            val pitch = atan2(y, horiz)
            return yaw.coerceIn(-YAW_MAX, YAW_MAX) to pitch.coerceIn(-PITCH_MAX, PITCH_MAX)
        }

        fun fromControls(
            distance: Float,
            height: Float,
            scale: Float,
            offsetX: Float = SpatialLayout.PANEL_X,
            contentAspect: Float = SpatialLayout.PANEL_W / SpatialLayout.PANEL_H,
            yaw: Float = 0f,
            pitch: Float = 0f,
            roll: Float = 0f,
        ): SpatialWindow {
            val d = distance.coerceIn(DIST_MIN, DIST_MAX)
            val s = scale.coerceIn(SCALE_MIN, SCALE_MAX)
            val aspect = contentAspect.coerceIn(0.30f, 2.8f)
            val width: Float
            val heightM: Float
            if (aspect >= 1f) {
                width = SpatialLayout.PANEL_W * s
                heightM = width / aspect
            } else {
                heightM = SpatialLayout.PANEL_H * s
                width = heightM * aspect
            }
            return SpatialWindow(
                x = offsetX.coerceIn(OFFSET_MIN, OFFSET_MAX),
                y = height.coerceIn(HEIGHT_MIN, HEIGHT_MAX),
                z = -d,
                width = width,
                height = heightM,
                yaw = yaw.coerceIn(-YAW_MAX, YAW_MAX),
                pitch = pitch.coerceIn(-PITCH_MAX, PITCH_MAX),
                roll = roll.coerceIn(-ROLL_MAX, ROLL_MAX),
            )
        }

        private fun rotateYxz(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float): FloatArray {
            val cr = cos(roll)
            val sr = sin(roll)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val cy = cos(yaw)
            val sy = sin(yaw)
            val x1 = x * cr - y * sr
            val y1 = x * sr + y * cr
            val y2 = y1 * cp - z * sp
            val z2 = y1 * sp + z * cp
            return floatArrayOf(
                x1 * cy - z2 * sy,
                y2,
                x1 * sy + z2 * cy,
            )
        }

        private fun rotateYxzInverse(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float): FloatArray {
            val cr = cos(roll)
            val sr = sin(roll)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val cy = cos(yaw)
            val sy = sin(yaw)
            val x1 = x * cy + z * sy
            val z1 = -x * sy + z * cy
            val y2 = y * cp + z1 * sp
            val z2 = -y * sp + z1 * cp
            return floatArrayOf(
                x1 * cr + y2 * sr,
                -x1 * sr + y2 * cr,
                z2,
            )
        }
    }
}
