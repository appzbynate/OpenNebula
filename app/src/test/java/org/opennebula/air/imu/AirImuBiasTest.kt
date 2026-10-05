package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.opennebula.air.protocol.NrealAirProtocol

class AirImuBiasTest {
    @Test
    fun json_reads_device_bias_vectors() {
        val json = """
            {"IMU":{"device_1":{
              "gyro_bias":[0.1,-0.2,0.05],
              "accel_bias":[0.3,0.0,-0.1],
              "scale_gyro":[1.01,0.98,1.02],
              "scale_accel":[0.99,1.00,1.01],
              "accel_q_gyro":[0.0,0.0,0.0,1.0]
            }}}
        """.trimIndent()
        val bias = AirImuBias.parseConfigJson(json)
        assertEquals(0.1f, bias.gyroBiasRad.x, 1e-5f)
        assertEquals(-0.2f, bias.gyroBiasRad.y, 1e-5f)
        assertEquals(0.05f, bias.gyroBiasRad.z, 1e-5f)
        assertEquals(0.3f, bias.accelBiasMps2.x, 1e-5f)
        assertEquals(0.0f, bias.accelBiasMps2.y, 1e-5f)
        assertEquals(-0.1f, bias.accelBiasMps2.z, 1e-5f)
        assertEquals(1.01f, bias.scaleGyro.x, 1e-5f)
        assertEquals(0.98f, bias.scaleGyro.y, 1e-5f)
        assertEquals(1.02f, bias.scaleGyro.z, 1e-5f)
        assertEquals(0.99f, bias.scaleAccel.x, 1e-5f)
        assertEquals(1.00f, bias.scaleAccel.y, 1e-5f)
        assertEquals(1.01f, bias.scaleAccel.z, 1e-5f)
        assertEquals(1f, bias.accelQGyro.w, 1e-5f)
    }

    @Test
    fun apply_matches_ar_drivers_signs() {
        val bias = AirImuBias(Vec3f(0.1f, 0.2f, 0.3f), Vec3f(1f, 2f, 3f))
        val sample = ImuSample(
            source = ImuParserSource.AIR_PACKED,
            timestampNs = 1L,
            temperatureRaw = null,
            accelRaw = Vec3i(0, 0, 0),
            gyroRaw = Vec3i(0, 0, 0),
            accelMps2 = Vec3f(0f, 0f, 0f),
            gyroDps = Vec3f(0f, 0f, 0f),
            pitchDeg = 0f,
            rollDeg = 0f,
        )
        val out = bias.apply(sample)
        assertEquals(-Math.toDegrees(0.1).toFloat(), out.gyroDps.x, 1e-3f)
        assertEquals(Math.toDegrees(0.2).toFloat(), out.gyroDps.y, 1e-3f)
        assertEquals(Math.toDegrees(0.3).toFloat(), out.gyroDps.z, 1e-3f)
        assertEquals(-1f, out.accelMps2.x, 1e-5f)
        assertEquals(2f, out.accelMps2.y, 1e-5f)
        assertEquals(3f, out.accelMps2.z, 1e-5f)
    }

    @Test
    fun apply_applies_scale_and_quaternion_rotation() {
        val bias = AirImuBias(
            gyroBiasRad = Vec3f(0f, 0f, 0f),
            accelBiasMps2 = Vec3f(0f, 0f, 0f),
            scaleGyro = Vec3f(1.1f, 1.2f, 0.9f),
            scaleAccel = Vec3f(1.05f, 0.95f, 1.0f),
            accelQGyro = Quatf.IDENTITY,
        )
        val sample = ImuSample(
            source = ImuParserSource.AIR_PACKED,
            timestampNs = 1L,
            temperatureRaw = null,
            accelRaw = Vec3i(0, 0, 0),
            gyroRaw = Vec3i(0, 0, 0),
            accelMps2 = Vec3f(1f, 2f, 3f),
            gyroDps = Vec3f(10f, 20f, 30f),
            pitchDeg = 0f,
            rollDeg = 0f,
        )
        assertEquals(false, bias.isZero)
        val out = bias.apply(sample)
        assertEquals(11.0f, out.gyroDps.x, 1e-3f)
        assertEquals(24.0f, out.gyroDps.y, 1e-3f)
        assertEquals(27.0f, out.gyroDps.z, 1e-3f)
        assertEquals(1.05f, out.accelMps2.x, 1e-4f)
        assertEquals(1.90f, out.accelMps2.y, 1e-4f)
        assertEquals(3.0f, out.accelMps2.z, 1e-4f)
    }

    @Test
    fun isZero_is_true_only_for_defaults() {
        assertEquals(true, AirImuBias.ZERO.isZero)
    }

    @Test
    fun control_reply_extracts_cmd_and_data() {
        val frame = NrealAirProtocol.imuControlFrame(0x14, byteArrayOf(0x10, 0x27, 0x00, 0x00))
        val reply = ImuParser.parseImuControlReply(frame)
        assertNotNull(reply)
        assertEquals(0x14, reply!!.first)
        assertEquals(4, reply.second.size)
        assertEquals(0x10.toByte(), reply.second[0])
        assertEquals(0x27.toByte(), reply.second[1])
    }
}

class MadgwickResidualBiasTest {
    @Test
    fun residual_bias_filter_is_off() {
        val filter = MadgwickImu()
        val sample = ImuSample(
            source = ImuParserSource.AIR_PACKED,
            timestampNs = 0L,
            temperatureRaw = null,
            accelRaw = Vec3i(0, 0, 0),
            gyroRaw = Vec3i(0, 0, 0),
            accelMps2 = Vec3f(0f, 0f, 9.81f),
            gyroDps = Vec3f(1.0f, 0f, 0f),
            pitchDeg = 0f,
            rollDeg = 0f,
        )
        repeat(200) { i ->
            filter.update(sample.copy(timestampNs = (i + 1) * 1_000_000L))
        }
        assertEquals(0f, filter.residualBiasDps().x, 1e-5f)
    }
}
