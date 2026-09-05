package org.opennebula.air.render

import org.opennebula.air.shell.SpatialWindow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class PanelHit(
    val layerId: String,
    val u: Float,
    val v: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val t: Float,
)

data class PointerBeam(
    val hitX: Float,
    val hitY: Float,
    val hitZ: Float,
    val active: Boolean,
)

object PanelPick {
    fun rayFromYawPitchDeg(yawDeg: Float, pitchDeg: Float): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        return floatArrayOf(sy * cp, -sp, -cy * cp)
    }

    fun pick(
        layers: List<Pair<String, SpatialWindow>>,
        dir: FloatArray,
        originX: Float = 0f,
        originY: Float = 0f,
        originZ: Float = 0f,
    ): PanelHit? {
        var best: PanelHit? = null
        for ((id, window) in layers) {
            val hit = intersect(originX, originY, originZ, dir, id, window) ?: continue
            if (hit.u !in 0f..1f || hit.v !in 0f..1f) continue
            if (best == null || hit.t < best.t) best = hit
        }
        return best
    }

    fun intersect(
        originX: Float,
        originY: Float,
        originZ: Float,
        dir: FloatArray,
        layerId: String,
        window: SpatialWindow,
    ): PanelHit? {
        val n = window.normal()
        val denom = n[0] * dir[0] + n[1] * dir[1] + n[2] * dir[2]
        if (abs(denom) < 1e-5f) return null
        val t = (
            (window.x - originX) * n[0] +
                (window.y - originY) * n[1] +
                (window.z - originZ) * n[2]
            ) / denom
        if (t < 0.05f) return null
        val x = originX + dir[0] * t
        val y = originY + dir[1] * t
        val z = originZ + dir[2] * t
        val local = window.worldToLocal(x, y, z)
        val u = local[0] / window.width + 0.5f
        val v = 0.5f - local[1] / window.height
        return PanelHit(layerId, u, v, x, y, z, t)
    }
}
