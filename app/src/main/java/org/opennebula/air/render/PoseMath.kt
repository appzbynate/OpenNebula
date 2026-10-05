package org.opennebula.air.render

import org.opennebula.air.imu.Orientation
import org.opennebula.air.shell.SpatialLayout
import kotlin.math.tan

object StereoConfig {
    /** Default adult IPD. World units match meters (panel sits at SpatialLayout.PANEL_Z). */
    const val IPD_METERS = 0.063f
    const val IPD_MIN = 0.052f
    const val IPD_MAX = 0.078f
    /** Physiological neck pivot: cervical spine C1/C2 is ~10cm below eyes, ~8cm behind eyes */
    const val NECK_UP = 0.10f
    const val NECK_FORWARD = -0.08f
    /** 46° diagonal at 16:9 → ~23.5° vertical (Nreal Air optics). */
    const val DIAGONAL_FOV_DEG = 46f
    const val FOVY_DEG = 23.5f
    /** Optical panel aspect (each eye). HalfSBS stretches ½-buffer to this. */
    const val OPTICAL_ASPECT = 16f / 9f
    const val NEAR = 0.08f
    const val FAR = 40f

    fun convergenceMeters(panelZ: Float = SpatialLayout.PANEL_Z): Float {
        return kotlin.math.abs(panelZ).coerceAtLeast(NEAR)
    }

    fun leftEyeX(ipd: Float = IPD_METERS): Float = -ipd / 2f

    fun rightEyeX(ipd: Float = IPD_METERS): Float = ipd / 2f
}

object PoseMath {
    /**
     * Gyro look-ahead cap for render-time pose prediction.
     *
     * The full pipeline from IMU packet arrival to photon-on-eye is:
     *   IMU thread→poseRef (~1ms) + render thread wake (~1ms) + GL submit (~5ms)
     *   + SurfaceFlinger composite (~4ms) + DP Alt Mode scanout (~8ms) + panel response (~8ms) ≈ 27ms
     *
     * At 90Hz display, one frame is 11.1ms stale *on average*. We target 30ms to cover
     * worst-case frame timing without over-shooting. At 120Hz the effective need drops to ~22ms.
     */
    const val PREDICT_LEAD_MAX_S = 0.030f
    const val PREDICT_LEAD_MAX_MS = 30f

    fun identity4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun translation(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f,
    )

    fun perspective(fovyDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovyDeg.toDouble()) / 2.0).toFloat()
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f,
        )
    }

    /**
     * Off-axis frustum so each eye looks at the same convergence plane.
     * [eyeX] is the camera offset in head space (negative = left eye).
     */
    fun perspectiveOffAxis(
        fovyDeg: Float,
        aspect: Float,
        near: Float,
        far: Float,
        eyeX: Float,
        convergence: Float,
    ): FloatArray {
        val top = near * tan(Math.toRadians(fovyDeg.toDouble()) / 2.0).toFloat()
        val bottom = -top
        val halfWidth = aspect * convergence * tan(Math.toRadians(fovyDeg.toDouble()) / 2.0).toFloat()
        val conv = convergence.coerceAtLeast(near)
        val left = (-halfWidth - eyeX) * (near / conv)
        val right = (halfWidth - eyeX) * (near / conv)
        return frustum(left, right, bottom, top, near, far)
    }

    fun frustum(
        left: Float,
        right: Float,
        bottom: Float,
        top: Float,
        near: Float,
        far: Float,
    ): FloatArray {
        val rl = (right - left).coerceAtLeast(1e-6f)
        val tb = (top - bottom).coerceAtLeast(1e-6f)
        val nf = 1f / (near - far)
        return floatArrayOf(
            2f * near / rl, 0f, 0f, 0f,
            0f, 2f * near / tb, 0f, 0f,
            (right + left) / rl, (top + bottom) / tb, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f,
        )
    }

    /**
     * View for one eye: IPD in **camera space**.
     * `T(-eyeX) * V` so translation happens after the head rotation.
     * `V * T` (Android `translateM(out, view, -eyeX, …)`) rotates the eye
     * offset with the world and shears on look-around — do not use it.
     */
    fun viewForEye(centerView: FloatArray, eyeX: Float): FloatArray {
        return multiply(translation(-eyeX, 0f, 0f), centerView)
    }

    /** View matrix: inverse of head pose. Y-up, camera looks down -Z. */
    fun viewFromOrientation(pose: Orientation, neckModel: Boolean = false): FloatArray {
        return viewFromQuat(pose.qw, pose.qx, pose.qy, pose.qz, neckModel = neckModel)
    }

    /**
     * View = inverse of camera rotation. Conjugate [q], convert R(q*) to a 4x4,
     * pack column-major for `glUniformMatrix4fv` (transpose = false):
     * col0=(r00,r10,r20), col1=(r01,r11,r21), col2=(r02,r12,r22).
     *
     * If [neckModel] is enabled, applies anatomical neck pivot compensation:
     * the human eye revolves around cervical spine C1/C2 (neck pivot: ~10cm below, ~8cm behind eyes).
     * When turning head, camera translates along an arc, eliminating artificial parallax swing.
     */
    fun viewFromQuat(
        qw: Float,
        qx: Float,
        qy: Float,
        qz: Float,
        neckModel: Boolean = false,
        neckY: Float = StereoConfig.NECK_UP,
        neckZ: Float = StereoConfig.NECK_FORWARD,
    ): FloatArray {
        var w = qw
        var x = -qx
        var y = -qy
        var z = -qz
        val n = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-12f) return identity4()
        val inv = 1f / n
        w *= inv
        x *= inv
        y *= inv
        z *= inv
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        val r00 = 1f - 2f * (yy + zz)
        val r01 = 2f * (xy - wz)
        val r02 = 2f * (xz + wy)
        val r10 = 2f * (xy + wz)
        val r11 = 1f - 2f * (xx + zz)
        val r12 = 2f * (yz - wx)
        val r20 = 2f * (xz - wy)
        val r21 = 2f * (yz + wx)
        val r22 = 1f - 2f * (xx + yy)

        val tx: Float
        val ty: Float
        val tz: Float
        if (neckModel) {
            tx = r01 * neckY + r02 * neckZ
            ty = r11 * neckY + r12 * neckZ - neckY
            tz = r21 * neckY + r22 * neckZ - neckZ
        } else {
            tx = 0f
            ty = 0f
            tz = 0f
        }

        return floatArrayOf(
            r00, r10, r20, 0f,
            r01, r11, r21, 0f,
            r02, r12, r22, 0f,
            tx, ty, tz, 1f,
        )
    }

    /**
     * Exact closed-form Lie Group SO(3) exponential map:
     * converts angular velocity vector ω (rad/s) over dt into a unit quaternion Δq.
     */
    fun expMap(gx: Float, gy: Float, gz: Float, dt: Float): FloatArray {
        val dtx = gx * dt
        val dty = gy * dt
        val dtz = gz * dt
        val thetaSq = dtx * dtx + dty * dty + dtz * dtz
        if (thetaSq < 1e-12f) {
            val s = 0.5f - thetaSq / 48f
            return floatArrayOf(1f - thetaSq / 8f, dtx * s, dty * s, dtz * s)
        }
        val theta = kotlin.math.sqrt(thetaSq)
        val half = theta * 0.5f
        val s = kotlin.math.sin(half.toDouble()).toFloat() / theta
        return floatArrayOf(
            kotlin.math.cos(half.toDouble()).toFloat(),
            dtx * s,
            dty * s,
            dtz * s,
        )
    }

    /**
     * Exact Lie Group SO(3) integration on a unit quat: q_new = q ⊗ exp(½ ω dt).
     *
     * Inherent norm preservation (‖Δq‖ ≡ 1), eliminating scale distortion
     * and truncation errors during rapid head glances.
     */
    fun integrateQuat(
        qw: Float, qx: Float, qy: Float, qz: Float,
        gx: Float, gy: Float, gz: Float,
        dt: Float,
    ): FloatArray {
        val step = dt.coerceIn(0f, PREDICT_LEAD_MAX_S)
        val dq = expMap(gx, gy, gz, step)
        val dqw = dq[0]
        val dqx = dq[1]
        val dqy = dq[2]
        val dqz = dq[3]
        val w = qw * dqw - qx * dqx - qy * dqy - qz * dqz
        val x = qw * dqx + qx * dqw + qy * dqz - qz * dqy
        val y = qw * dqy - qx * dqz + qy * dqw + qz * dqx
        val z = qw * dqz + qx * dqy - qy * dqx + qz * dqw
        val n = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f)
        val inv = 1f / n
        return floatArrayOf(w * inv, x * inv, y * inv, z * inv)
    }

    /** Shortest-path slerp. [t]=0 keeps [a], [t]=1 reaches [b]. */
    fun slerpQuat(
        aw: Float, ax: Float, ay: Float, az: Float,
        bw: Float, bx: Float, by: Float, bz: Float,
        t: Float,
    ): FloatArray {
        var bw2 = bw
        var bx2 = bx
        var by2 = by
        var bz2 = bz
        var dot = aw * bw2 + ax * bx2 + ay * by2 + az * bz2
        if (dot < 0f) {
            bw2 = -bw2
            bx2 = -bx2
            by2 = -by2
            bz2 = -bz2
            dot = -dot
        }
        val tt = t.coerceIn(0f, 1f)
        if (dot > 0.9995f) {
            val w = aw + (bw2 - aw) * tt
            val x = ax + (bx2 - ax) * tt
            val y = ay + (by2 - ay) * tt
            val z = az + (bz2 - az) * tt
            val n = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
            if (n < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f)
            val inv = 1f / n
            return floatArrayOf(w * inv, x * inv, y * inv, z * inv)
        }
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        val sinTheta = kotlin.math.sin(theta)
        val w1 = kotlin.math.sin((1f - tt) * theta) / sinTheta
        val w2 = kotlin.math.sin(tt * theta) / sinTheta
        return floatArrayOf(
            aw * w1 + bw2 * w2,
            ax * w1 + bx2 * w2,
            ay * w1 + by2 * w2,
            az * w1 + bz2 * w2,
        )
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
        )
    }

    /** Un-project a head-space point (phone in the hand) into world space. */
    fun worldFromViewLocal(view: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val rx = x - view[12]
        val ry = y - view[13]
        val rz = z - view[14]
        return floatArrayOf(
            view[0] * rx + view[1] * ry + view[2] * rz,
            view[4] * rx + view[5] * ry + view[6] * rz,
            view[8] * rx + view[9] * ry + view[10] * rz,
        )
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                o[col * 4 + row] =
                    a[0 * 4 + row] * b[col * 4 + 0] +
                    a[1 * 4 + row] * b[col * 4 + 1] +
                    a[2 * 4 + row] * b[col * 4 + 2] +
                    a[3 * 4 + row] * b[col * 4 + 3]
            }
        }
        return o
    }
}
