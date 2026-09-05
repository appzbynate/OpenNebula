package org.opennebula.air.imu

/**
 * Fusion `dt` from consecutive Air packet timestamps only — no host clock.
 *
 * Public docs call offset 0x04 nanoseconds; ar-drivers publishes µs
 * (`/1000`); Monado treats ticks as milliseconds (`NS_PER_TICK = 1e6`).
 * At ~1 kHz those units produce deltas of ~1e6 / ~1000 / ~1. The wrong
 * scale shrinks `dt` and the planted scene drags with the head.
 */
class PacketClock {
    @Volatile var nanosPerTick: Long = 1L
        private set
    @Volatile var decided: Boolean = false
        private set
    @Volatile var lastDelta: Long = 0L
        private set
    @Volatile var lastDtSeconds: Float = DEFAULT_DT
        private set

    private var lastTs = 0L

    fun reset() {
        nanosPerTick = 1L
        decided = false
        lastDelta = 0L
        lastDtSeconds = DEFAULT_DT
        lastTs = 0L
    }

    fun dtSeconds(packetTs: Long): Float {
        val prev = lastTs
        lastTs = packetTs
        if (prev == 0L || packetTs == 0L) {
            lastDtSeconds = DEFAULT_DT
            return DEFAULT_DT
        }
        val d = packetTs - prev
        lastDelta = d
        if (d <= 0L) {
            lastDtSeconds = DEFAULT_DT
            return DEFAULT_DT
        }
        if (!decided) {
            nanosPerTick = nanosPerTickForDelta(d)
            decided = true
        }
        val dt = d * nanosPerTick * 1e-9f
        lastDtSeconds = if (dt > 0f && dt <= MAX_DT) dt else DEFAULT_DT
        return lastDtSeconds
    }

    fun unitName(): String = when (nanosPerTick) {
        1_000_000L -> "ms"
        1_000L -> "us"
        else -> "ns"
    }

    companion object {
        const val DEFAULT_DT = 0.001f
        const val MAX_DT = 0.25f

        fun nanosPerTickForDelta(delta: Long): Long = when {
            delta < 50L -> 1_000_000L
            delta < 50_000L -> 1_000L
            else -> 1L
        }
    }
}

/**
 * Convert the Nreal Air packet timestamp to nanoseconds.
 *
 * Public docs call the field nanoseconds; ar-drivers divides by 1000 and
 * publishes microseconds; Monado uses 1 kHz millisecond ticks
 * (`NS_PER_TICK = 1_000_000`). Using the wrong unit shrinks `dt` and the
 * scene lags / drags with the head. Compare packet deltas to host elapsed
 * and pick ns / µs / ms.
 *
 * Live fusion uses [PacketClock] (packet deltas only). This host-time
 * helper stays for tests.
 */
class ImuClock {
    @Volatile var nanosPerTick: Long = 1L
        private set
    @Volatile var decided: Boolean = false
        private set

    private var originPacket = 0L
    private var originHostNs = 0L
    private var seen = 0

    fun reset() {
        nanosPerTick = 1L
        decided = false
        originPacket = 0L
        originHostNs = 0L
        seen = 0
    }

    fun toNanos(packetTs: Long, hostNs: Long): Long {
        if (packetTs == 0L) return 0L
        if (!decided) {
            if (seen == 0) {
                originPacket = packetTs
                originHostNs = hostNs
            }
            seen++
            val packetDelta = packetTs - originPacket
            val hostDelta = hostNs - originHostNs
            if (seen >= MIN_SAMPLES && packetDelta > 0L && hostDelta > 0L) {
                val nsPerTick = hostDelta.toDouble() / packetDelta.toDouble()
                nanosPerTick = when {
                    nsPerTick > MS_TICK -> 1_000_000L
                    nsPerTick > US_TICK -> 1_000L
                    else -> 1L
                }
                decided = true
            }
        }
        return packetTs * nanosPerTick
    }

    companion object {
        private const val MIN_SAMPLES = 16
        private const val MS_TICK = 50_000.0
        private const val US_TICK = 50.0
    }
}
