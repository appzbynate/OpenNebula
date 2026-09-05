package org.opennebula.air.imu

data class Vec3i(val x: Int, val y: Int, val z: Int) {
    override fun toString(): String = "$x,$y,$z"
}

data class Vec3f(val x: Float, val y: Float, val z: Float) {
    override fun toString(): String = "%.3f,%.3f,%.3f".format(x, y, z)
}

enum class ImuParserSource {
    MPU6050,
    AIR_PACKED,
}

data class ImuSample(
    val source: ImuParserSource,
    val timestampNs: Long,
    val temperatureRaw: Int?,
    val accelRaw: Vec3i,
    val gyroRaw: Vec3i,
    val accelMps2: Vec3f,
    val gyroDps: Vec3f,
    val pitchDeg: Float,
    val rollDeg: Float,
) {
    /** Gyro after packet mul/div, in rad/s for fusion. HUD keeps [gyroDps]. */
    val gyroRadS: Vec3f
        get() = Vec3f(
            Math.toRadians(gyroDps.x.toDouble()).toFloat(),
            Math.toRadians(gyroDps.y.toDouble()).toFloat(),
            Math.toRadians(gyroDps.z.toDouble()).toFloat(),
        )
}
