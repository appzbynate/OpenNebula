package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MadgwickImuTest {
    @Test
    fun baseline_gravity_assist_is_off() {
        assertEquals(0.35f, MadgwickImu.GAIN, 0f)
        val filter = MadgwickImu()
        repeat(MadgwickImu.WARMUP_SAMPLES + 50) { i -> filter.update(lookSample(i, 0f, 0f, 0f)) }
        // Warmup ends frozen but short of STILL_BEFORE_GRAVITY in this loop alone…
        assertEquals(0f, filter.lastGain(), 0f)
    }

    @Test
    fun long_still_does_not_gravity_pull_pitch() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        val jerk = Vec3f(0f, 20f, 0f)
        for (i in t0 until t0 + 300) pose = filter.update(sample(i, 60f, 0f, 0f, jerk))
        val tipped = pose.pitchDeg
        assertTrue(abs(tipped) > 8f)
        for (i in t0 + 300 until t0 + 300 + MadgwickImu.STILL_BEFORE_GRAVITY + 2500) {
            pose = filter.update(lookSample(i, 0f, 0f, 0f))
        }
        // Rest freeze holds pose — no accel horizon crawl (that looked like icons falling).
        assertEquals(tipped, pose.pitchDeg, 1.5f)
        assertEquals(0f, filter.lastGain(), 0f)
    }

    @Test
    fun jerk_keeps_gain_zero() {
        val filter = MadgwickImu()
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val jerk = Vec3f(0f, 20f, 0f)
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 50) filter.update(sample(i, 0f, 0f, 0f, jerk))
        assertEquals(0f, filter.lastGain(), 0f)
    }

    @Test
    fun warmup_with_tilted_accel_does_not_yank_pitch() {
        val filter = MadgwickImu()
        val tilted = Vec3f(0.64f, 9.57f, 1.99f)
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            filter.update(sample(i, 0f, 0f, 0f, tilted))
        }
        filter.recenter()
        var pose = Orientation.IDENTITY
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) {
            pose = filter.update(sample(i, 0f, 0f, 0f, tilted))
        }
        assertEquals(0f, pose.pitchDeg, 2f)
        assertEquals(0f, pose.yawDeg, 2f)
        assertEquals(0f, pose.rollDeg, 2f)
    }

    @Test
    fun warmup_seeds_bias_even_when_raw_offset_looks_like_motion() {
        val filter = MadgwickImu()
        // Constant 25°/s raw bias — must not block warmup via STILL_DPS gate.
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            filter.update(lookSample(i, 25f, 0f, 0f))
        }
        val b = filter.residualBiasDps()
        assertEquals(25f, b.x, 1f)
        assertEquals(0f, b.y, 1f)
        assertEquals(0f, b.z, 1f)
        // After warmup, corrected still samples should not keep integrating.
        var pose = Orientation.IDENTITY
        filter.recenter()
        for (i in MadgwickImu.WARMUP_SAMPLES until MadgwickImu.WARMUP_SAMPLES + 2000) {
            pose = filter.update(lookSample(i, 25f, 0f, 0f))
        }
        assertEquals(0f, pose.pitchDeg, 3f)
        assertEquals(0f, pose.yawDeg, 3f)
    }

    @Test
    fun aggressive_anchor_is_conjugate_times_raw() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 300) pose = filter.update(lookSample(i, 0f, 60f, 0f))
        val before = pose
        assertTrue(abs(before.qw - 1f) > 0.01f || abs(before.qy) > 0.01f)
        filter.recenter()
        pose = filter.update(lookSample(t0 + 300, 0f, 0f, 0f))
        assertEquals(1f, pose.qw, 0.02f)
        assertEquals(0f, pose.qx, 0.02f)
        assertEquals(0f, pose.qy, 0.02f)
        assertEquals(0f, pose.qz, 0.02f)
    }

    @Test
    fun packet_timestamp_dt_not_fixed_one_ms() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        val stepNs = 2_000_000L
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            pose = filter.update(lookSample(i, 0f, 0f, 0f, nsPerSample = stepNs))
        }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) {
            pose = filter.update(lookSample(i, 0f, 30f, 0f, nsPerSample = stepNs))
        }
        assertEquals(30f, pose.yawDeg, 2f)
    }

    @Test
    fun recenter_zeros_current_pose() {
        val filter = MadgwickImu()
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            filter.update(
                ImuSample(
                    source = ImuParserSource.AIR_PACKED,
                    timestampNs = (i + 1) * 1_000_000L,
                    temperatureRaw = null,
                    accelRaw = Vec3i(0, 0, 0),
                    gyroRaw = Vec3i(0, 0, 0),
                    accelMps2 = REST_UP,
                    gyroDps = Vec3f(0f, 0f, 0f),
                    pitchDeg = 0f,
                    rollDeg = 0f,
                ),
            )
        }
        filter.recenter()
        val pose = filter.update(
            ImuSample(
                source = ImuParserSource.AIR_PACKED,
                timestampNs = (MadgwickImu.WARMUP_SAMPLES + 1) * 1_000_000L,
                temperatureRaw = null,
                accelRaw = Vec3i(0, 0, 0),
                gyroRaw = Vec3i(0, 0, 0),
                accelMps2 = REST_UP,
                gyroDps = Vec3f(0f, 0f, 0f),
                pitchDeg = 0f,
                rollDeg = 0f,
            ),
        )
        assertEquals(0f, pose.rollDeg, 2f)
    }

    @Test
    fun look_left_stays_planted_after_two_seconds_still() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tLeft = MadgwickImu.WARMUP_SAMPLES
        for (i in tLeft until tLeft + 500) pose = filter.update(lookSample(i, 0f, 60f, 0f))
        val afterTurn = pose.yawDeg
        assertEquals(30f, afterTurn, 3f)
        for (i in tLeft + 500 until tLeft + 2500) pose = filter.update(lookSample(i, 0f, 0f, 0f))
        assertEquals(afterTurn, pose.yawDeg, 1.5f)
    }

    @Test
    fun nod_stays_planted_when_accel_matches() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tNod = MadgwickImu.WARMUP_SAMPLES
        for (i in tNod until tNod + 500) {
            val angle = ((i - tNod) * 0.060f).coerceAtMost(30f)
            pose = filter.update(nodSample(i, angle, 60f))
        }
        val afterTurn = pose.pitchDeg
        assertEquals(30f, afterTurn, 4f)
        for (i in tNod + 500 until tNod + 2500) pose = filter.update(nodSample(i, 30f, 0f))
        assertEquals(afterTurn, pose.pitchDeg, 3f)
    }

    @Test
    fun hamilton_is_origin_conjugate_times_body() {
        val iTimesJ = MadgwickImu.mul(0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(0f, iTimesJ[0], 1e-5f)
        assertEquals(0f, iTimesJ[1], 1e-5f)
        assertEquals(0f, iTimesJ[2], 1e-5f)
        assertEquals(1f, iTimesJ[3], 1e-5f)
        val originTimesQ = MadgwickImu.mul(0.6f, 0.8f, 0f, 0f, 0.8f, 0f, 0.6f, 0f)
        val recovered = MadgwickImu.mul(
            0.6f, -0.8f, 0f, 0f,
            originTimesQ[0], originTimesQ[1], originTimesQ[2], originTimesQ[3],
        )
        assertEquals(0.8f, recovered[0], 1e-5f)
        assertEquals(0f, recovered[1], 1e-5f)
        assertEquals(0.6f, recovered[2], 1e-5f)
        assertEquals(0f, recovered[3], 1e-5f)
    }

    @Test
    fun short_still_does_not_gravity_yank_before_delay() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tPitch = MadgwickImu.WARMUP_SAMPLES
        val jerk = Vec3f(0f, 20f, 0f)
        for (i in tPitch until tPitch + 300) pose = filter.update(sample(i, 60f, 0f, 0f, jerk))
        val tipped = pose.pitchDeg
        assertTrue(abs(tipped) > 8f)
        // Under STILL_BEFORE_GRAVITY — must not yank yet.
        for (i in tPitch + 300 until tPitch + 300 + MadgwickImu.STILL_BEFORE_GRAVITY - 50) {
            pose = filter.update(lookSample(i, 0f, 0f, 0f))
        }
        assertEquals(tipped, pose.pitchDeg, 1.5f)
        assertEquals(0f, filter.lastGain(), 0f)
    }

    @Test
    fun jerk_accel_does_not_pull_pitch_to_horizon() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val jerk = Vec3f(0f, 20f, 0f)
        val tJerk = MadgwickImu.WARMUP_SAMPLES
        for (i in tJerk until tJerk + 300) pose = filter.update(sample(i, 60f, 0f, 0f, jerk))
        val afterTurn = pose.pitchDeg
        assertTrue(abs(afterTurn) > 8f)
        for (i in tJerk + 300 until tJerk + 1600) pose = filter.update(sample(i, 0f, 0f, 0f, jerk))
        assertEquals(afterTurn, pose.pitchDeg, 1.5f)
    }

    @Test
    fun nod_turnaround_does_not_yank_pitch_with_gravity() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 250) pose = filter.update(nodSample(i, ((i - t0) * 0.06f).coerceAtMost(15f), 60f))
        val tipped = pose.pitchDeg
        assertTrue(abs(tipped) > 8f)
        for (i in t0 + 250 until t0 + 250 + 400) {
            pose = filter.update(nodSample(i, tipped, 0f))
            assertEquals("gravity yank at rest i=$i", tipped, pose.pitchDeg, 1.5f)
        }
    }

    @Test
    fun rest_freeze_ignores_heartbeat_gyro_without_rubberband() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 2000) {
            pose = filter.update(lookSample(i, 0f, 0.6f, 0f))
        }
        assertEquals(0f, pose.yawDeg, 0.5f)
        // Leaving rest: need rate above EXIT hysteresis.
        for (i in t0 + 2000 until t0 + 2050) pose = filter.update(lookSample(i, 0f, 10f, 0f))
        for (i in t0 + 2050 until t0 + 2550) pose = filter.update(lookSample(i, 0f, 60f, 0f))
        assertEquals(30f, pose.yawDeg, 4f)
    }

    @Test
    fun slow_look_is_not_absorbed_as_gyro_bias() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tSlow = MadgwickImu.WARMUP_SAMPLES
        // Above REST_EXIT_HARD so rest-hysteresis does not freeze the glance.
        for (i in tSlow until tSlow + 500) pose = filter.update(lookSample(i, 0f, 15f, 0f))
        assertEquals(7.5f, pose.yawDeg, 1.5f)
        val b = filter.residualBiasDps()
        assertEquals(0f, b.y, 1e-5f)
    }

    @Test
    fun y_up_rest_does_not_roll_the_floor_ninety() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tY = MadgwickImu.WARMUP_SAMPLES
        for (i in tY until tY + 600) pose = filter.update(lookSample(i, 0f, 0f, 0f))
        assertEquals(0f, pose.pitchDeg, 3f)
        assertEquals(0f, pose.rollDeg, 3f)
    }

    @Test
    fun look_past_ninety_does_not_flip_the_floor() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t90 = MadgwickImu.WARMUP_SAMPLES
        for (i in t90 until t90 + 2000) pose = filter.update(lookSample(i, 0f, 60f, 0f))
        assertEquals(120f, pose.yawDeg, 6f)
        assertEquals(0f, pose.pitchDeg, 6f)
        assertEquals(0f, pose.rollDeg, 6f)
        for (i in t90 + 2000 until t90 + 2600) pose = filter.update(lookSample(i, 0f, 0f, 0f))
        assertEquals(120f, pose.yawDeg, 6f)
        assertEquals(0f, pose.pitchDeg, 4f)
        assertEquals(0f, pose.rollDeg, 4f)
    }

    @Test
    fun yaw_at_one_g_does_not_couple_roll() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) pose = filter.update(lookSample(i, 0f, 40f, 0f))
        assertEquals(20f, pose.yawDeg, 2f)
        assertEquals(0f, pose.rollDeg, 1.5f)
        assertEquals(0f, pose.pitchDeg, 1.5f)
    }

    @Test
    fun still_moving_gate_does_not_snap_roll() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val tGate = MadgwickImu.WARMUP_SAMPLES
        for (i in tGate until tGate + 700) {
            val gy = if ((i - tGate) % 40 < 20) 22f else 0f
            pose = filter.update(lookSample(i, 0f, gy, 0f))
            assertTrue("roll snap at i=$i roll=${pose.rollDeg}", abs(pose.rollDeg) < 8f)
            assertTrue("pitch snap at i=$i pitch=${pose.pitchDeg}", abs(pose.pitchDeg) < 8f)
        }
    }

    @Test
    fun z_up_rest_is_gated_as_one_g_but_does_not_snap_in_one_sample() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        pose = filter.update(sample(0, 0f, 0f, 0f, REST_Z))
        assertEquals(0f, pose.pitchDeg, 3f)
        assertEquals(0f, pose.rollDeg, 3f)
    }

    @Test
    fun millisecond_packet_ticks_still_integrate_yaw() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            pose = filter.update(lookSample(i, 0f, 0f, 0f, nsPerSample = 1L))
        }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) pose = filter.update(lookSample(i, 0f, 60f, 0f, nsPerSample = 1L))
        assertEquals(30f, pose.yawDeg, 2f)
    }

    @Test
    fun microsecond_packet_stamps_still_integrate_yaw() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            pose = filter.update(lookSample(i, 0f, 0f, 0f, nsPerSample = 1_000L))
        }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) {
            pose = filter.update(lookSample(i, 0f, 60f, 0f, nsPerSample = 1_000L))
        }
        assertEquals(30f, pose.yawDeg, 2f)
    }
}

private val REST_UP = Vec3f(0f, 9.81f, 0f)
private val REST_Z = Vec3f(0f, 0f, 9.81f)

private fun lookSample(i: Int, gx: Float, gy: Float, gz: Float, nsPerSample: Long = 1_000_000L): ImuSample {
    return sample(i, gx, gy, gz, REST_UP, nsPerSample)
}

private fun nodSample(i: Int, pitchDeg: Float, gx: Float): ImuSample {
    val r = Math.toRadians(pitchDeg.toDouble())
    val accel = Vec3f(
        0f,
        kotlin.math.cos(r).toFloat() * 9.81f,
        -kotlin.math.sin(r).toFloat() * 9.81f,
    )
    return sample(i, gx, 0f, 0f, accel)
}

private fun sample(
    i: Int,
    gx: Float,
    gy: Float,
    gz: Float,
    accel: Vec3f,
    nsPerSample: Long = 1_000_000L,
): ImuSample {
    val tilt = ImuParser.tiltFromAccel(accel)
    return ImuSample(
        source = ImuParserSource.AIR_PACKED,
        timestampNs = (i + 1) * nsPerSample,
        temperatureRaw = null,
        accelRaw = Vec3i(0, 0, 0),
        gyroRaw = Vec3i(0, 0, 0),
        accelMps2 = accel,
        gyroDps = Vec3f(gx, gy, gz),
        pitchDeg = tilt.first,
        rollDeg = tilt.second,
    )
}
