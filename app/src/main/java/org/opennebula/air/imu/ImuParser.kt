package org.opennebula.air.imu

import org.opennebula.air.protocol.NrealAirProtocol.AirReport
import org.opennebula.air.protocol.NrealAirProtocol.Mpu6050
import org.opennebula.air.protocol.NrealAirProtocol.Mpu6050Layout
import kotlin.math.atan2
import kotlin.math.sqrt

object ImuParser {
    /** Locked to ar-drivers-rs: gyro/accel both (−x, +z, +y). */
    const val AIR_AXIS_LABEL = "ar(-x,+z,+y)"

    fun airAxisModeLabel(): String = AIR_AXIS_LABEL

    /** Axis experiments remapped gyro only and broke fusion — always reset. */
    fun cycleAirAxisMode(): String = AIR_AXIS_LABEL

    fun resetAirAxisMode() = Unit

    fun isAirPackedReport(packet: ByteArray): Boolean {
        return packet.size >= 2 &&
            packet[0] == AirReport.HEADER_0 &&
            packet[1] == AirReport.HEADER_1
    }

    fun isImuControlFrame(packet: ByteArray): Boolean {
        return packet.isNotEmpty() && packet[0] == 0xAA.toByte()
    }

    fun parseImuControlReply(packet: ByteArray): Pair<Int, ByteArray>? {
        if (packet.size < 8 || packet[0] != 0xAA.toByte()) return null
        val length = packet.readU16Le(5)
        val cmd = packet[7].toInt() and 0xFF
        val dataLen = (length - 3).coerceAtLeast(0)
        val end = (8 + dataLen).coerceAtMost(packet.size)
        return cmd to packet.copyOfRange(8, end)
    }

    /**
     * Auto-selects the packed Air report when the `01 02` header is present,
     * otherwise the MPU6050 16-bit LE placeholder layout.
     */
    fun parse(packet: ByteArray): ImuSample? {
        if (isImuControlFrame(packet)) return null
        return if (isAirPackedReport(packet)) parseAirPacked(packet) else parseMpu6050(packet)
    }

    /**
     * Milestone 1 parser: signed int16 LE accel/gyro with MPU6050 ±2g / ±250 dps
     * conversion, then placeholder [Mpu6050.ACCEL_SCALE] / [Mpu6050.GYRO_SCALE].
     */
    fun parseMpu6050(packet: ByteArray): ImuSample? {
        if (packet.size < Mpu6050Layout.MIN_SIZE) return null
        val ax = packet.readI16Le(Mpu6050Layout.ACCEL_X)
        val ay = packet.readI16Le(Mpu6050Layout.ACCEL_X + 2)
        val az = packet.readI16Le(Mpu6050Layout.ACCEL_X + 4)
        val gx = packet.readI16Le(Mpu6050Layout.GYRO_X)
        val gy = packet.readI16Le(Mpu6050Layout.GYRO_X + 2)
        val gz = packet.readI16Le(Mpu6050Layout.GYRO_X + 4)
        val accel = scaleMpuAccel(ax, ay, az)
        val gyro = scaleMpuGyro(gx, gy, gz)
        val tilt = tiltFromAccel(accel)
        return ImuSample(
            source = ImuParserSource.MPU6050,
            timestampNs = 0L,
            temperatureRaw = null,
            accelRaw = Vec3i(ax, ay, az),
            gyroRaw = Vec3i(gx, gy, gz),
            accelMps2 = accel,
            gyroDps = gyro,
            pitchDeg = tilt.first,
            rollDeg = tilt.second,
        )
    }

    /**
     * Live Nreal Air / ICM-20602 64-byte report used once the IMU stream is enabled.
     * Timestamp is the packet u64 at 0x04 in nanoseconds (ar-drivers divides by
     * 1000 only to publish microseconds). Gyro is ar-drivers
     * `(raw * mul / div).to_radians()` — not MPU `2000/32768`, which is for
     * int16 ±2000 dps sensors. Air reports i24 plus per-packet mul/div.
     */
    fun parseAirPacked(packet: ByteArray): ImuSample? {
        if (packet.size < AirReport.MIN_SIZE || !isAirPackedReport(packet)) return null
        val temperature = packet.readI16Le(AirReport.TEMPERATURE)
        val timestampNs = packet.readI64Le(AirReport.TIMESTAMP_NS)
        val gyroMul = packet.readU16Le(AirReport.GYRO_MUL).toFloat()
        val gyroDiv = packet.readU32Le(AirReport.GYRO_DIV).toFloat().takeIf { it != 0f } ?: return null
        val gx = packet.readI24Le(AirReport.GYRO_X)
        val gy = packet.readI24Le(AirReport.GYRO_X + 3)
        val gz = packet.readI24Le(AirReport.GYRO_X + 6)
        val accelMul = packet.readU16Le(AirReport.ACCEL_MUL).toFloat()
        val accelDiv = packet.readU32Le(AirReport.ACCEL_DIV).toFloat().takeIf { it != 0f } ?: return null
        val ax = packet.readI24Le(AirReport.ACCEL_X)
        val ay = packet.readI24Le(AirReport.ACCEL_X + 3)
        val az = packet.readI24Le(AirReport.ACCEL_X + 6)

        // ar-drivers-rs: (−x, z, y) after mul/div. Same remap for gyro and accel.
        val gyro = remapAirAxes(
            airGyroDps(gx, gyroMul, gyroDiv, negate = false),
            airGyroDps(gy, gyroMul, gyroDiv, negate = false),
            airGyroDps(gz, gyroMul, gyroDiv, negate = false),
        )
        val accelG = remapAirAxes(
            ax * accelMul / accelDiv,
            ay * accelMul / accelDiv,
            az * accelMul / accelDiv,
        )
        val accel = Vec3f(
            accelG.x * Mpu6050.GRAVITY_MPS2 * Mpu6050.ACCEL_SCALE,
            accelG.y * Mpu6050.GRAVITY_MPS2 * Mpu6050.ACCEL_SCALE,
            accelG.z * Mpu6050.GRAVITY_MPS2 * Mpu6050.ACCEL_SCALE,
        )
        val tilt = tiltFromAccel(accel)
        return ImuSample(
            source = ImuParserSource.AIR_PACKED,
            timestampNs = timestampNs,
            temperatureRaw = temperature,
            accelRaw = Vec3i(ax, ay, az),
            gyroRaw = Vec3i(gx, gy, gz),
            accelMps2 = accel,
            gyroDps = gyro,
            pitchDeg = tilt.first,
            rollDeg = tilt.second,
        )
    }

    /** `raw * multiplier / divisor` degrees/sec (ar-drivers, before axis remap). */
    fun airGyroDps(raw: Int, mul: Float, div: Float, negate: Boolean = false): Float {
        val dps = raw * mul / div
        return if (negate) -dps else dps
    }

    fun dpsToRadS(dps: Float): Float = Math.toRadians(dps.toDouble()).toFloat()

    /** ar-drivers RUB: (−sensorX, sensorZ, sensorY). */
    private fun remapAirAxes(sx: Float, sy: Float, sz: Float): Vec3f {
        return Vec3f(-sx, sz, sy)
    }

    private fun scaleMpuAccel(ax: Int, ay: Int, az: Int): Vec3f {
        val s = Mpu6050.GRAVITY_MPS2 / Mpu6050.ACCEL_LSB_PER_G * Mpu6050.ACCEL_SCALE
        return Vec3f(ax * s, ay * s, az * s)
    }

    private fun scaleMpuGyro(gx: Int, gy: Int, gz: Int): Vec3f {
        val s = Mpu6050.GYRO_SCALE / Mpu6050.GYRO_LSB_PER_DPS
        return Vec3f(gx * s, gy * s, gz * s)
    }

    fun tiltFromAccel(accel: Vec3f): Pair<Float, Float> {
        val pitch = Math.toDegrees(
            atan2(accel.x.toDouble(), sqrt((accel.y * accel.y + accel.z * accel.z).toDouble())),
        ).toFloat()
        val roll = Math.toDegrees(atan2(accel.y.toDouble(), accel.z.toDouble())).toFloat()
        return pitch to roll
    }
}

fun ImuSample.toLogLine(): String {
    val ts = if (timestampNs == 0L) "-" else timestampNs.toString()
    return "src=$source ts=$ts temp=$temperatureRaw " +
        "acc_raw=${accelRaw} g_raw=${gyroRaw} " +
        "acc_mps2=$accelMps2 gyro_dps=$gyroDps " +
        "pitch=${"%.1f".format(pitchDeg)} roll=${"%.1f".format(rollDeg)}"
}