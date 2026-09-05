package org.opennebula.air.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opennebula.air.imu.Orientation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PoseSmootherTest {
    @Test
    fun wrap_takes_short_arc_across_180() {
        assertEquals(-2f, PoseSmoother.wrapDeg(179f - (-179f)), 0.01f)
        assertEquals(2f, PoseSmoother.wrapDeg(-179f - 179f), 0.01f)
    }

    @Test
    fun reset_snaps_to_identity() {
        val s = PoseSmoother(minCutoffHz = 0.2f, beta = 0.01f)
        val tipped = yawPose(40f)
        repeat(40) {
            s.step(tipped, nowNs = (it + 1) * 1_000_000L)
        }
        s.reset(Orientation.IDENTITY)
        val pose = s.step(Orientation.IDENTITY, nowNs = 50_000_000L)
        assertEquals(1f, pose.qw, 0.01f)
        assertEquals(0f, pose.qx, 0.01f)
        assertEquals(0f, pose.qy, 0.01f)
        assertEquals(0f, pose.qz, 0.01f)
    }

    @Test
    fun heartbeat_band_jitter_is_held() {
        // ~1.2 Hz, 0.4° — under break-hold; display must stay put.
        val s = PoseSmoother()
        s.reset(Orientation.IDENTITY)
        s.step(Orientation.IDENTITY, nowNs = 1_000_000L)
        val ampDeg = 0.40f
        val hz = 1.2f
        var peak = 0f
        for (i in 2..800) {
            val t = i * 0.001f
            val pitch = ampDeg * sin(2.0 * Math.PI * hz * t).toFloat()
            val out = s.step(pitchPose(pitch), nowNs = i * 1_000_000L)
            val ang = angleDeg(1f, 0f, 0f, 0f, out.qw, out.qx, out.qy, out.qz)
            if (i > 200) peak = maxOf(peak, ang)
        }
        assertTrue("peak=$peak amp=$ampDeg", peak < 0.08f)
    }

    @Test
    fun fast_head_turn_stays_snappy() {
        val s = PoseSmoother()
        s.reset(Orientation.IDENTITY)
        s.step(Orientation.IDENTITY, nowNs = 1_000_000L)
        // Ramp yaw so raw speed leaves hold, then settle on 25°.
        var ang = 0f
        for (i in 2..40) {
            val yaw = (i - 1) * (25f / 38f)
            val out = s.step(yawPose(yaw), nowNs = i * 1_000_000L)
            ang = angleDeg(1f, 0f, 0f, 0f, out.qw, out.qx, out.qy, out.qz)
        }
        assertTrue("ang=$ang", ang > 15f)
    }

    @Test
    fun leftover_head_turn_does_not_crawl_for_a_second() {
        val s = PoseSmoother()
        s.reset(Orientation.IDENTITY)
        s.step(Orientation.IDENTITY, nowNs = 1_000_000L)
        val target = yawPose(30f)
        // First frames: big lag breaks hold and tracks.
        var err = 30f
        for (i in 2..80) {
            val out = s.step(target, nowNs = i * 1_000_000L)
            err = angleDeg(out.qw, out.qx, out.qy, out.qz, target.qw, target.qx, target.qy, target.qz)
        }
        assertTrue("err=$err", err < 1.5f)
    }

    private fun yawPose(yawDeg: Float): Orientation {
        val h = Math.toRadians(yawDeg * 0.5).toFloat()
        return Orientation(yawDeg, 0f, 0f, cos(h), 0f, sin(h), 0f)
    }

    private fun pitchPose(pitchDeg: Float): Orientation {
        val h = Math.toRadians(pitchDeg * 0.5).toFloat()
        return Orientation(0f, pitchDeg, 0f, cos(h), sin(h), 0f, 0f)
    }

    private fun angleDeg(
        aw: Float, ax: Float, ay: Float, az: Float,
        bw: Float, bx: Float, by: Float, bz: Float,
    ): Float {
        var dot = abs(aw * bw + ax * bx + ay * by + az * bz)
        if (dot > 1f) dot = 1f
        return Math.toDegrees(2.0 * kotlin.math.acos(dot.toDouble())).toFloat()
    }
}
