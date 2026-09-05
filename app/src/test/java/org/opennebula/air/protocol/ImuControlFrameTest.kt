package org.opennebula.air.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImuControlFrameTest {
    @Test
    fun enable_stream_matches_ar_drivers_crc() {
        val frame = NrealAirProtocol.ACTIVATION_PAYLOAD
        assertEquals(64, frame.size)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0x04.toByte(), frame[5])
        assertEquals(0x00.toByte(), frame[6])
        assertEquals(0x19.toByte(), frame[7])
        assertEquals(0x01.toByte(), frame[8])
        // zlib.crc32([0x04, 0x00, 0x19, 0x01]) == 0x4221d1c5
        assertEquals(0xC5.toByte(), frame[1])
        assertEquals(0xD1.toByte(), frame[2])
        assertEquals(0x21.toByte(), frame[3])
        assertEquals(0x42.toByte(), frame[4])
        val tail = ByteArray(55)
        assertArrayEquals(tail, frame.copyOfRange(9, 64))
    }
}
