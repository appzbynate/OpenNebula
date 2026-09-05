package org.opennebula.air.imu

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Orientation(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val qw: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
) {
    fun toLogLine(): String {
        return "yaw=${"%.1f".format(yawDeg)} pitch=${"%.1f".format(pitchDeg)} " +
            "roll=${"%.1f".format(rollDeg)} q=${"%.3f".format(qw)},${"%.3f".format(qx)}," +
            "${"%.3f".format(qy)},${"%.3f".format(qz)}"
    }

    companion object {
        val IDENTITY = Orientation(0f, 0f, 0f, 1f, 0f, 0f, 0f)
    }
}

/**
 * Gyro integrate + rest-bias + Anchor. Rest freeze zeros gyro (no heartbeat).
 * Accel horizon assist is off — it made world-locked icons slowly crawl/fall.
 */
class MadgwickImu {
    private val lock = Any()
    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f
    /** Conjugate of the raw IMU quat captured at Anchor. */
    private var anchorW = 1f
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorZ = 0f
    private val packetClock = PacketClock()
    private var lgx = 0f
    private var lgy = 0f
    private var lgz = 0f
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f
    private var warmupN = 0
    private var warmupSumX = 0f
    private var warmupSumY = 0f
    private var warmupSumZ = 0f
    private var lastKp = 0f
    private var lastAccelMag = 0f
    private var offsetTimer = 0
    /** Smoothed |ω| for rest gate only — never filters the quat. */
    private var rateEmaDps = 0f
    /** Hysteresis: stay frozen until rate clearly leaves rest (kills gate chatter). */
    private var restFrozen = true
    private var stillSamples = 0
    private var restEnterCount = 0
    private var restExitCount = 0

    fun reset() {
        synchronized(lock) {
            q0 = 1f
            q1 = 0f
            q2 = 0f
            q3 = 0f
            anchorW = 1f
            anchorX = 0f
            anchorY = 0f
            anchorZ = 0f
            packetClock.reset()
            lgx = 0f
            lgy = 0f
            lgz = 0f
            biasX = 0f
            biasY = 0f
            biasZ = 0f
            warmupN = 0
            warmupSumX = 0f
            warmupSumY = 0f
            warmupSumZ = 0f
            lastKp = 0f
            lastAccelMag = 0f
            offsetTimer = 0
            rateEmaDps = 0f
            restFrozen = true
            stillSamples = 0
            restEnterCount = 0
            restExitCount = 0
        }
    }

    /** Aggressive Anchor: `anchorQuat = currentRawImuQuat.conjugate()`. */
    fun recenter() {
        synchronized(lock) {
            anchorW = q0
            anchorX = -q1
            anchorY = -q2
            anchorZ = -q3
        }
    }

    fun update(sample: ImuSample): Orientation {
        synchronized(lock) {
            val dt = packetClock.dtSeconds(sample.timestampNs)
            val rawX = sample.gyroRadS.x
            val rawY = sample.gyroRadS.y
            val rawZ = sample.gyroRadS.z
            lastAccelMag = sqrt(
                sample.accelMps2.x * sample.accelMps2.x +
                    sample.accelMps2.y * sample.accelMps2.y +
                    sample.accelMps2.z * sample.accelMps2.z,
            )
            learnBias(rawX, rawY, rawZ)
            if (warmupN < WARMUP_SAMPLES) {
                lgx = 0f
                lgy = 0f
                lgz = 0f
                lastKp = 0f
                return published()
            }
            val gx = rawX - biasX
            val gy = rawY - biasY
            val gz = rawZ - biasZ
            refineOffset(gx, gy, gz)
            val gyroDps = sqrt(gx * gx + gy * gy + gz * gz) * (180f / Math.PI.toFloat())
            rateEmaDps += (gyroDps - rateEmaDps) * RATE_EMA
            lastKp = 0f
            // Rest freeze kills heartbeat, but must NOT eat the start of a glance —
            // that reads as "apps stick to my head for 5–10°, then lock".
            if (restFrozen) {
                if (gyroDps >= REST_EXIT_HARD_DPS) {
                    restFrozen = false
                    stillSamples = 0
                    restExitCount = 0
                    restEnterCount = 0
                    // fall through → integrate this sample
                } else if (rateEmaDps >= REST_EXIT_DPS) {
                    restExitCount++
                    restEnterCount = 0
                    if (restExitCount >= REST_EXIT_SAMPLES) {
                        restFrozen = false
                        stillSamples = 0
                        restExitCount = 0
                        // fall through → integrate
                    } else {
                        lgx = 0f; lgy = 0f; lgz = 0f
                        stillSamples++
                        // Rest gravity caused world-locked icons to slowly "fall"
                        // toward accel horizon; keep freeze-only (no applyGravityOnly).
                        return published()
                    }
                } else {
                    restExitCount = 0
                    lgx = 0f; lgy = 0f; lgz = 0f
                    stillSamples++
                    return published()
                }
            } else {
                if (rateEmaDps < REST_ENTER_DPS) {
                    restEnterCount++
                    restExitCount = 0
                    if (restEnterCount >= REST_ENTER_SAMPLES) {
                        restFrozen = true
                        stillSamples = 0
                        restEnterCount = 0
                        lgx = 0f; lgy = 0f; lgz = 0f
                        stillSamples++
                        return published()
                    }
                } else {
                    restEnterCount = 0
                }
            }
            stillSamples = 0
            lgx = gx
            lgy = gy
            lgz = gz
            integrateGyro(gx, gy, gz, dt)
            return published()
        }
    }

    fun residualBiasDps(): Vec3f = synchronized(lock) {
        Vec3f(
            Math.toDegrees(biasX.toDouble()).toFloat(),
            Math.toDegrees(biasY.toDouble()).toFloat(),
            Math.toDegrees(biasZ.toDouble()).toFloat(),
        )
    }

    fun lastGyroRadS(): Vec3f = synchronized(lock) { Vec3f(lgx, lgy, lgz) }

    fun lastGain(): Float = synchronized(lock) { lastKp }

    fun lastDtMs(): Float = synchronized(lock) { packetClock.lastDtSeconds * 1000f }

    fun isRestFrozen(): Boolean = synchronized(lock) { restFrozen }

    fun stillSamples(): Int = synchronized(lock) { stillSamples }

    fun rateEmaDps(): Float = synchronized(lock) { rateEmaDps }

    fun gravityPulling(): Boolean = synchronized(lock) {
        restFrozen && stillSamples >= STILL_BEFORE_GRAVITY && lastKp > 0f
    }

    fun biasReady(): Boolean = synchronized(lock) { warmupN >= WARMUP_SAMPLES }

    /** Calib UI progress 0..1 (baseline fusion; API kept for guided Calibrate). */
    fun biasProgress(): Float = synchronized(lock) {
        (warmupN.toFloat() / WARMUP_SAMPLES.toFloat()).coerceIn(0f, 1f)
    }

    fun gyroDeadzoneDps(): Float = REST_DEADZONE_DPS

    fun noiseFloorDps(): Float = synchronized(lock) {
        if (warmupN <= 0) return@synchronized 0f
        // Approx from bias magnitude after quiet warmup (baseline has no mag ring).
        val bx = Math.toDegrees(biasX.toDouble()).toFloat()
        val by = Math.toDegrees(biasY.toDouble()).toFloat()
        val bz = Math.toDegrees(biasZ.toDouble()).toFloat()
        sqrt(bx * bx + by * by + bz * bz)
    }

    /** Restart quiet warmup for guided calibration (does not change fusion math). */
    fun restartBiasCalibration() {
        synchronized(lock) {
            warmupN = 0
            warmupSumX = 0f
            warmupSumY = 0f
            warmupSumZ = 0f
            biasX = 0f
            biasY = 0f
            biasZ = 0f
            offsetTimer = 0
            stillSamples = 0
            restEnterCount = 0
            restExitCount = 0
            restFrozen = true
            rateEmaDps = 0f
            lastKp = 0f
        }
    }

    fun biasLogLine(): String = synchronized(lock) {
        val b = residualBiasDps()
        val corrDps = Vec3f(
            Math.toDegrees(lgx.toDouble()).toFloat(),
            Math.toDegrees(lgy.toDouble()).toFloat(),
            Math.toDegrees(lgz.toDouble()).toFloat(),
        )
        "gbias=${"%.1f".format(b.x)},${"%.1f".format(b.y)},${"%.1f".format(b.z)} " +
            "corr=${"%.1f".format(corrDps.x)},${"%.1f".format(corrDps.y)},${"%.1f".format(corrDps.z)} " +
            "w=$warmupN gain=${"%.2f".format(lastKp)} amag=${"%.2f".format(lastAccelMag)}"
    }

    fun clockLogLine(): String = synchronized(lock) {
        "dt=${"%.3f".format(packetClock.lastDtSeconds * 1000f)}ms unit=${packetClock.unitName()}"
    }

    private fun integrateGyro(gx: Float, gy: Float, gz: Float, dt: Float) {
        applyRates(0.5f * gx, 0.5f * gy, 0.5f * gz, dt)
    }

    /** Accel horizon assist with gyro=0 (rest only). */
    private fun applyGravityOnly(dt: Float, accel: Vec3f) {
        val mag = lastAccelMag
        if (mag < 1e-3f || abs(mag - GRAVITY) >= ACCEL_STILL_MPS2) {
            lastKp = 0f
            return
        }
        val inv = 1f / mag
        val ax = accel.x * inv
        val ay = accel.y * inv
        val az = accel.z * inv
        val halfG = halfGravityYup()
        val res = residual(ax, ay, az, halfG[0], halfG[1], halfG[2])
        val rn = sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2])
        if (rn < GRAVITY_RES_MIN) {
            lastKp = 0f
            return
        }
        lastKp = GAIN
        applyRates(res[0] * GAIN, res[1] * GAIN, res[2] * GAIN, dt)
    }

    private fun applyRates(hx: Float, hy: Float, hz: Float, dt: Float) {
        val dw = (-q1 * hx - q2 * hy - q3 * hz) * dt
        val dx = (q0 * hx + q2 * hz - q3 * hy) * dt
        val dy = (q0 * hy - q1 * hz + q3 * hx) * dt
        val dz = (q0 * hz + q1 * hy - q2 * hx) * dt
        q0 += dw
        q1 += dx
        q2 += dy
        q3 += dz
        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (n > 1e-12f) {
            val inv = 1f / n
            q0 *= inv
            q1 *= inv
            q2 *= inv
            q3 *= inv
        }
    }

    /** Body-frame +Y scaled by 1/2 (Fusion HalfWest, NWU). */
    private fun halfGravityYup(): FloatArray {
        return floatArrayOf(
            q1 * q2 + q0 * q3,
            q0 * q0 - 0.5f + q2 * q2,
            q2 * q3 - q0 * q1,
        )
    }

    private fun residual(
        sx: Float, sy: Float, sz: Float,
        rx: Float, ry: Float, rz: Float,
    ): FloatArray {
        val dot = sx * rx + sy * ry + sz * rz
        val cx = sy * rz - sz * ry
        val cy = sz * rx - sx * rz
        val cz = sx * ry - sy * rx
        if (dot > 0f) return floatArrayOf(cx, cy, cz)
        val n = sqrt(cx * cx + cy * cy + cz * cz)
        if (n < 1e-12f) return floatArrayOf(0f, 0f, 0f)
        val inv = 1f / n
        return floatArrayOf(cx * inv, cy * inv, cz * inv)
    }

    private fun learnBias(gx: Float, gy: Float, gz: Float) {
        if (warmupN < WARMUP_SAMPLES) {
            warmupSumX += gx
            warmupSumY += gy
            warmupSumZ += gz
            warmupN++
            if (warmupN == WARMUP_SAMPLES) {
                biasX = warmupSumX / warmupN
                biasY = warmupSumY / warmupN
                biasZ = warmupSumZ / warmupN
            }
        }
    }

    /** FusionOffset: slow EMA of leftover gyro when |corr| < 3 °/s for a few s. */
    private fun refineOffset(gx: Float, gy: Float, gz: Float) {
        val dpsX = abs(Math.toDegrees(gx.toDouble()).toFloat())
        val dpsY = abs(Math.toDegrees(gy.toDouble()).toFloat())
        val dpsZ = abs(Math.toDegrees(gz.toDouble()).toFloat())
        if (dpsX < OFFSET_THRESHOLD_DPS && dpsY < OFFSET_THRESHOLD_DPS && dpsZ < OFFSET_THRESHOLD_DPS) {
            if (offsetTimer < OFFSET_TIMEOUT_SAMPLES) {
                offsetTimer++
            } else {
                biasX += gx * OFFSET_FILTER
                biasY += gy * OFFSET_FILTER
                biasZ += gz * OFFSET_FILTER
            }
        } else {
            offsetTimer = 0
        }
    }

    private fun published(): Orientation {
        val q = mul(anchorW, anchorX, anchorY, anchorZ, q0, q1, q2, q3)
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]
        val forward = rotateVec(w, x, y, z, 0f, 0f, -1f)
        val up = rotateVec(w, x, y, z, 0f, 1f, 0f)
        val yaw = -Math.toDegrees(atan2(forward[0].toDouble(), (-forward[2]).toDouble()))
        val horiz = sqrt(forward[0] * forward[0] + forward[2] * forward[2]).toDouble()
        val pitch = Math.toDegrees(atan2(forward[1].toDouble(), horiz))
        val cy = cos(Math.toRadians(yaw)).toFloat()
        val sy = sin(Math.toRadians(yaw)).toFloat()
        val roll = Math.toDegrees(atan2((-(up[0] * cy + up[2] * sy)).toDouble(), up[1].toDouble()))
        return Orientation(
            yawDeg = yaw.toFloat(),
            pitchDeg = pitch.toFloat(),
            rollDeg = roll.toFloat(),
            qw = w,
            qx = x,
            qy = y,
            qz = z,
        )
    }

    private fun rotateVec(w: Float, x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float): FloatArray {
        val cx = y * vz - z * vy
        val cy = z * vx - x * vz
        val cz = x * vy - y * vx
        return floatArrayOf(
            vx + 2f * w * cx + 2f * (y * cz - z * cy),
            vy + 2f * w * cy + 2f * (z * cx - x * cz),
            vz + 2f * w * cz + 2f * (x * cy - y * cx),
        )
    }

    companion object {
        const val GRAVITY = 9.80665f
        /** Soft horizon only after long still (pitch drift fix). */
        const val GAIN = 0.35f
        const val KP_REST = GAIN
        const val BETA = GAIN
        const val ACCEL_STILL_MPS2 = 0.1f * GRAVITY
        const val ACCEL_GATE_MPS2 = ACCEL_STILL_MPS2
        const val GYRO_GATE_DPS = 6f
        const val MOTION_HOLDOFF_SAMPLES = 250
        /** ~0.8 s continuous rest before gravity may run. */
        const val STILL_BEFORE_GRAVITY = 800
        /** Ignore tiny accel residual (heartbeat); real pitch drift is larger. */
        const val GRAVITY_RES_MIN = 0.06f
        /** Enter rest freeze below this smoothed |ω|. */
        const val REST_ENTER_DPS = 2.5f
        /** Soft EMA exit (backup). Hard exit is what kills head-coupled lag. */
        const val REST_EXIT_DPS = 5.0f
        /**
         * Instant unfreeze + integrate this sample. Was 12°/s and ate ~5–10° of
         * glance while pose stayed frozen (apps stuck to HMD). Keep above BCG.
         */
        const val REST_EXIT_HARD_DPS = 4.0f
        /** Alias for tests / HUD. */
        const val REST_DEADZONE_DPS = REST_ENTER_DPS
        /** ~60 ms quiet before freeze engages. */
        const val REST_ENTER_SAMPLES = 60
        /** Soft exit if EMA stays above REST_EXIT (~40 ms). */
        const val REST_EXIT_SAMPLES = 40
        const val RATE_EMA = 0.12f
        const val ACCEL_REJECTION = 0.00754f
        const val INITIAL_STARTUP_GAIN = GAIN
        const val STARTUP_PERIOD_S = 0f
        const val SAMPLE_HZ = 1000f
        const val STARTUP_GAIN_RATE = 0f
        const val WARMUP_SAMPLES = 1500
        const val OFFSET_THRESHOLD_DPS = 3f
        const val OFFSET_TIMEOUT_SAMPLES = 3 * 1000
        const val OFFSET_FILTER = (2f * Math.PI.toFloat() * 0.05f / SAMPLE_HZ)

        /** Hamilton product `a * b`. Display pose is `anchor.conjugate * raw`. */
        fun mul(
            aw: Float, ax: Float, ay: Float, az: Float,
            bw: Float, bx: Float, by: Float, bz: Float,
        ): FloatArray {
            return floatArrayOf(
                aw * bw - ax * bx - ay * by - az * bz,
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
            )
        }
    }
}
