package org.opennebula.air.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opennebula.air.imu.Orientation
import kotlin.math.abs

class PoseMathTest {
    @Test
    fun nreal_vertical_fov_is_46_diagonal_at_16_by_9() {
        val aspect = 16f / 9f
        val diag = Math.toRadians(StereoConfig.DIAGONAL_FOV_DEG.toDouble())
        val vfov = 2.0 * kotlin.math.atan(kotlin.math.tan(diag / 2.0) / kotlin.math.sqrt(1.0 + aspect * aspect))
        assertEquals(Math.toDegrees(vfov).toFloat(), StereoConfig.FOVY_DEG, 0.05f)
    }

    @Test
    fun identity_view_is_identity() {
        val view = PoseMath.viewFromOrientation(Orientation.IDENTITY)
        val id = PoseMath.identity4()
        for (i in id.indices) {
            assertEquals(id[i], view[i], 1e-5f)
        }
    }

    @Test
    fun centered_off_axis_matches_symmetric_perspective() {
        val aspect = 16f / 9f
        val symmetric = PoseMath.perspective(StereoConfig.FOVY_DEG, aspect, StereoConfig.NEAR, StereoConfig.FAR)
        val offAxis = PoseMath.perspectiveOffAxis(
            StereoConfig.FOVY_DEG,
            aspect,
            StereoConfig.NEAR,
            StereoConfig.FAR,
            0f,
            StereoConfig.convergenceMeters(),
        )
        for (i in symmetric.indices) {
            assertEquals("m[$i]", symmetric[i], offAxis[i], 1e-4f)
        }
    }

    @Test
    fun left_and_right_eye_views_are_mirrored_on_x() {
        val center = PoseMath.viewFromOrientation(Orientation.IDENTITY)
        val left = PoseMath.viewForEye(center, StereoConfig.leftEyeX())
        val right = PoseMath.viewForEye(center, StereoConfig.rightEyeX())
        assertEquals(StereoConfig.IPD_METERS / 2f, left[12], 1e-5f)
        assertEquals(-StereoConfig.IPD_METERS / 2f, right[12], 1e-5f)
        assertEquals(0f, left[13], 1e-5f)
        assertEquals(0f, right[13], 1e-5f)
        assertNotEquals(left[12], right[12], 1e-4f)
    }

    @Test
    fun stereo_frusta_shift_opposite_directions() {
        val aspect = 0.89f
        val conv = StereoConfig.convergenceMeters()
        val left = PoseMath.perspectiveOffAxis(
            StereoConfig.FOVY_DEG, aspect, StereoConfig.NEAR, StereoConfig.FAR,
            StereoConfig.leftEyeX(), conv,
        )
        val right = PoseMath.perspectiveOffAxis(
            StereoConfig.FOVY_DEG, aspect, StereoConfig.NEAR, StereoConfig.FAR,
            StereoConfig.rightEyeX(), conv,
        )
        val center = PoseMath.perspectiveOffAxis(
            StereoConfig.FOVY_DEG, aspect, StereoConfig.NEAR, StereoConfig.FAR,
            0f, conv,
        )
        assertTrue(abs(left[8]) > 1e-4f)
        assertEquals(-left[8], right[8], 1e-4f)
        assertEquals(0f, center[8], 1e-4f)
    }

    @Test
    fun world_from_view_local_identity() {
        val view = PoseMath.viewFromOrientation(Orientation.IDENTITY)
        val world = PoseMath.worldFromViewLocal(view, 0.05f, -0.4f, -0.2f)
        assertEquals(0.05f, world[0], 1e-5f)
        assertEquals(-0.4f, world[1], 1e-5f)
        assertEquals(-0.2f, world[2], 1e-5f)
    }

    @Test
    fun yaw_left_puts_world_left_on_camera_forward() {
        val s = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        val view = PoseMath.viewFromQuat(c, 0f, s, 0f)
        val left = PoseMath.transformPoint(view, -1f, 0f, 0f)
        assertEquals(0f, left[0], 1e-4f)
        assertEquals(0f, left[1], 1e-4f)
        assertEquals(-1f, left[2], 1e-4f)
        val right = PoseMath.transformPoint(view, 1f, 0f, 0f)
        assertEquals(0f, right[0], 1e-4f)
        assertEquals(0f, right[1], 1e-4f)
        assertEquals(1f, right[2], 1e-4f)
    }

    @Test
    fun looking_at_world_right_puts_it_on_camera_forward() {
        val s = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        val view = PoseMath.viewFromQuat(c, 0f, -s, 0f)
        val p = PoseMath.transformPoint(view, 1f, 0f, 0f)
        assertEquals(0f, p[0], 1e-4f)
        assertEquals(0f, p[1], 1e-4f)
        assertEquals(-1f, p[2], 1e-4f)
    }

    @Test
    fun ipd_stays_on_camera_x_after_yaw() {
        val s = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        val view = PoseMath.viewFromQuat(c, 0f, s, 0f)
        val left = PoseMath.viewForEye(view, StereoConfig.leftEyeX())
        assertEquals(StereoConfig.IPD_METERS / 2f, left[12], 1e-4f)
        assertEquals(0f, left[13], 1e-4f)
        assertEquals(0f, left[14], 1e-4f)
        val worldSpace = PoseMath.multiply(view, PoseMath.translation(-StereoConfig.leftEyeX(), 0f, 0f))
        assertTrue(abs(worldSpace[14]) > 0.01f)
    }

    @Test
    fun view_uses_quat_not_lagged_eulers() {
        val half = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        val mismatched = Orientation(
            yawDeg = 0f,
            pitchDeg = 12f,
            rollDeg = -8f,
            qw = c,
            qx = 0f,
            qy = half,
            qz = 0f,
        )
        val fromPose = PoseMath.viewFromOrientation(mismatched)
        val fromQuat = PoseMath.viewFromQuat(c, 0f, half, 0f)
        for (i in fromPose.indices) {
            assertEquals("m[$i]", fromQuat[i], fromPose[i], 1e-5f)
        }
    }

    @Test
    fun integrate_quat_yaw_matches_view_from_quat() {
        val rad = Math.toRadians(90.0).toFloat()
        var w = 1f
        var x = 0f
        var y = 0f
        var z = 0f
        val steps = 200
        val dt = 1f / steps
        repeat(steps) {
            val q = PoseMath.integrateQuat(w, x, y, z, 0f, rad, 0f, dt)
            w = q[0]
            x = q[1]
            y = q[2]
            z = q[3]
        }
        val view = PoseMath.viewFromQuat(w, x, y, z)
        val p = PoseMath.transformPoint(view, -1f, 0f, 0f)
        assertEquals(0f, p[0], 0.04f)
        assertEquals(0f, p[1], 0.04f)
        assertEquals(-1f, p[2], 0.04f)
    }

    @Test
    fun slerp_quat_midpoint_is_halfway_yaw() {
        val half = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        // 0° → 90° yaw about Y; midpoint ≈ 45°.
        val mid = PoseMath.slerpQuat(1f, 0f, 0f, 0f, c, 0f, half, 0f, 0.5f)
        val angle = 2f * kotlin.math.atan2(mid[2], mid[0])
        assertEquals(Math.toRadians(45.0).toFloat(), angle, 0.05f)
    }

    @Test
    fun neck_model_at_neutral_pose_has_zero_translation() {
        val view = PoseMath.viewFromQuat(1f, 0f, 0f, 0f, neckModel = true)
        assertEquals(0f, view[12], 1e-6f)
        assertEquals(0f, view[13], 1e-6f)
        assertEquals(0f, view[14], 1e-6f)
    }

    @Test
    fun neck_model_at_yaw_translates_camera_in_arc() {
        val s = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        val c = kotlin.math.cos(Math.toRadians(45.0)).toFloat()
        // 90 deg yaw
        val view = PoseMath.viewFromQuat(c, 0f, s, 0f, neckModel = true)
        // Camera translation should shift along the arc of the neck offset
        val arcRadius = kotlin.math.sqrt(StereoConfig.NECK_FORWARD * StereoConfig.NECK_FORWARD)
        val shift = kotlin.math.sqrt(view[12] * view[12] + view[14] * view[14])
        assertTrue("Camera should translate along neck arc (got $shift)", shift > 0.05f)
    }

    @Test
    fun so3_exponential_map_preserves_exact_norm_and_angle() {
        val rad = Math.toRadians(120.0).toFloat() // Rapid 120 deg/s rotation
        var w = 1f
        var x = 0f
        var y = 0f
        var z = 0f
        val steps = 1000
        val dt = 1f / steps // 1 second total -> 120 deg total rotation
        repeat(steps) {
            val q = PoseMath.integrateQuat(w, x, y, z, 0f, rad, 0f, dt)
            w = q[0]
            x = q[1]
            y = q[2]
            z = q[3]
            val norm = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
            assertEquals(1f, norm, 1e-6f)
        }
        val expectedHalf = Math.toRadians(60.0).toFloat()
        assertEquals(kotlin.math.cos(expectedHalf.toDouble()).toFloat(), w, 0.01f)
        assertEquals(kotlin.math.sin(expectedHalf.toDouble()).toFloat(), y, 0.01f)
    }
}
