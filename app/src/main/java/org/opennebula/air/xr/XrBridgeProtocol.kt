package org.opennebula.air.xr

/**
 * OpenNebula XR Bridge protocol — open replacement for Nebula's CloudXR tile.
 *
 * Nebula streamed SteamVR via NVIDIA CloudXR (proprietary server on PC +
 * client APK on phone). We cannot ship that SDK; this bridge instead:
 *   phone → UDP pose → PC companion → OpenTrack / SteamVR driver
 *   PC companion → TCP MJPEG → phone → glasses panel
 *
 * Ports match tools/xr-bridge defaults.
 */
object XrBridgeProtocol {
    const val MAGIC = "ONXR"
    const val DISCOVERY_PORT = 38400
    const val POSE_PORT = 38401
    const val VIDEO_PORT = 38402
    const val CONTROL_PORT = 38403
    /** Primary-monitor MJPEG for floating Remote Desktop panel. */
    const val DESKTOP_VIDEO_PORT = 38404

    /** Beacon: ONXR|1|<hostname>|<posePort>|<videoPort>|<controlPort> */
    fun parseBeacon(text: String): XrHost? {
        val parts = text.trim().split('|')
        if (parts.size < 6) return null
        if (parts[0] != MAGIC) return null
        val ver = parts[1].toIntOrNull() ?: return null
        if (ver < 1) return null
        val name = parts[2].ifBlank { "PC" }
        val pose = parts[3].toIntOrNull() ?: POSE_PORT
        val video = parts[4].toIntOrNull() ?: VIDEO_PORT
        val control = parts[5].toIntOrNull() ?: CONTROL_PORT
        return XrHost(name = name, posePort = pose, videoPort = video, controlPort = control)
    }

    fun beacon(hostname: String, pose: Int = POSE_PORT, video: Int = VIDEO_PORT, control: Int = CONTROL_PORT): String {
        return "$MAGIC|1|$hostname|$pose|$video|$control"
    }
}

data class XrHost(
    val name: String,
    val address: String = "",
    val posePort: Int = XrBridgeProtocol.POSE_PORT,
    val videoPort: Int = XrBridgeProtocol.VIDEO_PORT,
    val controlPort: Int = XrBridgeProtocol.CONTROL_PORT,
) {
    val id: String get() = if (address.isBlank()) name else "$name@$address"
}
