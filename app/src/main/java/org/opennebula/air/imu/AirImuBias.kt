package org.opennebula.air.imu

import kotlin.math.sqrt

data class Quatf(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val isIdentity: Boolean
        get() = w == 1f && x == 0f && y == 0f && z == 0f

    fun rotateVec(vx: Float, vy: Float, vz: Float): Vec3f {
        val cx = y * vz - z * vy
        val cy = z * vx - x * vz
        val cz = x * vy - y * vx
        return Vec3f(
            vx + 2f * w * cx + 2f * (y * cz - z * cy),
            vy + 2f * w * cy + 2f * (z * cx - x * cz),
            vz + 2f * w * cz + 2f * (x * cy - y * cx),
        )
    }

    companion object {
        val IDENTITY = Quatf(1f, 0f, 0f, 0f)
    }
}

/**
 * Factory IMU calibration from glasses EEPROM JSON (`IMU.device_1`).
 *
 * Implements reference Monado OpenXR pipeline (`xreal_air_hmd.c`):
 * 1. Rotate raw sensor gyro vector by `accel_q_gyro` die misalignment quaternion.
 * 2. Remap to pre-biased frame (RUB), subtract factory bias, and multiply by `scale_gyro` / `scale_accel`.
 * 3. Convert to body OpenGL coordinate system (-X, +Z, +Y).
 */
data class AirImuBias(
    val gyroBiasRad: Vec3f,
    val accelBiasMps2: Vec3f,
    val scaleGyro: Vec3f = Vec3f(1f, 1f, 1f),
    val scaleAccel: Vec3f = Vec3f(1f, 1f, 1f),
    val accelQGyro: Quatf = Quatf.IDENTITY,
) {
    val isZero: Boolean
        get() = gyroBiasRad.x == 0f && gyroBiasRad.y == 0f && gyroBiasRad.z == 0f &&
            accelBiasMps2.x == 0f && accelBiasMps2.y == 0f && accelBiasMps2.z == 0f &&
            scaleGyro.x == 1f && scaleGyro.y == 1f && scaleGyro.z == 1f &&
            scaleAccel.x == 1f && scaleAccel.y == 1f && scaleAccel.z == 1f &&
            accelQGyro.isIdentity

    fun apply(sample: ImuSample): ImuSample {
        if (isZero) return sample

        // Reconstruct raw sensor frame from remapped sample (RUB: -X, Z, Y).
        val rawGx = ImuParser.dpsToRadS(-sample.gyroDps.x)
        val rawGy = ImuParser.dpsToRadS(sample.gyroDps.z)
        val rawGz = ImuParser.dpsToRadS(sample.gyroDps.y)

        // 1. Rotate gyro by accel_q_gyro die misalignment quaternion
        val rotG = accelQGyro.rotateVec(rawGx, rawGy, rawGz)

        // 2. Monado pre_biased (-x, -z, -y) - bias * scale -> post_biased (x, -y, -z)
        val outRadX = (-rotG.x - gyroBiasRad.x) * scaleGyro.x
        val outRadY = (rotG.z + gyroBiasRad.y) * scaleGyro.y
        val outRadZ = (rotG.y + gyroBiasRad.z) * scaleGyro.z

        val gyro = Vec3f(
            Math.toDegrees(outRadX.toDouble()).toFloat(),
            Math.toDegrees(outRadY.toDouble()).toFloat(),
            Math.toDegrees(outRadZ.toDouble()).toFloat(),
        )

        // Accelerometer calibration
        val rawAx = -sample.accelMps2.x
        val rawAy = sample.accelMps2.z
        val rawAz = sample.accelMps2.y

        val outAx = (-rawAx - accelBiasMps2.x) * scaleAccel.x
        val outAy = (rawAz + accelBiasMps2.y) * scaleAccel.y
        val outAz = (rawAy + accelBiasMps2.z) * scaleAccel.z

        val accel = Vec3f(outAx, outAy, outAz)
        val tilt = ImuParser.tiltFromAccel(accel)

        return sample.copy(
            accelMps2 = accel,
            gyroDps = gyro,
            pitchDeg = tilt.first,
            rollDeg = tilt.second,
        )
    }

    fun toLogLine(): String {
        return "gyro_bias_rad=$gyroBiasRad accel_bias=$accelBiasMps2 " +
            "scale_gyro=$scaleGyro scale_accel=$scaleAccel " +
            "accel_q_gyro=(w=${"%.4f".format(accelQGyro.w)},x=${"%.4f".format(accelQGyro.x)},y=${"%.4f".format(accelQGyro.y)},z=${"%.4f".format(accelQGyro.z)})"
    }

    companion object {
        val ZERO = AirImuBias(Vec3f(0f, 0f, 0f), Vec3f(0f, 0f, 0f))

        fun parseConfigJson(json: String): AirImuBias {
            val gyro = parseVec3(json, "gyro_bias") ?: Vec3f(0f, 0f, 0f)
            val accel = parseVec3(json, "accel_bias") ?: Vec3f(0f, 0f, 0f)
            val scaleGyro = parseVec3(json, "scale_gyro") ?: Vec3f(1f, 1f, 1f)
            val scaleAccel = parseVec3(json, "scale_accel") ?: Vec3f(1f, 1f, 1f)
            val accelQGyro = parseQuat(json, "accel_q_gyro") ?: Quatf.IDENTITY
            return AirImuBias(gyro, accel, scaleGyro, scaleAccel, accelQGyro)
        }

        private fun parseVec3(json: String, key: String): Vec3f? {
            val re = Regex(
                """"${Regex.escape(key)}"\s*:\s*\[\s*([^\],]+)\s*,\s*([^\],]+)\s*,\s*([^\],]+)\s*\]""",
            )
            val m = re.find(json) ?: return null
            return runCatching {
                Vec3f(
                    m.groupValues[1].trim().toFloat(),
                    m.groupValues[2].trim().toFloat(),
                    m.groupValues[3].trim().toFloat(),
                )
            }.getOrNull()
        }

        private fun parseQuat(json: String, key: String): Quatf? {
            val re = Regex(
                """"${Regex.escape(key)}"\s*:\s*\[\s*([^\],]+)\s*,\s*([^\],]+)\s*,\s*([^\],]+)\s*,\s*([^\],]+)\s*\]""",
            )
            val m = re.find(json) ?: return null
            return runCatching {
                val x = m.groupValues[1].trim().toFloat()
                val y = m.groupValues[2].trim().toFloat()
                val z = m.groupValues[3].trim().toFloat()
                val w = m.groupValues[4].trim().toFloat()
                normalize(Quatf(w, x, y, z))
            }.getOrNull()
        }

        private fun normalize(q: Quatf): Quatf {
            val n = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
            if (n < 1e-12f) return Quatf.IDENTITY
            val inv = 1f / n
            return Quatf(q.w * inv, q.x * inv, q.y * inv, q.z * inv)
        }
    }
}

