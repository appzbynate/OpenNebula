package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuClockTest {
    @Test
    fun nanosecond_stamps_stay_nanoseconds() {
        val clock = ImuClock()
        var host = 1_000_000_000L
        var packet = 10_000_000_000L
        repeat(20) {
            packet += 1_000_000L
            host += 1_000_000L
            clock.toNanos(packet, host)
        }
        assertEquals(1L, clock.nanosPerTick)
        assertEquals(true, clock.decided)
        assertEquals(packet, clock.toNanos(packet, host))
    }

    @Test
    fun microsecond_stamps_scale_by_thousand() {
        val clock = ImuClock()
        var host = 1_000_000_000L
        var packet = 10_000_000L
        repeat(20) {
            packet += 1_000L
            host += 1_000_000L
            clock.toNanos(packet, host)
        }
        assertEquals(1_000L, clock.nanosPerTick)
        assertEquals(packet * 1_000L, clock.toNanos(packet, host))
    }

    @Test
    fun millisecond_ticks_scale_like_monado() {
        val clock = ImuClock()
        var host = 1_000_000_000L
        var packet = 1_000L
        repeat(20) {
            packet += 1L
            host += 1_000_000L
            clock.toNanos(packet, host)
        }
        assertEquals(1_000_000L, clock.nanosPerTick)
        assertEquals(packet * 1_000_000L, clock.toNanos(packet, host))
    }
}

class PacketClockTest {
    @Test
    fun millisecond_ticks_are_one_ms() {
        val clock = PacketClock()
        var ts = 10L
        repeat(8) {
            ts += 1L
            clock.dtSeconds(ts)
        }
        assertEquals(true, clock.decided)
        assertEquals(1_000_000L, clock.nanosPerTick)
        assertEquals("ms", clock.unitName())
        assertEquals(0.001f, clock.lastDtSeconds, 1e-6f)
    }

    @Test
    fun microsecond_stamps_are_one_ms() {
        val clock = PacketClock()
        var ts = 10_000L
        repeat(8) {
            ts += 1_000L
            clock.dtSeconds(ts)
        }
        assertEquals(1_000L, clock.nanosPerTick)
        assertEquals("us", clock.unitName())
        assertEquals(0.001f, clock.lastDtSeconds, 1e-6f)
    }

    @Test
    fun nanosecond_stamps_are_one_ms() {
        val clock = PacketClock()
        var ts = 10_000_000_000L
        repeat(8) {
            ts += 1_000_000L
            clock.dtSeconds(ts)
        }
        assertEquals(1L, clock.nanosPerTick)
        assertEquals("ns", clock.unitName())
        assertEquals(0.001f, clock.lastDtSeconds, 1e-6f)
    }

    @Test
    fun classifies_delta_thresholds() {
        assertEquals(1_000_000L, PacketClock.nanosPerTickForDelta(1L))
        assertEquals(1_000L, PacketClock.nanosPerTickForDelta(1_000L))
        assertEquals(1L, PacketClock.nanosPerTickForDelta(1_000_000L))
    }
}
