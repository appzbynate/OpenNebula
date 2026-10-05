package org.opennebula.air.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the tuned Fusion stack (steady gain 0.04, no mag,
 * FusionOffset 0.02 Hz / 5 s / 0.5 °/s) — Y-up gravity for Air body frame.
 */
class MadgwickImuTest {
    @Test
    fun fusion_gain_matches_tuned_steady() {
        assertEquals(0.04f, MadgwickImu.GAIN, 0f)
        assertEquals(0.04f, MadgwickImu.REST_GRAVITY_GAIN, 0f)
        assertEquals(0.5f, FusionOffset.THRESHOLD_DPS, 0f)
        assertEquals(5.0f, FusionOffset.TIMEOUT_S, 0f)
    }

    @Test
    fun after_startup_gain_is_steady() {
        val filter = MadgwickImu()
        // 3 s startup ramp at 1 kHz.
        repeat(3500) { i -> filter.update(lookSample(i, 0f, 0f, 0f)) }
        assertEquals(0.04f, filter.lastGain(), 0.005f)
        assertTrue(filter.gravityPulling())
    }

    @Test
    fun tip_then_still_levels_pitch_via_accel() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        val jerk = Vec3f(0f, 20f, 0f)
        for (i in t0 until t0 + 300) pose = filter.update(sample(i, 60f, 0f, 0f, jerk))
        val tipped = pose.pitchDeg
        assertTrue(abs(tipped) > 8f)
        val levelAccel = Vec3f(0f, MadgwickImu.GRAVITY, 0f)
        // Accel rejection (~5 s) then slow leveling at gain 0.04.
        val hold = FusionAhrs.RECOVERY_TRIGGER_SAMPLES + 60_000
        for (i in t0 + 300 until t0 + 300 + hold) {
            pose = filter.update(sample(i, 0f, 0f, 0f, levelAccel))
        }
        assertEquals(0f, pose.pitchDeg, 5f)
        assertEquals(0f, pose.rollDeg, 5f)
    }

    @Test
    fun jerk_accel_is_rejected_briefly() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val jerk = Vec3f(0f, 20f, 0f)
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 50) pose = filter.update(sample(i, 0f, 0f, 0f, jerk))
        // Large linear accel should not instantly yank attitude to a nonsense horizon.
        assertTrue(abs(pose.pitchDeg) < 25f)
    }

    @Test
    fun warmup_with_tilted_accel_then_recenter_holds() {
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
        assertEquals(0f, pose.pitchDeg, 3f)
        assertEquals(0f, pose.yawDeg, 3f)
        assertEquals(0f, pose.rollDeg, 3f)
    }

    @Test
    fun fusion_offset_learns_small_rest_bias_after_timeout() {
        val filter = MadgwickImu()
        // 0.3 °/s — under 0.5 °/s threshold; EMA is slow (0.02 Hz).
        val learnSamples = FusionOffset.TIMEOUT_SAMPLES + 40_000 // ~40 s of learning
        val still = MadgwickImu.WARMUP_SAMPLES + learnSamples
        repeat(still) { i -> filter.update(lookSample(i, 0.3f, 0f, 0f)) }
        val b = filter.residualBiasDps()
        assertEquals(0.3f, b.x, 0.08f)
        filter.recenter()
        var pose = Orientation.IDENTITY
        for (i in still until still + 2000) {
            pose = filter.update(lookSample(i, 0.3f, 0f, 0f))
        }
        assertEquals(0f, pose.pitchDeg, 4f)
        assertEquals(0f, pose.yawDeg, 4f)
    }

    @Test
    fun large_constant_rate_is_not_offset_it_is_motion() {
        val filter = MadgwickImu()
        // 25 °/s is above 0.5 °/s threshold — FusionOffset must not treat as bias.
        // Factory flash cal is what removes large gyro_bias; not runtime offset.
        repeat(MadgwickImu.WARMUP_SAMPLES) { i ->
            filter.update(lookSample(i, 25f, 0f, 0f))
        }
        val b = filter.residualBiasDps()
        assertEquals(0f, b.x, 0.05f)
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
        assertEquals(1f, pose.qw, 0.05f)
        assertEquals(0f, pose.qx, 0.05f)
        assertEquals(0f, pose.qy, 0.05f)
        assertEquals(0f, pose.qz, 0.05f)
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
        assertEquals(30f, pose.yawDeg, 4f)
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
        assertEquals(30f, afterTurn, 4f)
        for (i in tLeft + 500 until tLeft + 2500) pose = filter.update(lookSample(i, 0f, 0f, 0f))
        // Mag-less: yaw holds (no magnetic heading).
        assertEquals(afterTurn, pose.yawDeg, 2f)
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
        assertEquals(30f, afterTurn, 5f)
        for (i in tNod + 500 until tNod + 2500) pose = filter.update(nodSample(i, 30f, 0f))
        assertEquals(afterTurn, pose.pitchDeg, 4f)
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
    fun slow_glance_above_offset_threshold_integrates() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        // Just above FusionOffset 0.5 °/s threshold — must integrate, not bias-eat.
        for (i in t0 until t0 + 500) pose = filter.update(lookSample(i, 0f, 0.8f, 0f))
        assertTrue(abs(pose.yawDeg) > 0.3f)
        val b = filter.residualBiasDps()
        assertEquals(0f, b.y, 0.05f)
    }

    @Test
    fun slow_nod_above_threshold_is_not_absorbed_into_bias() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        val before = filter.residualBiasDps()
        for (i in t0 until t0 + 2000) pose = filter.update(lookSample(i, 4f, 0f, 0f))
        assertTrue("slow nod should change pitch (got ${pose.pitchDeg})", abs(pose.pitchDeg) > 5f)
        val after = filter.residualBiasDps()
        assertEquals("bias must not absorb motion above 0.5 °/s", before.x, after.x, 0.05f)
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
        assertEquals(120f, pose.yawDeg, 8f)
        assertEquals(0f, pose.pitchDeg, 8f)
        assertEquals(0f, pose.rollDeg, 8f)
        for (i in t90 + 2000 until t90 + 2600) pose = filter.update(lookSample(i, 0f, 0f, 0f))
        assertEquals(120f, pose.yawDeg, 8f)
        assertEquals(0f, pose.pitchDeg, 6f)
        assertEquals(0f, pose.rollDeg, 6f)
    }

    @Test
    fun yaw_at_one_g_does_not_couple_roll() {
        val filter = MadgwickImu()
        var pose = Orientation.IDENTITY
        repeat(MadgwickImu.WARMUP_SAMPLES) { i -> pose = filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()
        val t0 = MadgwickImu.WARMUP_SAMPLES
        for (i in t0 until t0 + 500) pose = filter.update(lookSample(i, 0f, 40f, 0f))
        assertEquals(20f, pose.yawDeg, 3f)
        assertEquals(0f, pose.rollDeg, 3f)
        assertEquals(0f, pose.pitchDeg, 3f)
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
            assertTrue("roll snap at i=$i roll=${pose.rollDeg}", abs(pose.rollDeg) < 10f)
            assertTrue("pitch snap at i=$i pitch=${pose.pitchDeg}", abs(pose.pitchDeg) < 10f)
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
        assertEquals(30f, pose.yawDeg, 3f)
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
        assertEquals(30f, pose.yawDeg, 3f)
    }

    @Test
    fun active_calibration_accumulates_and_sets_bias() {
        val filter = MadgwickImu()
        filter.restartBiasCalibration()
        assertEquals(0f, filter.biasProgress(), 0f)

        // Feed quiet samples with a 0.45 deg/s offset on X and -0.35 on Y
        val offsetSamples = MadgwickImu.CALIBRATION_SAMPLES
        repeat(offsetSamples) { i ->
            filter.update(lookSample(i, 0.45f, -0.35f, 0.05f))
        }

        assertTrue(filter.biasReady())
        assertEquals(1f, filter.biasProgress(), 0.01f)
        val bias = filter.residualBiasDps()
        assertEquals(0.45f, bias.x, 0.02f)
        assertEquals(-0.35f, bias.y, 0.02f)
        assertEquals(0.05f, bias.z, 0.02f)
    }

    @Test
    fun motion_gating_shuts_off_accelerometer_during_head_turn() {
        val filter = MadgwickImu()
        // Complete startup period (3.5 s)
        repeat(3500) { i -> filter.update(lookSample(i, 0f, 0f, 0f)) }
        assertEquals(0.04f, filter.lastGain(), 0.005f)

        // Turn head at 35 dps
        filter.update(lookSample(3501, 0f, 35f, 0f))
        assertEquals(0f, filter.lastGain(), 0f)
        org.junit.Assert.assertFalse(filter.gravityPulling())

        // Stop turning head
        filter.update(lookSample(3502, 0f, 0f, 0f))
        assertEquals(0.04f, filter.lastGain(), 0.005f)
        assertTrue(filter.gravityPulling())
    }

    @Test
    fun still_deadzone_prevents_creep_at_rest() {
        val filter = MadgwickImu()
        repeat(3500) { i -> filter.update(lookSample(i, 0f, 0f, 0f)) }
        filter.recenter()

        var pose = Orientation.IDENTITY
        // Feed 5000 samples of 0.25 dps micro-tremor / sensor noise (below 0.45 dps deadzone)
        for (i in 3500 until 8500) {
            pose = filter.update(lookSample(i, 0.20f, -0.25f, 0.15f))
        }

        // Without ZUPT stillness deadzone, this would have drifted > 1.25 degrees.
        // With ZUPT, orientation remains pinned at zero.
        assertEquals("yaw must not creep during stillness", 0f, pose.yawDeg, 0.001f)
        assertEquals("pitch must not creep during stillness", 0f, pose.pitchDeg, 0.001f)
        assertEquals("roll must not creep during stillness", 0f, pose.rollDeg, 0.001f)
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
