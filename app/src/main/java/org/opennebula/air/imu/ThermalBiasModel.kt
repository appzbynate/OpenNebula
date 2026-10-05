package org.opennebula.air.imu

import kotlin.math.abs

/**
 * Online MEMS Thermal Drift Estimator for Nreal Air (InvenSense ICM-20602).
 *
 * Physical silicon gyros exhibit thermal expansion drift as the Micro-OLED displays
 * and face contact warm the glasses from room temperature (~22°C) to operating temp (~42°C):
 *   b(T) = b0 + KT * (T - T0)
 *
 * This estimator tracks baseline bias b0 and temperature sensitivity slope KT (dps/°C)
 * during confirmed ZUPT stillness, continuously subtracting thermal drift even during motion.
 */
class ThermalBiasModel(
    val referenceTempC: Float = NOMINAL_TEMP_C,
) {
    var bias0X = 0f
        private set
    var bias0Y = 0f
        private set
    var bias0Z = 0f
        private set

    var slopeX = 0f
        private set
    var slopeY = 0f
        private set
    var slopeZ = 0f
        private set

    var lastTempC: Float = referenceTempC
        private set

    fun reset() {
        bias0X = 0f
        bias0Y = 0f
        bias0Z = 0f
        slopeX = 0f
        slopeY = 0f
        slopeZ = 0f
        lastTempC = referenceTempC
    }

    fun setInitialBias(bx: Float, by: Float, bz: Float, tempC: Float = referenceTempC) {
        lastTempC = tempC
        val dt = tempC - referenceTempC
        // Set b0 such that b0 + slope * dt == bx
        bias0X = bx - slopeX * dt
        bias0Y = by - slopeY * dt
        bias0Z = bz - slopeZ * dt
    }

    /**
     * Updates thermal parameters during confirmed stillness (ZUPT active).
     * [gx],[gy],[gz] are measured raw gyro rates (°/s).
     */
    fun updateStationary(
        gx: Float,
        gy: Float,
        gz: Float,
        tempC: Float,
        alpha: Float = 0.001f,
    ) {
        lastTempC = tempC
        val dt = tempC - referenceTempC

        // Axis X
        val predX = bias0X + slopeX * dt
        val errX = gx - predX
        bias0X += alpha * errX
        if (abs(dt) > 0.5f) {
            val slopeAlpha = (alpha * dt / (dt * dt + 1.0f)).coerceIn(-MAX_SLOPE_STEP, MAX_SLOPE_STEP)
            slopeX = (slopeX + slopeAlpha * errX).coerceIn(-MAX_PHYSICAL_SLOPE_DPS_PER_C, MAX_PHYSICAL_SLOPE_DPS_PER_C)
        }

        // Axis Y
        val predY = bias0Y + slopeY * dt
        val errY = gy - predY
        bias0Y += alpha * errY
        if (abs(dt) > 0.5f) {
            val slopeAlpha = (alpha * dt / (dt * dt + 1.0f)).coerceIn(-MAX_SLOPE_STEP, MAX_SLOPE_STEP)
            slopeY = (slopeY + slopeAlpha * errY).coerceIn(-MAX_PHYSICAL_SLOPE_DPS_PER_C, MAX_PHYSICAL_SLOPE_DPS_PER_C)
        }

        // Axis Z
        val predZ = bias0Z + slopeZ * dt
        val errZ = gz - predZ
        bias0Z += alpha * errZ
        if (abs(dt) > 0.5f) {
            val slopeAlpha = (alpha * dt / (dt * dt + 1.0f)).coerceIn(-MAX_SLOPE_STEP, MAX_SLOPE_STEP)
            slopeZ = (slopeZ + slopeAlpha * errZ).coerceIn(-MAX_PHYSICAL_SLOPE_DPS_PER_C, MAX_PHYSICAL_SLOPE_DPS_PER_C)
        }
    }

    /**
     * Computes estimated gyro bias (°/s) at [tempC].
     */
    fun getBias(tempC: Float = lastTempC): FloatArray {
        val dt = tempC - referenceTempC
        return floatArrayOf(
            bias0X + slopeX * dt,
            bias0Y + slopeY * dt,
            bias0Z + slopeZ * dt,
        )
    }

    fun toLogLine(tempC: Float = lastTempC): String {
        return "temp=${"%.1f".format(tempC)}C slope=${"%.3f".format(slopeX)},${"%.3f".format(slopeY)},${"%.3f".format(slopeZ)}"
    }

    companion object {
        const val NOMINAL_TEMP_C = 25.0f
        /** ICM-20602 temperature sensitivity: 326.8 LSB/°C with 0 = 25°C. */
        const val ICM20602_LSB_PER_C = 326.8f
        /** Upper bound on physical MEMS silicon thermal sensitivity: ±0.15 °/s per °C. */
        const val MAX_PHYSICAL_SLOPE_DPS_PER_C = 0.15f
        const val MAX_SLOPE_STEP = 0.005f

        fun rawToCelsius(raw: Int?): Float {
            if (raw == null) return NOMINAL_TEMP_C
            return (raw.toFloat() / ICM20602_LSB_PER_C) + NOMINAL_TEMP_C
        }
    }
}
