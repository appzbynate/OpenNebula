package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThermalBiasModelTest {

    @Test
    fun raw_to_celsius_conversion() {
        assertEquals(25.0f, ThermalBiasModel.rawToCelsius(null), 1e-4f)
        assertEquals(25.0f, ThermalBiasModel.rawToCelsius(0), 1e-4f)
        // 3268 LSB / 326.8 = 10°C delta -> 35°C
        assertEquals(35.0f, ThermalBiasModel.rawToCelsius(3268), 0.1f)
    }

    @Test
    fun initial_bias_retained_at_nominal_temperature() {
        val model = ThermalBiasModel()
        model.setInitialBias(0.12f, -0.24f, 0.05f, 25.0f)
        val bias = model.getBias(25.0f)
        assertEquals(0.12f, bias[0], 1e-4f)
        assertEquals(-0.24f, bias[1], 1e-4f)
        assertEquals(0.05f, bias[2], 1e-4f)
    }

    @Test
    fun online_learning_estimates_thermal_slope() {
        val model = ThermalBiasModel()
        model.setInitialBias(0.10f, 0.0f, 0.0f, 25.0f)

        // Ground truth physical slope: +0.03 dps/°C on X
        val trueSlope = 0.03f
        val trueBase = 0.10f

        // Simulate glasses warming up from 25°C to 40°C over 15,000 samples
        val samples = 15000
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples.toFloat()
            val tempC = 25.0f + 15.0f * progress
            val currentBiasX = trueBase + trueSlope * (tempC - 25.0f)
            model.updateStationary(currentBiasX, 0f, 0f, tempC, alpha = 0.002f)
        }

        // Verify slope converged close to ground truth
        assertEquals(trueSlope, model.slopeX, 0.008f)
        // Verify bias predicted at 40°C matches true physical bias
        val predictedBias = model.getBias(40.0f)
        val expectedAt40 = trueBase + trueSlope * 15.0f
        assertEquals(expectedAt40, predictedBias[0], 0.03f)
    }
}
