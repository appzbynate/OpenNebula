package org.opennebula.air.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorPipelineTest {
    @Test
    fun capture_size_keeps_phone_aspect_and_caps_long_side() {
        val (w, h) = MirrorPipeline.captureSize(1080, 2400, maxLong = 1600)
        assertEquals(720, w)
        assertEquals(1600, h)
        assertEquals(1080f / 2400f, w.toFloat() / h.toFloat(), 1e-3f)
    }

    @Test
    fun capture_size_even_dims_when_already_under_cap() {
        val (w, h) = MirrorPipeline.captureSize(1081, 1921, maxLong = 2000)
        assertEquals(1080, w)
        assertEquals(1920, h)
    }

    @Test
    fun frame_count_starts_at_zero() {
        assertEquals(0, MirrorPipeline().frameCount())
    }

    @Test
    fun opaque_argb_always_has_full_alpha() {
        assertEquals(0xFF336699.toInt(), MirrorPipeline.opaqueArgb(0x33, 0x66, 0x99))
        assertEquals(0xFF000000.toInt(), MirrorPipeline.opaqueArgb(0, 0, 0))
    }
}
