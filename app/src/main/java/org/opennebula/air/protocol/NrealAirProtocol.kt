package org.opennebula.air.protocol

/**
 * First-gen Nreal Air USB identity and IMU report layout.
 *
 * HID map (public drivers / Monado / ar-drivers-rs):
 * - Interface 3: IMU (interrupt IN ~0x84, OUT ~0x05)
 * - Interface 4: MCU / display-mode control (not claimed in Milestone 1)
 */
object NrealAirProtocol {
    const val VENDOR_ID = 0x3318
    const val PRODUCT_ID = 0x0424
    const val PACKET_SIZE = 64

    /** Prefer the IMU HID interface; fall back to 4, then any interrupt-IN interface. */
    val PREFERRED_INTERFACE_IDS: IntArray = intArrayOf(3, 4)

    const val USB_TRANSFER_TIMEOUT_MS = 250
    const val ACTIVATION_TIMEOUT_MS = 1_000
    const val IMU_COMMAND_TIMEOUT_MS = 1_000
    const val IMU_CONFIG_MAX_BYTES = 256 * 1024

    const val IMU_CMD_GET_CONFIG_LEN = 0x14
    const val IMU_CMD_GET_CONFIG_CHUNK = 0x15
    const val IMU_CMD_ENABLE_STREAM = 0x19

    /**
     * 64-byte IMU HID control frame that enables the sensor stream.
     *
     * Layout (ar-drivers-rs / Monado, interface 3):
     * `AA` + CRC32-IEEE LE of bytes `[5, 5+length)` + length u16 LE + cmd + data, padded to 64.
     * Enable stream = cmd `0x19`, data `0x01`.
     */
    val ACTIVATION_PAYLOAD: ByteArray = imuControlFrame(IMU_CMD_ENABLE_STREAM, byteArrayOf(0x01))

    fun imuControlFrame(cmdId: Int, data: ByteArray): ByteArray {
        require(data.size <= 56) { "IMU HID data max 56 bytes" }
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = 0xAA.toByte()
        val length = data.size + 3
        packet[5] = (length and 0xFF).toByte()
        packet[6] = ((length shr 8) and 0xFF).toByte()
        packet[7] = (cmdId and 0xFF).toByte()
        data.copyInto(packet, destinationOffset = 8)
        val crc = crc32Ieee(packet, 5, length)
        packet[1] = (crc and 0xFF).toByte()
        packet[2] = ((crc shr 8) and 0xFF).toByte()
        packet[3] = ((crc shr 16) and 0xFF).toByte()
        packet[4] = ((crc shr 24) and 0xFF).toByte()
        return packet
    }

    /** ZIP / PNG CRC-32 (called crc32_adler in ar-drivers-rs). */
    fun crc32Ieee(data: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor CRC32_POLY else crc ushr 1
            }
        }
        return crc.inv()
    }

    private const val CRC32_POLY = -0x12477ce0 // 0xEDB88320

    object AirReport {
        const val HEADER_0: Byte = 0x01
        const val HEADER_1: Byte = 0x02
        const val TEMPERATURE = 0x02
        const val TIMESTAMP_NS = 0x04
        const val GYRO_MUL = 0x0C
        const val GYRO_DIV = 0x0E
        const val GYRO_X = 0x12
        const val ACCEL_MUL = 0x1B
        const val ACCEL_DIV = 0x1D
        const val ACCEL_X = 0x21
        const val MIN_SIZE = 0x2A
    }

    /**
     * Placeholder MPU6050-style 16-bit LE layout. Adjust these offsets after you
     * inspect [org.opennebula.air.logging.LogTags.IMU_RAW] hex dumps.
     */
    object Mpu6050Layout {
        const val ACCEL_X = 0
        const val GYRO_X = 6
        const val MIN_SIZE = 12
    }

    object Mpu6050 {
        const val ACCEL_LSB_PER_G = 16384f // ±2 g
        const val GYRO_LSB_PER_DPS = 131f // ±250 °/s
        const val GRAVITY_MPS2 = 9.80665f
        const val ACCEL_SCALE = 1.0f
        const val GYRO_SCALE = 1.0f
    }
}
