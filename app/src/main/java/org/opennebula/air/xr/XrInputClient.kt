package org.opennebula.air.xr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent control TCP for remote-desktop pointer/keys (and one-shot DESKTOP/LAUNCH).
 * TCP_NODELAY + fire-and-forget lines for minimal click latency.
 */
class XrInputClient(
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {
    private val lock = Any()
    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private var job: Job? = null
    private val connected = AtomicBoolean(false)
    private val lastPtr = AtomicReference("")

    fun isConnected(): Boolean = connected.get()

    fun connect(address: String, port: Int = XrBridgeProtocol.CONTROL_PORT) {
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(address, port), 3_000)
                synchronized(lock) {
                    socket = sock
                    out = BufferedOutputStream(sock.getOutputStream(), 256)
                    connected.set(true)
                }
                sendLine("DESKTOP")
                onLog("input → $address:$port")
                // Keep socket alive; drain OK/ERR quietly.
                val buf = ByteArray(256)
                while (isActive && connected.get()) {
                    val n = runCatching { sock.getInputStream().read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                }
            } catch (err: Exception) {
                if (isActive) onLog("input ended: ${err.message}")
            } finally {
                disconnect()
            }
        }
    }

    fun pointer(u: Float, v: Float, buttons: Int = 0, wheel: Int = 0) {
        val line = "PTR ${"%.4f".format(u)} ${"%.4f".format(v)} $buttons $wheel"
        // Drop duplicate move-only packets to cut Wi‑Fi chatter.
        if (buttons == 0 && wheel == 0 && lastPtr.get() == line) return
        lastPtr.set(line)
        sendLine(line)
    }

    fun click(u: Float, v: Float, button: Int = 0) {
        sendLine("CLICK ${"%.4f".format(u)} ${"%.4f".format(v)} $button")
    }

    fun key(vk: Int, down: Boolean) {
        sendLine("KEY ${if (down) "down" else "up"} $vk")
    }

    fun disconnect() {
        connected.set(false)
        job?.cancel()
        job = null
        synchronized(lock) {
            runCatching { out?.flush() }
            runCatching { socket?.close() }
            out = null
            socket = null
        }
        lastPtr.set("")
    }

    private fun sendLine(line: String) {
        synchronized(lock) {
            val stream = out ?: return
            runCatching {
                stream.write("$line\n".toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }
}
