package org.opennebula.air.imu

import android.util.Log
import org.opennebula.air.logging.LogTags
import org.opennebula.air.render.DisplayPoseBridge
import org.opennebula.air.shell.SpatialLayout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deep motion diagnostics for world-lock debugging (less reliance on verbal reports).
 *
 * Writes under app external files:
 * - `imu_trace.csv` — 50 Hz ring (~90 s)
 * - `imu_events.csv` — state transitions + jitter/drift spikes
 * - `imu_summary.txt` — rolling auto-diagnosis (human + machine readable)
 *
 * Logcat: [LogTags.MOTION] ~5 Hz + [LogTags.EVENT] on anomalies.
 */
class ImuTrace(
    private val dir: File,
    private val appVersion: String,
) {
    private val traceFile = File(dir, "imu_trace.csv")
    private val eventFile = File(dir, "imu_events.csv")
    private val summaryFile = File(dir, "imu_summary.txt")

    private val lock = Any()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ImuTraceIo").apply { isDaemon = true }
    }
    private val lines = ArrayDeque<String>(MAX + 8)
    private val events = ArrayDeque<String>(EVENT_MAX + 8)
    private val seq = AtomicInteger(0)
    private var keep = 0

    private var prevObjX = 0f
    private var prevObjY = 0f
    private var havePrevObj = false
    private var prevQw = 1f
    private var prevQx = 0f
    private var prevQy = 0f
    private var prevQz = 0f
    private var havePrevPose = false
    private var prevHostMs = 0L

    private var prevRest = -1
    private var prevGrav = -1
    private var stillYaw0 = 0f
    private var stillPitch0 = 0f
    private var stillRoll0 = 0f
    private var stillObjX0 = 0f
    private var stillObjY0 = 0f
    private var stillSegStartMs = 0L
    private var inStillSeg = false

    // Rolling window stats (last ~STATS_WIN samples at 50 Hz).
    private val winObjJump = FloatArray(STATS_WIN)
    private val winPoseDps = FloatArray(STATS_WIN)
    private val winGyro = FloatArray(STATS_WIN)
    private val winMismatch = FloatArray(STATS_WIN)
    private val winStill = BooleanArray(STATS_WIN)
    private var winI = 0
    private var winN = 0
    private var restTrans = 0
    private var gravTrans = 0
    private var jitterEvents = 0
    private var driftEvents = 0
    private var lastSummaryMs = 0L

    fun onSample(sample: ImuSample, pose: Orientation, fusion: MadgwickImu) {
        if (keep++ % DECIMATE != 0) return
        val n = seq.incrementAndGet()
        val hostMs = System.currentTimeMillis()
        val dtMs = if (prevHostMs > 0L) (hostMs - prevHostMs).toFloat() else fusion.lastDtMs()
        val dtS = (dtMs / 1000f).coerceIn(0.005f, 0.1f)
        prevHostMs = hostMs

        val b = fusion.residualBiasDps()
        val c = fusion.lastGyroRadS()
        val cx = Math.toDegrees(c.x.toDouble()).toFloat()
        val cy = Math.toDegrees(c.y.toDouble()).toFloat()
        val cz = Math.toDegrees(c.z.toDouble()).toFloat()
        val gyroMag = mag3(sample.gyroDps.x, sample.gyroDps.y, sample.gyroDps.z)
        val corrMag = mag3(cx, cy, cz)
        val accelMag = mag3(sample.accelMps2.x, sample.accelMps2.y, sample.accelMps2.z)
        val accelErr = abs(accelMag - MadgwickImu.GRAVITY)

        var poseDps = 0f
        if (havePrevPose) {
            poseDps = quatAngleDeg(prevQw, prevQx, prevQy, prevQz, pose.qw, pose.qx, pose.qy, pose.qz) / dtS
        }
        prevQw = pose.qw
        prevQx = pose.qx
        prevQy = pose.qy
        prevQz = pose.qz
        havePrevPose = true

        // When integrating gyro, pose rate ≈ |corr|; large gap = freeze/gravity/bug.
        val mismatch = poseDps - corrMag

        val disp = DisplayPoseBridge.get()
        val objDx = if (havePrevObj) disp.objX - prevObjX else 0f
        val objDy = if (havePrevObj) disp.objY - prevObjY else 0f
        val objJump = sqrt(objDx * objDx + objDy * objDy)
        prevObjX = disp.objX
        prevObjY = disp.objY
        havePrevObj = true

        val rest = if (fusion.isRestFrozen()) 1 else 0
        val grav = if (fusion.gravityPulling()) 1 else 0
        val hold = if (disp.holding) 1 else 0
        val rateEma = fusion.rateEmaDps()
        val stillN = fusion.stillSamples()

        // --- events ---
        if (prevRest >= 0 && rest != prevRest) {
            restTrans++
            emitEvent(
                hostMs, n, if (rest == 1) "REST_ENTER" else "REST_EXIT",
                "rateEma=${"%.2f".format(rateEma)} gyro=${"%.2f".format(gyroMag)} " +
                    "yaw=${"%.2f".format(pose.yawDeg)} pitch=${"%.2f".format(pose.pitchDeg)}",
            )
            if (rest == 1) {
                inStillSeg = true
                stillSegStartMs = hostMs
                stillYaw0 = pose.yawDeg
                stillPitch0 = pose.pitchDeg
                stillRoll0 = pose.rollDeg
                stillObjX0 = disp.objX
                stillObjY0 = disp.objY
            } else if (inStillSeg) {
                val dur = (hostMs - stillSegStartMs) / 1000f
                val dy = wrapDeg(pose.yawDeg - stillYaw0)
                val dp = pose.pitchDeg - stillPitch0
                val dr = pose.rollDeg - stillRoll0
                val ox = disp.objX - stillObjX0
                val oy = disp.objY - stillObjY0
                emitEvent(
                    hostMs, n, "STILL_SEG_END",
                    "dur_s=${"%.2f".format(dur)} Δypr=${"%.3f".format(dy)},${"%.3f".format(dp)},${"%.3f".format(dr)} " +
                        "Δobj=${"%.4f".format(ox)},${"%.4f".format(oy)}",
                )
                inStillSeg = false
            }
        }
        if (prevGrav >= 0 && grav != prevGrav) {
            gravTrans++
            emitEvent(
                hostMs, n, if (grav == 1) "GRAV_ON" else "GRAV_OFF",
                "still=$stillN gain=${"%.2f".format(fusion.lastGain())} " +
                    "pitch=${"%.2f".format(pose.pitchDeg)} accelErr=${"%.3f".format(accelErr)}",
            )
        }
        prevRest = rest
        prevGrav = grav

        // Jitter: big on-screen jump while sensors say nearly still.
        if (gyroMag < JITTER_GYRO_MAX && objJump > JITTER_OBJ_MIN) {
            jitterEvents++
            emitEvent(
                hostMs, n, "JITTER",
                "objJump=${"%.4f".format(objJump)} gyro=${"%.2f".format(gyroMag)} " +
                    "poseDps=${"%.2f".format(poseDps)} rest=$rest grav=$grav " +
                    "yaw=${"%.2f".format(pose.yawDeg)} pitch=${"%.2f".format(pose.pitchDeg)}",
            )
        }
        // Drift: rest-frozen but pose still creeping.
        if (rest == 1 && poseDps > DRIFT_POSE_DPS && gyroMag < 3f) {
            driftEvents++
            emitEvent(
                hostMs, n, "DRIFT",
                "poseDps=${"%.2f".format(poseDps)} corr=${"%.2f".format(corrMag)} " +
                    "grav=$grav gain=${"%.2f".format(fusion.lastGain())} " +
                    "ypr=${"%.2f".format(pose.yawDeg)},${"%.2f".format(pose.pitchDeg)},${"%.2f".format(pose.rollDeg)}",
            )
        }
        // Fusion vs gyro disagreement while supposedly integrating.
        if (rest == 0 && abs(mismatch) > MISMATCH_DPS && corrMag > 5f) {
            emitEvent(
                hostMs, n, "MISMATCH",
                "poseDps=${"%.1f".format(poseDps)} corr=${"%.1f".format(corrMag)} " +
                    "Δ=${"%.1f".format(mismatch)} gyro=${"%.1f".format(gyroMag)}",
            )
        }

        pushWin(objJump, poseDps, gyroMag, mismatch, rest == 1 && gyroMag < 3f)

        val line = buildString(320) {
            append(n); append(',')
            append(hostMs); append(',')
            append("%.3f".format(fusion.lastDtMs())); append(',')
            append("%.2f".format(dtMs)); append(',')
            append("%.2f".format(sample.gyroDps.x)); append(',')
            append("%.2f".format(sample.gyroDps.y)); append(',')
            append("%.2f".format(sample.gyroDps.z)); append(',')
            append("%.2f".format(gyroMag)); append(',')
            append("%.2f".format(cx)); append(',')
            append("%.2f".format(cy)); append(',')
            append("%.2f".format(cz)); append(',')
            append("%.2f".format(corrMag)); append(',')
            append("%.2f".format(poseDps)); append(',')
            append("%.2f".format(mismatch)); append(',')
            append("%.2f".format(sample.accelMps2.x)); append(',')
            append("%.2f".format(sample.accelMps2.y)); append(',')
            append("%.2f".format(sample.accelMps2.z)); append(',')
            append("%.2f".format(accelMag)); append(',')
            append("%.3f".format(accelErr)); append(',')
            append("%.2f".format(pose.yawDeg)); append(',')
            append("%.2f".format(pose.pitchDeg)); append(',')
            append("%.2f".format(pose.rollDeg)); append(',')
            append("%.4f".format(pose.qw)); append(',')
            append("%.4f".format(pose.qx)); append(',')
            append("%.4f".format(pose.qy)); append(',')
            append("%.4f".format(pose.qz)); append(',')
            append("%.4f".format(disp.display.qw)); append(',')
            append("%.4f".format(disp.display.qx)); append(',')
            append("%.4f".format(disp.display.qy)); append(',')
            append("%.4f".format(disp.display.qz)); append(',')
            append(hold); append(',')
            append("%.3f".format(disp.lagDeg)); append(',')
            append("%.4f".format(disp.objX)); append(',')
            append("%.4f".format(disp.objY)); append(',')
            append("%.4f".format(disp.objZ)); append(',')
            append("%.4f".format(objDx)); append(',')
            append("%.4f".format(objDy)); append(',')
            append("%.4f".format(objJump)); append(',')
            append("%.2f".format(b.x)); append(',')
            append("%.2f".format(b.y)); append(',')
            append("%.2f".format(b.z)); append(',')
            append("%.2f".format(fusion.lastGain())); append(',')
            append(rest); append(',')
            append(stillN); append(',')
            append("%.2f".format(rateEma)); append(',')
            append(grav); append(',')
            append("%.2f".format(MadgwickImu.REST_ENTER_DPS)); append(',')
            append("%.2f".format(MadgwickImu.REST_EXIT_HARD_DPS)); append(',')
            append("%.2f".format(MadgwickImu.GAIN)); append(',')
            append("%.2f".format(-SpatialLayout.PANEL_Z)); append(',')
            append(appVersion)
        }
        synchronized(lock) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(line)
            if (n % FLUSH_EVERY == 0) {
                scheduleWriteLocked()
            }
        }

        if (n % 10 == 0) {
            Log.i(
                LogTags.MOTION,
                "v=$appVersion g=${"%.1f".format(gyroMag)} corr=${"%.1f".format(corrMag)} " +
                    "poseDps=${"%.1f".format(poseDps)} mis=${"%.1f".format(mismatch)} " +
                    "ypr=${"%.2f".format(pose.yawDeg)},${"%.2f".format(pose.pitchDeg)},${"%.2f".format(pose.rollDeg)} " +
                    "objJ=${"%.4f".format(objJump)} rest=$rest grav=$grav still=$stillN " +
                    "bias=${"%.2f".format(b.x)},${"%.2f".format(b.y)},${"%.2f".format(b.z)} " +
                    "enter=${MadgwickImu.REST_ENTER_DPS} exitH=${MadgwickImu.REST_EXIT_HARD_DPS} " +
                    "gain=${MadgwickImu.GAIN} aErr=${"%.3f".format(accelErr)}",
            )
        }

        // Extra burst logging while moving — richer than 5 Hz for ratchet diagnosis.
        if (rest == 0 && n % 5 == 0) {
            Log.i(
                LogTags.MOTION,
                "MOVE gx=${"%.2f".format(sample.gyroDps.x)},${"%.2f".format(sample.gyroDps.y)},${"%.2f".format(sample.gyroDps.z)} " +
                    "corr=${"%.2f".format(cx)},${"%.2f".format(cy)},${"%.2f".format(cz)} " +
                    "Δobj=${"%.4f".format(objDx)},${"%.4f".format(objDy)}",
            )
        }

        if (hostMs - lastSummaryMs >= SUMMARY_EVERY_MS) {
            lastSummaryMs = hostMs
            writeSummary(hostMs, pose, b, rateEma)
        }
    }

    fun flush() {
        synchronized(lock) {
            scheduleWriteLocked()
        }
        writeSummary(System.currentTimeMillis(), null, null, 0f)
    }

    private fun emitEvent(hostMs: Long, n: Int, kind: String, detail: String) {
        val row = "$n,$hostMs,$kind,$detail"
        synchronized(lock) {
            if (events.size >= EVENT_MAX) events.removeFirst()
            events.addLast(row)
        }
        Log.w(LogTags.EVENT, "$kind $detail")
    }

    private fun pushWin(objJump: Float, poseDps: Float, gyro: Float, mismatch: Float, still: Boolean) {
        val i = winI % STATS_WIN
        winObjJump[i] = objJump
        winPoseDps[i] = poseDps
        winGyro[i] = gyro
        winMismatch[i] = mismatch
        winStill[i] = still
        winI++
        if (winN < STATS_WIN) winN++
    }

    private fun writeSummary(hostMs: Long, pose: Orientation?, bias: Vec3f?, rateEma: Float) {
        if (winN < 25) return
        val n = winN
        var stillC = 0
        var jumpSum = 0.0
        var jumpMax = 0f
        var poseStillMax = 0f
        var misAbsSum = 0.0
        val stillJumps = ArrayList<Float>(n)
        for (k in 0 until n) {
            val i = (winI - n + k + STATS_WIN * 4) % STATS_WIN
            if (winStill[i]) {
                stillC++
                stillJumps.add(winObjJump[i])
                if (winPoseDps[i] > poseStillMax) poseStillMax = winPoseDps[i]
            }
            jumpSum += winObjJump[i]
            if (winObjJump[i] > jumpMax) jumpMax = winObjJump[i]
            misAbsSum += abs(winMismatch[i])
        }
        stillJumps.sort()
        val p95 = if (stillJumps.isEmpty()) 0f else stillJumps[(stillJumps.size * 0.95f).toInt().coerceAtMost(stillJumps.lastIndex)]
        val p50 = if (stillJumps.isEmpty()) 0f else stillJumps[stillJumps.size / 2]
        val stillPct = 100f * stillC / n
        val issues = ArrayList<String>(6)
        if (p95 > 0.008f) issues.add("STILL_OBJ_JITTER_HIGH p95=${"%.4f".format(p95)}m")
        if (poseStillMax > 3f) issues.add("STILL_POSE_RATE_HIGH max=${"%.1f".format(poseStillMax)}dps")
        if (restTrans > 8) issues.add("REST_CHATTER count=$restTrans (window)")
        if (jitterEvents > 5) issues.add("JITTER_EVENTS count=$jitterEvents")
        if (driftEvents > 5) issues.add("DRIFT_EVENTS count=$driftEvents")
        if (gravTrans > 4) issues.add("GRAV_TOGGLES count=$gravTrans")
        if (issues.isEmpty()) issues.add("none_flagged")

        val text = buildString {
            appendLine("OpenNebula motion summary")
            appendLine("version=$appVersion host_ms=$hostMs panel_m=${-SpatialLayout.PANEL_Z}")
            appendLine("window_samples=$n (~${"%.1f".format(n / 50f)}s) trace_rows_buffered=${synchronized(lock) { lines.size }}")
            appendLine("still_pct=${"%.1f".format(stillPct)}")
            appendLine("obj_jump_mean_m=${"%.5f".format(jumpSum / n)} obj_jump_max_m=${"%.4f".format(jumpMax)}")
            appendLine("still_obj_jump_p50_m=${"%.5f".format(p50)} still_obj_jump_p95_m=${"%.5f".format(p95)}")
            appendLine("still_pose_dps_max=${"%.2f".format(poseStillMax)}")
            appendLine("mismatch_abs_mean_dps=${"%.2f".format(misAbsSum / n)}")
            appendLine("rest_trans=$restTrans grav_trans=$gravTrans jitter_events=$jitterEvents drift_events=$driftEvents")
            if (pose != null) {
                appendLine("last_ypr=${"%.2f".format(pose.yawDeg)},${"%.2f".format(pose.pitchDeg)},${"%.2f".format(pose.rollDeg)}")
            }
            if (bias != null) {
                appendLine("bias_dps=${"%.2f".format(bias.x)},${"%.2f".format(bias.y)},${"%.2f".format(bias.z)} rate_ema=${"%.2f".format(rateEma)}")
            }
            appendLine("issues=${issues.joinToString("|")}")
            appendLine("files=imu_trace.csv,imu_events.csv,imu_summary.txt")
            appendLine("HOW_TO_READ=still_obj_jump=on-screen wobble while gyro quiet; DRIFT=pose moves during rest freeze; REST_CHATTER=freeze gate flapping; GRAV=horizon pull")
        }
        ioExecutor.submit {
            runCatching {
                dir.mkdirs()
                summaryFile.writeText(text)
            }
        }
        Log.i(LogTags.SUMMARY, text.replace("\n", " · "))
        // Reset event counters each summary window so counts are per-interval.
        restTrans = 0
        gravTrans = 0
        jitterEvents = 0
        driftEvents = 0
    }

    private fun scheduleWriteLocked() {
        val traceSnapshot = TRACE_HEADER + lines.joinToString("\n", postfix = "\n")
        val eventSnapshot = EVENT_HEADER + events.joinToString("\n", postfix = "\n")
        ioExecutor.submit {
            runCatching {
                dir.mkdirs()
                traceFile.writeText(traceSnapshot)
                eventFile.writeText(eventSnapshot)
            }.onFailure {
                Log.w(LogTags.TRACE, "async trace write failed ${it.message}")
            }
        }
    }

    companion object {
        const val TRACE_HEADER =
            "n,host_ms,dt_ms,host_dt_ms,gx,gy,gz,gyro_mag,cx,cy,cz,corr_mag,pose_dps,mismatch," +
                "ax,ay,az,accel_mag,accel_err,yaw,pitch,roll,qw,qx,qy,qz," +
                "dqw,dqx,dqy,dqz,hold,lag_deg," +
                "obj_x,obj_y,obj_z,obj_dx,obj_dy,obj_jump," +
                "bx,by,bz,gain,rest,still_n,rate_ema,grav,rest_enter,rest_exit_hard,grav_gain,panel_m,version\n"
        const val EVENT_HEADER = "n,host_ms,kind,detail\n"

        private const val DECIMATE = 20 // 1000 → 50 Hz
        private const val MAX = 4500 // 90 s
        private const val EVENT_MAX = 800
        private const val FLUSH_EVERY = 25
        private const val STATS_WIN = 500 // 10 s
        private const val SUMMARY_EVERY_MS = 5000L
        private const val JITTER_GYRO_MAX = 3f
        private const val JITTER_OBJ_MIN = 0.012f // ~1.2 cm at panel in view-space units
        private const val DRIFT_POSE_DPS = 2.5f
        private const val MISMATCH_DPS = 25f

        private fun mag3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

        private fun quatAngleDeg(
            aw: Float, ax: Float, ay: Float, az: Float,
            bw: Float, bx: Float, by: Float, bz: Float,
        ): Float {
            var d = aw * bw + ax * bx + ay * by + az * bz
            if (d < 0f) d = -d
            if (d > 1f) d = 1f
            return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
        }

        private fun wrapDeg(d: Float): Float {
            var x = d
            while (x > 180f) x -= 360f
            while (x < -180f) x += 360f
            return x
        }
    }
}
