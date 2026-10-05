package org.opennebula.air.imu

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
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
 * Proven Nreal/Xreal Air stack (nrealAirLinuxDriver / x-io Fusion):
 *
 * 1. Factory flash gyro/accel bias applied upstream ([AirImuBias], ar-drivers axes)
 * 2. [FusionOffset] runtime bias (cutoff 0.02 Hz, timeout 5 s, threshold 0.5 °/s)
 * 3. [FusionAhrs] UpdateNoMagnetometer, steady gain 0.04, accel rejection 10°
 * 4. Gravity reference is **Y-up** (ar-drivers body / OpenGL), not stock NWU Z-up
 *
 * Not a custom Madgwick fork — class name kept for call-site compatibility.
 */
class MadgwickImu {
    private val lock = Any()
    private val clock = PacketClock()
    private val offset = FusionOffset()
    private val thermalModel = ThermalBiasModel()
    private val ahrs = FusionAhrs()

    private var anchorW = 1f
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorZ = 0f

    private var lgx = 0f
    private var lgy = 0f
    private var lgz = 0f
    private var lastKp = 0f
    private var lastAccelMag = 0f
    private var rateEmaDps = 0f
    private var sampleCount = 0

    private var calibrating = true
    private var calibrated = false
    private var calSumX = 0.0
    private var calSumY = 0.0
    private var calSumZ = 0.0
    private var calSamples = 0

    fun reset() {
        synchronized(lock) {
            clock.reset()
            offset.reset()
            thermalModel.reset()
            ahrs.reset()
            anchorW = 1f
            anchorX = 0f
            anchorY = 0f
            anchorZ = 0f
            lgx = 0f
            lgy = 0f
            lgz = 0f
            lastKp = 0f
            lastAccelMag = 0f
            rateEmaDps = 0f
            sampleCount = 0
            calibrating = true
            calibrated = false
            calSumX = 0.0
            calSumY = 0.0
            calSumZ = 0.0
            calSamples = 0
        }
    }

    /** Aggressive Anchor: `anchorQuat = currentRawImuQuat.conjugate()`. */
    fun recenter() {
        synchronized(lock) {
            anchorW = ahrs.qw
            anchorX = -ahrs.qx
            anchorY = -ahrs.qy
            anchorZ = -ahrs.qz
        }
    }

    fun update(sample: ImuSample): Orientation {
        synchronized(lock) {
            val dt = clock.dtSeconds(sample.timestampNs).coerceIn(1e-5f, 0.05f)
            ahrs.setSamplePeriod(dt)

            lastAccelMag = sqrt(
                sample.accelMps2.x * sample.accelMps2.x +
                    sample.accelMps2.y * sample.accelMps2.y +
                    sample.accelMps2.z * sample.accelMps2.z,
            )
            // Fusion expects gyro °/s and accel in g.
            val gx = sample.gyroDps.x
            val gy = sample.gyroDps.y
            val gz = sample.gyroDps.z
            val ax = sample.accelMps2.x / GRAVITY
            val ay = sample.accelMps2.y / GRAVITY
            val az = sample.accelMps2.z / GRAVITY

            val tempC = ThermalBiasModel.rawToCelsius(sample.temperatureRaw)

            if (calibrating) {
                val rawMag = sqrt(gx * gx + gy * gy + gz * gz)
                if (rawMag < 4.0f) {
                    calSumX += gx
                    calSumY += gy
                    calSumZ += gz
                    calSamples++
                    if (calSamples >= CALIBRATION_SAMPLES) {
                        val bx = (calSumX / calSamples).toFloat()
                        val by = (calSumY / calSamples).toFloat()
                        val bz = (calSumZ / calSamples).toFloat()
                        offset.setDirectBias(bx, by, bz)
                        thermalModel.setInitialBias(bx, by, bz, tempC)
                        calibrating = false
                        calibrated = true
                    }
                }
            }

            if (calibrated) {
                val tBias = thermalModel.getBias(tempC)
                offset.setDirectBias(tBias[0], tBias[1], tBias[2])
            }

            val corr = offset.update(gx, gy, gz)
            val corrMag = sqrt(corr[0] * corr[0] + corr[1] * corr[1] + corr[2] * corr[2])
            rateEmaDps += (corrMag - rateEmaDps) * RATE_EMA

            // Temporal Zero-velocity update (ZUPT):
            // Only engage stillness lock if the head has been stationary for at least
            // SUSTAINED_STILL_SAMPLES (100 ms = 100 samples at 1 kHz).
            // During dynamic head shakes or rapid nods, angular velocity crosses zero for
            // only 2-5 ms. A temporal window prevents the zero-crossing turnaround from being
            // eaten by an instantaneous deadband, eliminating phase lag and window unpinning.
            val activeGx: Float
            val activeGy: Float
            val activeGz: Float
            if (offset.timer >= SUSTAINED_STILL_SAMPLES && corrMag <= STILL_DEADZONE_DPS) {
                activeGx = 0f
                activeGy = 0f
                activeGz = 0f
                if (calibrated || !calibrating) {
                    thermalModel.updateStationary(gx, gy, gz, tempC, 0.0005f)
                    offset.adaptBias(gx, gy, gz, 0.0005f)
                }
            } else {
                activeGx = corr[0]
                activeGy = corr[1]
                activeGz = corr[2]
            }

            ahrs.updateNoMagnetometer(activeGx, activeGy, activeGz, ax, ay, az)
            lastKp = ahrs.lastGain
            lgx = Math.toRadians(activeGx.toDouble()).toFloat()
            lgy = Math.toRadians(activeGy.toDouble()).toFloat()
            lgz = Math.toRadians(activeGz.toDouble()).toFloat()
            sampleCount++
            return published()
        }
    }

    fun residualBiasDps(): Vec3f = synchronized(lock) {
        Vec3f(offset.biasX, offset.biasY, offset.biasZ)
    }

    fun lastGyroRadS(): Vec3f = synchronized(lock) { Vec3f(lgx, lgy, lgz) }

    fun lastGain(): Float = synchronized(lock) { lastKp }

    fun lastDtMs(): Float = synchronized(lock) { clock.lastDtSeconds * 1000f }

    fun isRestFrozen(): Boolean = synchronized(lock) { offset.isStationary }

    fun stillSamples(): Int = synchronized(lock) { offset.timer }

    fun rateEmaDps(): Float = synchronized(lock) { rateEmaDps }

    fun gravityPulling(): Boolean = synchronized(lock) { ahrs.usingAccelerometer && lastKp > 0f }

    fun biasReady(): Boolean = synchronized(lock) {
        calibrated || (sampleCount >= WARMUP_SAMPLES || !ahrs.startup)
    }

    fun biasProgress(): Float = synchronized(lock) {
        if (calibrating) {
            (calSamples.toFloat() / CALIBRATION_SAMPLES.toFloat()).coerceIn(0f, 1f)
        } else if (calibrated) {
            1f
        } else {
            (sampleCount.toFloat() / WARMUP_SAMPLES.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun gyroDeadzoneDps(): Float = FusionOffset.THRESHOLD_DPS

    fun noiseFloorDps(): Float = synchronized(lock) {
        val b = residualBiasDps()
        sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
    }

    fun restartBiasCalibration() {
        synchronized(lock) {
            offset.reset()
            ahrs.reset()
            sampleCount = 0
            rateEmaDps = 0f
            lastKp = 0f
            calibrating = true
            calibrated = false
            calSumX = 0.0
            calSumY = 0.0
            calSumZ = 0.0
            calSamples = 0
        }
    }

    fun biasLogLine(): String = synchronized(lock) {
        val b = residualBiasDps()
        "gbias=${"%.2f".format(b.x)},${"%.2f".format(b.y)},${"%.2f".format(b.z)} " +
            "corr=${"%.1f".format(Math.toDegrees(lgx.toDouble()))}," +
            "${"%.1f".format(Math.toDegrees(lgy.toDouble()))}," +
            "${"%.1f".format(Math.toDegrees(lgz.toDouble()))} " +
            "${thermalModel.toLogLine()} " +
            "gain=${"%.2f".format(lastKp)} fusion=so3 amag=${"%.2f".format(lastAccelMag)}"
    }

    fun clockLogLine(): String = synchronized(lock) {
        "dt=${"%.3f".format(clock.lastDtSeconds * 1000f)}ms unit=${clock.unitName()}"
    }

    private fun published(): Orientation {
        val q = mul(anchorW, anchorX, anchorY, anchorZ, ahrs.qw, ahrs.qx, ahrs.qy, ahrs.qz)
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
        /** Sustained stillness window: 100 ms (100 samples @ 1 kHz) before zero-velocity clamping engages. */
        const val SUSTAINED_STILL_SAMPLES = 100
        const val STILL_DEADZONE_DPS = 0.45f
        const val STILL_DEADZONE_RAMP_DPS = 0.30f
        const val GRAVITY = 9.80665f
        /** Steady AHRS gain — low to rely on gyro during motion; startup still ramps 10→this. */
        const val GAIN = 0.04f
        const val REST_GRAVITY_GAIN = GAIN
        const val KP_REST = GAIN
        const val BETA = GAIN
        const val ACCEL_STILL_MPS2 = 0.1f * GRAVITY
        const val ACCEL_GATE_MPS2 = ACCEL_STILL_MPS2
        const val GYRO_GATE_DPS = FusionOffset.THRESHOLD_DPS
        const val MOTION_HOLDOFF_SAMPLES = 250
        const val STILL_BEFORE_GRAVITY = 0
        const val GRAVITY_RES_MIN = 0.06f
        const val HEARTBEAT_DPS = FusionOffset.THRESHOLD_DPS
        const val REST_ENTER_DPS = HEARTBEAT_DPS
        const val REST_EXIT_DPS = HEARTBEAT_DPS
        const val REST_EXIT_HARD_DPS = HEARTBEAT_DPS
        const val REST_DEADZONE_DPS = HEARTBEAT_DPS
        const val REST_ENTER_SAMPLES = 60
        const val REST_EXIT_SAMPLES = 1
        const val RATE_EMA = 0.12f
        /** Fusion README: 10° rejection + 5 s recovery is appropriate for most apps. */
        const val ACCEL_REJECTION_DEG = 10f
        const val INITIAL_STARTUP_GAIN = 10f
        const val STARTUP_PERIOD_S = 3f
        const val SAMPLE_HZ = 1000f
        const val RECOVERY_TRIGGER_S = 5f
        const val WARMUP_SAMPLES = 3000
        const val CALIBRATION_SAMPLES = 1800
        const val OFFSET_THRESHOLD_DPS = FusionOffset.THRESHOLD_DPS
        const val OFFSET_TIMEOUT_SAMPLES = FusionOffset.TIMEOUT_SAMPLES
        val OFFSET_FILTER = FusionOffset.FILTER_COEFF

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

/**
 * x-io FusionOffset — defaults used by Fusion / nrealAirLinuxDriver.
 * Gyro units: degrees/second.
 */
class FusionOffset {
    var biasX = 0f
        private set
    var biasY = 0f
        private set
    var biasZ = 0f
        private set
    var timer = 0
        private set
    val isStationary: Boolean
        get() = timer > 0

    fun reset() {
        biasX = 0f
        biasY = 0f
        biasZ = 0f
        timer = 0
    }

    fun setDirectBias(bx: Float, by: Float, bz: Float) {
        biasX = bx
        biasY = by
        biasZ = bz
        timer = TIMEOUT_SAMPLES + 1
    }

    fun adaptBias(gx: Float, gy: Float, gz: Float, alpha: Float = 0.001f) {
        biasX += alpha * (gx - biasX)
        biasY += alpha * (gy - biasY)
        biasZ += alpha * (gz - biasZ)
    }

    /** Returns bias-corrected gyro (°/s). */
    fun update(gx: Float, gy: Float, gz: Float): FloatArray {
        if (abs(gx) > THRESHOLD_DPS || abs(gy) > THRESHOLD_DPS || abs(gz) > THRESHOLD_DPS) {
            timer = 0
        } else {
            timer++
            if (timer > TIMEOUT_SAMPLES) {
                biasX += FILTER_COEFF * (gx - biasX)
                biasY += FILTER_COEFF * (gy - biasY)
                biasZ += FILTER_COEFF * (gz - biasZ)
            }
        }
        return floatArrayOf(gx - biasX, gy - biasY, gz - biasZ)
    }

    companion object {
        /** x-io FusionOffset defaults. */
        const val CUTOFF_HZ = 0.02f
        const val TIMEOUT_S = 5.0f
        const val THRESHOLD_DPS = 0.5f
        const val SAMPLE_HZ = 1000f
        const val TIMEOUT_SAMPLES = (TIMEOUT_S * SAMPLE_HZ).toInt()
        val FILTER_COEFF = (2f * Math.PI.toFloat() * CUTOFF_HZ / SAMPLE_HZ)
    }
}

/**
 * x-io FusionAhrs UpdateNoMagnetometer with Air body frame.
 *
 * After ar-drivers (−x,+z,+y), rest gravity is on **+Y** (OpenGL Y-up).
 * Stock Fusion NWU/NED HalfGravity assumes **+Z** up and would pull 90°.
 * Gravity reference here is Fusion's NWU HalfWest column (Y = up).
 *
 * Gyro °/s, accel in g. Steady gain 0.04, accel rejection 10°, recovery 5 s.
 */
class FusionAhrs {
    var qw = 1f
        private set
    var qx = 0f
        private set
    var qy = 0f
        private set
    var qz = 0f
        private set

    var samplePeriod = 1f / 1000f
        private set
    var lastGain = 0f
        private set
    var startup = true
        private set
    var usingAccelerometer = false
        private set

    private var startupGain = INITIAL_STARTUP_GAIN
    private var accelerationRecoveryTrigger = 0
    private var accelerationRecoveryThreshold = RECOVERY_TRIGGER_SAMPLES

    fun setSamplePeriod(dt: Float) {
        samplePeriod = dt.coerceIn(1e-5f, 0.05f)
    }

    fun reset() {
        qw = 1f
        qx = 0f
        qy = 0f
        qz = 0f
        startup = true
        startupGain = INITIAL_STARTUP_GAIN
        lastGain = 0f
        usingAccelerometer = false
        accelerationRecoveryTrigger = 0
        accelerationRecoveryThreshold = RECOVERY_TRIGGER_SAMPLES
    }

    fun updateNoMagnetometer(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        val gain = rampGain()

        val halfGx = Math.toRadians(gx.toDouble()).toFloat() * 0.5f
        val halfGy = Math.toRadians(gy.toDouble()).toFloat() * 0.5f
        val halfGz = Math.toRadians(gz.toDouble()).toFloat() * 0.5f

        val halfG = halfGravityYUp()
        val feedback = halfInclinationFeedback(halfG, ax, ay, az, gx, gy, gz)

        val hx = halfGx + feedback[0] * gain
        val hy = halfGy + feedback[1] * gain
        val hz = halfGz + feedback[2] * gain
        lastGain = if (usingAccelerometer) gain else 0f

        // Lie Group SO(3) closed-form exponential map integration:
        // Angular displacement dθ = 2 * h * samplePeriod
        val dtx = 2f * hx * samplePeriod
        val dty = 2f * hy * samplePeriod
        val dtz = 2f * hz * samplePeriod
        val thetaSq = dtx * dtx + dty * dty + dtz * dtz
        val dqw: Float
        val dqx: Float
        val dqy: Float
        val dqz: Float
        if (thetaSq < 1e-12f) {
            val s = 0.5f - thetaSq / 48f
            dqw = 1f - thetaSq / 8f
            dqx = dtx * s
            dqy = dty * s
            dqz = dtz * s
        } else {
            val theta = sqrt(thetaSq)
            val half = theta * 0.5f
            val s = sin(half.toDouble()).toFloat() / theta
            dqw = cos(half.toDouble()).toFloat()
            dqx = dtx * s
            dqy = dty * s
            dqz = dtz * s
        }
        val nw = qw * dqw - qx * dqx - qy * dqy - qz * dqz
        val nx = qw * dqx + qx * dqw + qy * dqz - qz * dqy
        val ny = qw * dqy - qx * dqz + qy * dqw + qz * dqx
        val nz = qw * dqz + qx * dqy - qy * dqx + qz * dqw
        qw = nw
        qx = nx
        qy = ny
        qz = nz
        normalize()

        if (startup) {
            setHeading(0f)
        }
    }

    private fun rampGain(): Float {
        if (!startup) return GAIN
        startupGain -= ((INITIAL_STARTUP_GAIN - GAIN) / STARTUP_PERIOD_S) * samplePeriod
        if (startupGain > GAIN) return startupGain
        startup = false
        return GAIN
    }

    /**
     * Earth +Y up (OpenGL / ar-drivers Air body). Same algebra as Fusion
     * NWU HalfWest — second column of the transposed rotation matrix / 2.
     */
    private fun halfGravityYUp(): FloatArray {
        return floatArrayOf(
            qx * qy + qw * qz,
            qw * qw - 0.5f + qy * qy,
            qy * qz - qw * qx,
        )
    }

    private fun halfInclinationFeedback(
        halfGravity: FloatArray,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
    ): FloatArray {
        usingAccelerometer = false
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (mag < 1e-6f) return floatArrayOf(0f, 0f, 0f)
        val inv = 1f / mag
        val sx = ax * inv
        val sy = ay * inv
        val sz = az * inv
        val residual = residual(sx, sy, sz, halfGravity[0], halfGravity[1], halfGravity[2])
        val r2 = residual[0] * residual[0] + residual[1] * residual[1] + residual[2] * residual[2]

        val gyroDps = sqrt(gx * gx + gy * gy + gz * gz)
        val accelDistG = abs(mag - 1.0f)

        // Continuous Bayesian Innovation Weighting:
        // Weight gravity tilt correction by likelihood that the measurement represents static Earth gravity.
        // As motion increases or linear acceleration distorts the vector, weight smoothly decays to 0.
        val bayesianWeight = if (startup) {
            1.0f
        } else {
            val gTerm = (gyroDps / BAYESIAN_GYRO_SIGMA_DPS).let { it * it }
            val aTerm = (accelDistG / BAYESIAN_ACCEL_SIGMA_G).let { it * it }
            exp(-0.5f * (gTerm + aTerm))
        }

        if (bayesianWeight < 0.01f) {
            return floatArrayOf(0f, 0f, 0f)
        }

        if (startup || r2 <= ACCELERATION_REJECTION) {
            usingAccelerometer = true
            accelerationRecoveryTrigger -= 9
        } else {
            accelerationRecoveryTrigger += 1
        }
        if (accelerationRecoveryTrigger > accelerationRecoveryThreshold) {
            accelerationRecoveryThreshold = 0
            usingAccelerometer = true
        } else {
            accelerationRecoveryThreshold = RECOVERY_TRIGGER_SAMPLES
        }
        accelerationRecoveryTrigger =
            accelerationRecoveryTrigger.coerceIn(0, RECOVERY_TRIGGER_SAMPLES)

        // Accelerometer only corrects tilt (pitch and roll). Zero out Y (heading)
        // to prevent tilt errors or lateral forces from rotating yaw.
        // Scale pitch and roll feedback by continuous bayesianWeight.
        return if (usingAccelerometer) {
            floatArrayOf(residual[0] * bayesianWeight, 0f, residual[2] * bayesianWeight)
        } else {
            floatArrayOf(0f, 0f, 0f)
        }
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

    private fun setHeading(headingDeg: Float) {
        val yaw = atan2(2.0 * (qw * qy - qx * qz), 1.0 - 2.0 * (qy * qy + qz * qz)).toFloat()
        val half = 0.5f * (yaw - Math.toRadians(headingDeg.toDouble()).toFloat())
        val rw = cos(half.toDouble()).toFloat()
        val ry = -sin(half.toDouble()).toFloat()
        val nw = rw * qw - ry * qy
        val nx = rw * qx + ry * qz
        val ny = rw * qy + ry * qw
        val nz = rw * qz - ry * qx
        qw = nw
        qx = nx
        qy = ny
        qz = nz
        normalize()
    }

    private fun normalize() {
        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (n > 1e-12f) {
            val inv = 1f / n
            qw *= inv
            qx *= inv
            qy *= inv
            qz *= inv
        }
    }

    companion object {
        const val GAIN = 0.04f
        const val INITIAL_STARTUP_GAIN = 10f
        const val STARTUP_PERIOD_S = 3f
        /** Fusion README: 10° is appropriate for most applications. */
        const val ACCELERATION_REJECTION_DEG = 10f
        val ACCELERATION_REJECTION =
            (0.5f * sin(Math.toRadians(ACCELERATION_REJECTION_DEG.toDouble())).toFloat()).let { it * it }
        const val MOTION_THRESHOLD_DPS = 2.5f
        const val MOTION_ACCEL_DIFF_G = 0.12f
        /**
         * Bayesian innovation weighting:
         * At rest and gentle gaze (< 5 dps): bayesianWeight > 0.9. Accelerometer tilt
         * feedback is active, continuously locking pitch and roll to Earth gravity.
         * Downward drift is completely prevented.
         *
         * During fast head shakes or rapid nods (> 35 dps): bayesianWeight < 0.01.
         * Gravity feedback shuts off completely, preventing centripetal and linear
         * acceleration from pulling the horizon sideways during shakes.
         */
        const val BAYESIAN_GYRO_SIGMA_DPS = 10.0f
        const val BAYESIAN_ACCEL_SIGMA_G = 0.15f
        const val RECOVERY_TRIGGER_S = 5f
        const val SAMPLE_HZ = 1000f
        const val RECOVERY_TRIGGER_SAMPLES = (RECOVERY_TRIGGER_S * SAMPLE_HZ).toInt()
    }
}
