package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.opennebula.air.protocol.NrealAirProtocol.Mpu6050

class ImuParserTest {
    @Test
    fun mpu6050_scales_signed_le_with_placeholder_factors() {
        val packet = ByteArray(64)
        writeI16Le(packet, 0, 16384) // +1 g X
        writeI16Le(packet, 2, 0)
        writeI16Le(packet, 4, 0)
        writeI16Le(packet, 6, 131) // +1 dps X
        writeI16Le(packet, 8, 0)
        writeI16Le(packet, 10, 0)

        val sample = ImuParser.parseMpu6050(packet)
        assertNotNull(sample)
        assertEquals(ImuParserSource.MPU6050, sample!!.source)
        assertEquals(16384, sample.accelRaw.x)
        assertEquals(Mpu6050.GRAVITY_MPS2, sample.accelMps2.x, 0.01f)
        assertEquals(1.0f, sample.gyroDps.x, 0.02f)
    }

    @Test
    fun mpu6050_reads_negative_int16() {
        val packet = ByteArray(12)
        writeI16Le(packet, 0, -16384)
        val sample = ImuParser.parseMpu6050(packet)!!
        assertEquals(-16384, sample.accelRaw.x)
        assertEquals(-Mpu6050.GRAVITY_MPS2, sample.accelMps2.x, 0.01f)
    }

    @Test
    fun i24_le_sign_extends_negative_noise() {
        val buf = ByteArray(3)
        writeI24Le(buf, 0, -1)
        assertEquals(-1, buf.readI24Le(0))
        writeI24Le(buf, 0, -256)
        assertEquals(-256, buf.readI24Le(0))
        writeI24Le(buf, 0, -8_388_608)
        assertEquals(-8_388_608, buf.readI24Le(0))
        writeI24Le(buf, 0, 8_388_607)
        assertEquals(8_388_607, buf.readI24Le(0))
        // 0xFFFFFF must be -1, not 16_777_215 (unsigned trap).
        buf[0] = 0xFF.toByte()
        buf[1] = 0xFF.toByte()
        buf[2] = 0xFF.toByte()
        assertEquals(-1, buf.readI24Le(0))
        assertEquals(false, buf.readI24Le(0) == 16_777_215)
    }

    @Test
    fun air_packed_gyro_matches_ar_drivers_radians() {
        val packet = ByteArray(64)
        packet[0] = 0x01
        packet[1] = 0x02
        writeI16Le(packet, 0x0C, 1)
        writeI32Le(packet, 0x0E, 1)
        writeI24Le(packet, 0x12, 180)
        writeI16Le(packet, 0x1B, 1)
        writeI32Le(packet, 0x1D, 1)
        val sample = ImuParser.parse(packet)!!
        assertEquals(-180f, sample.gyroDps.x, 1e-3f)
        assertEquals(0f, sample.gyroDps.y, 1e-3f)
        assertEquals(0f, sample.gyroDps.z, 1e-3f)
        assertEquals((-Math.PI).toFloat(), sample.gyroRadS.x, 1e-4f)
        assertEquals(ImuParser.dpsToRadS(-180f), sample.gyroRadS.x, 1e-5f)
    }

    @Test
    fun air_packed_negative_i24_gyro_is_small_not_unsigned() {
        val packet = ByteArray(64)
        packet[0] = 0x01
        packet[1] = 0x02
        writeI16Le(packet, 0x0C, 1)
        writeI32Le(packet, 0x0E, 1)
        writeI24Le(packet, 0x12, -1)
        writeI16Le(packet, 0x1B, 1)
        writeI32Le(packet, 0x1D, 1)
        val sample = ImuParser.parse(packet)!!
        // X is negated after remap: raw -1 dps → +1 dps, not ~±16.7e6.
        assertEquals(1f, sample.gyroDps.x, 1e-3f)
        assertEquals(true, kotlin.math.abs(sample.gyroDps.x) < 2f)
    }

    @Test
    fun air_packed_timestamp_is_packet_u64_nanoseconds() {
        val packet = ByteArray(64)
        packet[0] = 0x01
        packet[1] = 0x02
        writeI64Le(packet, 0x04, 1_000_000_000L)
        writeI16Le(packet, 0x0C, 1)
        writeI32Le(packet, 0x0E, 1)
        writeI16Le(packet, 0x1B, 1)
        writeI32Le(packet, 0x1D, 1)
        val sample = ImuParser.parse(packet)!!
        assertEquals(1_000_000_000L, sample.timestampNs)
    }

    @Test
    fun air_packed_accel_matches_ar_drivers_remap() {
        val packet = ByteArray(64)
        packet[0] = 0x01
        packet[1] = 0x02
        writeI16Le(packet, 0x0C, 1)
        writeI32Le(packet, 0x0E, 1)
        writeI16Le(packet, 0x1B, 1)
        writeI32Le(packet, 0x1D, 1)
        writeI24Le(packet, 0x21, 1) // ax → −1g X after remap
        writeI24Le(packet, 0x24, 2) // ay → +2g Z
        writeI24Le(packet, 0x27, 3) // az → +3g Y
        val sample = ImuParser.parse(packet)!!
        assertEquals(-Mpu6050.GRAVITY_MPS2, sample.accelMps2.x, 0.02f)
        assertEquals(3f * Mpu6050.GRAVITY_MPS2, sample.accelMps2.y, 0.02f)
        assertEquals(2f * Mpu6050.GRAVITY_MPS2, sample.accelMps2.z, 0.02f)
    }

    @Test
    fun air_packed_gyro_yz_swap_matches_ar_drivers() {
        val packet = ByteArray(64)
        packet[0] = 0x01
        packet[1] = 0x02
        writeI16Le(packet, 0x0C, 1)
        writeI32Le(packet, 0x0E, 1)
        writeI24Le(packet, 0x15, 90) // gy → Z
        writeI24Le(packet, 0x18, 45) // gz → Y
        writeI16Le(packet, 0x1B, 1)
        writeI32Le(packet, 0x1D, 1)
        val sample = ImuParser.parse(packet)!!
        assertEquals(0f, sample.gyroDps.x, 1e-3f)
        assertEquals(45f, sample.gyroDps.y, 1e-3f)
        assertEquals(90f, sample.gyroDps.z, 1e-3f)
    }

    @Test
    fun control_frame_is_ignored() {
        val packet = ByteArray(64) { 0 }
        packet[0] = 0xAA.toByte()
        assertNull(ImuParser.parse(packet))
    }

    private fun writeI16Le(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value and 0xFF).toByte()
        packet[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeI24Le(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value and 0xFF).toByte()
        packet[offset + 1] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun writeI32Le(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value and 0xFF).toByte()
        packet[offset + 1] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 16) and 0xFF).toByte()
        packet[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeI64Le(packet: ByteArray, offset: Int, value: Long) {
        writeI32Le(packet, offset, (value and 0xFFFFFFFFL).toInt())
        writeI32Le(packet, offset + 4, ((value ushr 32) and 0xFFFFFFFFL).toInt())
    }
}
