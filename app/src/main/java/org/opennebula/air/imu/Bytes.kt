package org.opennebula.air.imu

fun ByteArray.readU8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.readI16Le(offset: Int): Int {
    val lo = this[offset].toInt() and 0xFF
    val hi = this[offset + 1].toInt()
    return (hi shl 8) or lo
}

fun ByteArray.readU16Le(offset: Int): Int {
    val lo = this[offset].toInt() and 0xFF
    val hi = this[offset + 1].toInt() and 0xFF
    return (hi shl 8) or lo
}

fun ByteArray.readI24Le(offset: Int): Int {
    val v = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)
    // Force 32-bit sign extension: bit 23 → bit 31, then arithmetic shr.
    return (v shl 8) shr 8
}

fun ByteArray.readI32Le(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        (this[offset + 3].toInt() shl 24)
}

fun ByteArray.readU32Le(offset: Int): Long {
    return (readI32Le(offset).toLong() and 0xFFFFFFFFL)
}

fun ByteArray.readI64Le(offset: Int): Long {
    val lo = readU32Le(offset)
    val hi = readU32Le(offset + 4)
    return (hi shl 32) or lo
}

fun ByteArray.toHex(limit: Int = size): String {
    val n = minOf(limit, size)
    val out = StringBuilder(n * 3)
    for (i in 0 until n) {
        if (i > 0) out.append(' ')
        out.append(String.format("%02X", this[i].toInt() and 0xFF))
    }
    return out.toString()
}
