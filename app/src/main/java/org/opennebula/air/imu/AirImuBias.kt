package org.opennebula.air.imu

import kotlin.math.sqrt

data class Quatf(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    companion object {
        val IDENTITY = Quatf(1f, 0f, 0f, 0f)
    }
}

/**
 * Factory IMU zeros from the glasses JSON (`IMU.device_1`).
 *
 * Matches ar-drivers-rs exactly: after the X-neg / Y↔Z remap, apply only
 * `gyro_bias` / `accel_bias` with signs (−, +, +). Scale / misalignment are
 * parsed for logs but **not** applied — ar-drivers ignores them in the sample
 * path, and earlier mixed-sign attempts made tracking worse.
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
            accelBiasMps2.x == 0f && accelBiasMps2.y == 0f && accelBiasMps2.z == 0f

    fun apply(sample: ImuSample): ImuSample {
        if (isZero) return sample
        val gx = ImuParser.dpsToRadS(sample.gyroDps.x)
        val gy = ImuParser.dpsToRadS(sample.gyroDps.y)
        val gz = ImuParser.dpsToRadS(sample.gyroDps.z)
        val gyro = Vec3f(
            Math.toDegrees((gx - gyroBiasRad.x).toDouble()).toFloat(),
            Math.toDegrees((gy + gyroBiasRad.y).toDouble()).toFloat(),
            Math.toDegrees((gz + gyroBiasRad.z).toDouble()).toFloat(),
        )
        val accel = Vec3f(
            sample.accelMps2.x - accelBiasMps2.x,
            sample.accelMps2.y + accelBiasMps2.y,
            sample.accelMps2.z + accelBiasMps2.z,
        )
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
            "scale_gyro=$scaleGyro(unused) scale_accel=$scaleAccel(unused)"
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

