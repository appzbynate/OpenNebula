package org.opennebula.air.xr

import org.opennebula.air.imu.Orientation
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * High-rate head pose uplink to the PC companion (SteamVR / OpenTrack).
 * Packet: magic u32 LE 'ONXR', seq u32, t_ns i64, qw..qz f32,
 * yawDeg, pitchDeg, rollDeg f32, heightM f32.
 */
class XrPoseStreamer {
    private val running = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private val target = AtomicReference<Pair<InetAddress, Int>?>(null)
    private var socket: DatagramSocket? = null
    @Volatile var heightM: Float = 1.6f
    @Volatile var onLog: ((String) -> Unit)? = null

    fun start(address: String, port: Int = XrBridgeProtocol.POSE_PORT) {
        stop()
        val addr = InetAddress.getByName(address)
        target.set(addr to port)
        socket = DatagramSocket()
        running.set(true)
        onLog?.invoke("pose → $address:$port")
    }

    fun stop() {
        running.set(false)
        target.set(null)
        runCatching { socket?.close() }
        socket = null
    }

    fun isRunning(): Boolean = running.get()

    fun send(pose: Orientation) {
        if (!running.get()) return
        val dest = target.get() ?: return
        val sock = socket ?: return
        val buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x52584E4F) // 'ONXR' LE
        buf.putInt(seq.incrementAndGet())
        buf.putLong(System.nanoTime())
        buf.putFloat(pose.qw)
        buf.putFloat(pose.qx)
        buf.putFloat(pose.qy)
        buf.putFloat(pose.qz)
        // Madgwick euler — PC FreeTrack uses these (not quat→euler).
        buf.putFloat(pose.yawDeg)
        buf.putFloat(pose.pitchDeg)
        buf.putFloat(pose.rollDeg)
        buf.putFloat(heightM)
        val packet = DatagramPacket(buf.array(), buf.position(), dest.first, dest.second)
        runCatching { sock.send(packet) }
    }
}
