package org.opennebula.air.xr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opennebula.air.mirror.MirrorPipeline
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP MJPEG client: length-prefixed JPEG frames from the PC companion.
 * Frame header: u32 LE magic 'JFIF' / 'ONXR', u32 LE byte length, then JPEG bytes.
 */
class XrVideoClient(
    private val scope: CoroutineScope,
    private val pipeline: MirrorPipeline,
    private val onLog: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    private val onFailed: (String) -> Unit = {},
) {
    private var job: Job? = null

    fun start(address: String, port: Int = XrBridgeProtocol.VIDEO_PORT) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            onStatus("connecting video…")
            try {
                Socket().use { sock ->
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(address, port), 4_000)
                    onStatus("video linked")
                    onLog("video ← $address:$port")
                    pipeline.setActive(true)
                    val input = DataInputStream(sock.getInputStream())
                    val header = ByteArray(8)
                    while (isActive) {
                        input.readFully(header)
                        val magic = ((header[0].toInt() and 0xff)) or
                            ((header[1].toInt() and 0xff) shl 8) or
                            ((header[2].toInt() and 0xff) shl 16) or
                            ((header[3].toInt() and 0xff) shl 24)
                        val len = ((header[4].toInt() and 0xff)) or
                            ((header[5].toInt() and 0xff) shl 8) or
                            ((header[6].toInt() and 0xff) shl 16) or
                            ((header[7].toInt() and 0xff) shl 24)
                        if (magic != 0x52584E4F && magic != 0x4649464A) {
                            onLog("bad video magic=$magic")
                            break
                        }
                        if (len <= 0 || len > 8_000_000) {
                            onLog("bad frame len=$len")
                            break
                        }
                        val jpeg = ByteArray(len)
                        input.readFully(jpeg)
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                        pipeline.pushBitmap(bmp)
                    }
                }
                if (isActive) onFailed("Video stream ended — is the PC companion still running?")
            } catch (err: Exception) {
                if (isActive) {
                    onLog("video ended: ${err.message}")
                    onFailed(
                        "Can't reach PC video :$port — run tools\\xr-bridge\\start_bridge.bat " +
                            "(${err.message})",
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
