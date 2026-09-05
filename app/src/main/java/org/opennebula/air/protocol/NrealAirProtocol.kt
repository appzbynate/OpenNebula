package org.opennebula.air.protocol

/**
 * First-gen Nreal Air USB identity, IMU, and MCU (brightness / display mode).
 *
 * HID map (Monado / ar-drivers-rs):
 * - Interface 3: IMU (interrupt IN ~0x84, OUT ~0x05) — head `0xAA`
 * - Interface 4: MCU (interrupt IN ~0x86, OUT ~0x07) — head `0xFD`
 */
object NrealAirProtocol {
    const val VENDOR_ID = 0x3318
    const val PRODUCT_ID = 0x0424
    const val PACKET_SIZE = 64

    const val IMU_INTERFACE_ID = 3
    const val MCU_INTERFACE_ID = 4
    /** Prefer the IMU HID interface; fall back to 4, then any interrupt-IN interface. */
    val PREFERRED_INTERFACE_IDS: IntArray = intArrayOf(IMU_INTERFACE_ID, MCU_INTERFACE_ID)

    const val USB_TRANSFER_TIMEOUT_MS = 250
    const val ACTIVATION_TIMEOUT_MS = 1_000
    const val IMU_COMMAND_TIMEOUT_MS = 1_000
    const val MCU_COMMAND_TIMEOUT_MS = 2_000
    const val IMU_CONFIG_MAX_BYTES = 256 * 1024

    const val IMU_CMD_GET_CONFIG_LEN = 0x14
    const val IMU_CMD_GET_CONFIG_CHUNK = 0x15
    const val IMU_CMD_ENABLE_STREAM = 0x19

    const val MCU_CMD_GET_BRIGHTNESS = 0x03
    const val MCU_CMD_SET_BRIGHTNESS = 0x04
    const val MCU_CMD_GET_DISPLAY_MODE = 0x07
    const val MCU_CMD_SET_DISPLAY_MODE = 0x08

    const val BRIGHTNESS_MIN = 0
    const val BRIGHTNESS_MAX = 7
    /** 1920×1080 mirror (both eyes same). */
    const val DISPLAY_MODE_2D = 1
    /** 3840×1080 native SBS — PC/EDID switch; often wrong for phone DP. */
    const val DISPLAY_MODE_3D_SBS_ULTRAWIDE = 3
    /**
     * 1920×1080 SBS (ar-drivers HalfSBS / linux `1920x1080_60_SBS`).
     * Same panel timing as 2D; glasses split left/right halves to each eye.
     * Correct match for OpenNebula software SBS on phone DisplayPort.
     */
    const val DISPLAY_MODE_3D_SBS = 8

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

    /**
     * MCU HID frame (ar-drivers-rs McuPacket):
     * `FD` + CRC32 LE + length u16 + request_id u32 + timestamp u32 + cmd u16 + 5 reserved + data.
     * length = data.size + 17. CRC over `[5, 5+length)`.
     */
    fun mcuControlFrame(cmdId: Int, data: ByteArray): ByteArray {
        require(data.size <= 42) { "MCU HID data max 42 bytes" }
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = 0xFD.toByte()
        val length = data.size + 17
        packet[5] = (length and 0xFF).toByte()
        packet[6] = ((length shr 8) and 0xFF).toByte()
        // request_id = 0x1337
        packet[7] = 0x37
        packet[8] = 0x13
        packet[9] = 0x00
        packet[10] = 0x00
        // timestamp = 0
        // cmd_id u16 LE at offset 15
        packet[15] = (cmdId and 0xFF).toByte()
        packet[16] = ((cmdId shr 8) and 0xFF).toByte()
        // reserved [17, 22)
        data.copyInto(packet, destinationOffset = 22)
        val crc = crc32Ieee(packet, 5, length)
        packet[1] = (crc and 0xFF).toByte()
        packet[2] = ((crc shr 8) and 0xFF).toByte()
        packet[3] = ((crc shr 16) and 0xFF).toByte()
        packet[4] = ((crc shr 24) and 0xFF).toByte()
        return packet
    }

    fun parseMcuReply(packet: ByteArray): Pair<Int, ByteArray>? {
        if (packet.size < 22 || (packet[0].toInt() and 0xFF) != 0xFD) return null
        val length = (packet[5].toInt() and 0xFF) or ((packet[6].toInt() and 0xFF) shl 8)
        if (length < 17 || length > 59) return null
        val cmd = (packet[15].toInt() and 0xFF) or ((packet[16].toInt() and 0xFF) shl 8)
        val dataLen = (length - 17).coerceIn(0, 42)
        return cmd to packet.copyOfRange(22, 22 + dataLen)
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
