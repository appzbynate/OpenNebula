package org.opennebula.air.render

import org.opennebula.air.imu.Orientation
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Display-only pose gate (1€ family, still-hold variant).
 *
 * Soft chase + catch-up hunts and reads as icons drifting back/forth.
 * Instead: **freeze the drawn quat while the head is still** (kills heartbeat),
 * track nearly raw while moving. Fusion / Anchor / gravity stay untouched.
 * Never pair with prediction look-ahead.
 */
class PoseSmoother(
    private val minCutoffHz: Float = 12f,
    private val beta: Float = 0.08f,
    private val dCutoffHz: Float = 2.0f,
    private val stillDps: Float = 6f,
    private val moveDps: Float = 10f,
) {
    private var primed = false
    private var lastNs = 0L
    private var holding = false
    private var w = 1f
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var pw = 1f
    private var px = 0f
    private var py = 0f
    private var pz = 0f
    private var speedHat = 0f
    private var lastLagDeg = 0f

    fun isHolding(): Boolean = holding
    fun lastLagDeg(): Float = lastLagDeg
    fun lastSpeedHatDps(): Float = speedHat

    fun reset(pose: Orientation) {
        w = pose.qw
        x = pose.qx
        y = pose.qy
        z = pose.qz
        normalizeDisplay()
        pw = w
        px = x
        py = y
        pz = z
        speedHat = 0f
        holding = false
        lastNs = 0L
        primed = false
    }

    /** Filtered display orientation; [pose] stays the raw fusion sample. */
    fun step(pose: Orientation, nowNs: Long = System.nanoTime()): Orientation {
        if (!primed || lastNs == 0L) {
            reset(pose)
            lastNs = nowNs
            primed = true
            holding = true
            return Orientation(pose.yawDeg, pose.pitchDeg, pose.rollDeg, w, x, y, z)
        }
        val rawDt = (nowNs - lastNs) * 1e-9f
        lastNs = nowNs
        val dt = rawDt.coerceIn(MIN_DT, MAX_DT)

        var tw = pose.qw
        var tx = pose.qx
        var ty = pose.qy
        var tz = pose.qz
        if (dot(pw, px, py, pz, tw, tx, ty, tz) < 0f) {
            tw = -tw; tx = -tx; ty = -ty; tz = -tz
        }
        val rawDelta = angleDeg(pw, px, py, pz, tw, tx, ty, tz)
        pw = tw; px = tx; py = ty; pz = tz

        var dw = tw
        var dx = tx
        var dy = ty
        var dz = tz
        if (dot(w, x, y, z, dw, dx, dy, dz) < 0f) {
            dw = -dw; dx = -dx; dy = -dy; dz = -dz
        }
        val lagDeg = angleDeg(w, x, y, z, dw, dx, dy, dz)
        lastLagDeg = lagDeg

        if (lagDeg > SNAP_DEG) {
            w = dw; x = dx; y = dy; z = dz
            normalizeDisplay()
            speedHat = 0f
            lastLagDeg = 0f
            holding = true
            return Orientation(pose.yawDeg, pose.pitchDeg, pose.rollDeg, w, x, y, z)
        }

        val speed = rawDelta / dt
        val ad = alpha(dt, dCutoffHz)
        speedHat += (speed - speedHat) * ad

        // Enter hold when quiet; leave when rate rises or lag proves a real move.
        if (holding) {
            if (speedHat >= moveDps || lagDeg >= BREAK_HOLD_DEG) {
                holding = false
            } else {
                return Orientation(pose.yawDeg, pose.pitchDeg, pose.rollDeg, w, x, y, z)
            }
        } else if (speedHat < stillDps && lagDeg < HOLD_ALIGN_DEG) {
            // Snap onto current raw once, then freeze — no soft chase.
            w = dw; x = dx; y = dy; z = dz
            normalizeDisplay()
            holding = true
            return Orientation(pose.yawDeg, pose.pitchDeg, pose.rollDeg, w, x, y, z)
        }

        val fc = (minCutoffHz + beta * speedHat).coerceAtMost(MAX_CUTOFF_HZ)
        val t = alpha(dt, fc).coerceIn(0f, 1f)
        val q = PoseMath.slerpQuat(w, x, y, z, dw, dx, dy, dz, t)
        w = q[0]; x = q[1]; y = q[2]; z = q[3]
        normalizeDisplay()
        return Orientation(pose.yawDeg, pose.pitchDeg, pose.rollDeg, w, x, y, z)
    }

    private fun normalizeDisplay() {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-12f) {
            w = 1f; x = 0f; y = 0f; z = 0f
            return
        }
        val inv = 1f / n
        w *= inv; x *= inv; y *= inv; z *= inv
    }

    companion object {
        private const val MIN_DT = 0.0004f
        private const val MAX_DT = 0.05f
        private const val SNAP_DEG = 35f
        private const val MAX_CUTOFF_HZ = 90f
        /** Lag that means "user moved while we were holding" — break freeze. */
        private const val BREAK_HOLD_DEG = 0.55f
        /** Only enter hold once we are already aligned (no leftover crawl). */
        private const val HOLD_ALIGN_DEG = 0.35f

        fun wrapDeg(delta: Float): Float {
            var d = delta
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return d
        }

        internal fun alpha(dt: Float, cutoffHz: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(0.05f))
            return 1f - exp(-dt / tau)
        }

        private fun dot(
            aw: Float, ax: Float, ay: Float, az: Float,
            bw: Float, bx: Float, by: Float, bz: Float,
        ) = aw * bw + ax * bx + ay * by + az * bz

        private fun angleDeg(
            aw: Float, ax: Float, ay: Float, az: Float,
            bw: Float, bx: Float, by: Float, bz: Float,
        ): Float {
            var d = dot(aw, ax, ay, az, bw, bx, by, bz)
            if (d < 0f) d = -d
            if (d > 1f) d = 1f
            return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
        }
    }
}
