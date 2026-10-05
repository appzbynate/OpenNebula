package org.opennebula.air.xr

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent control TCP for remote-desktop pointer/keys.
 *
 * Writes always run on a background [HandlerThread]. Calling [moveRel] from the
 * UI/trackpad thread used to hit NetworkOnMainThreadException — caught, socket
 * marked down, video kept working, trackpad looked dead.
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
    private val generation = AtomicInteger(0)
    private var sentRel = 0

    private val writerThread = HandlerThread("xr-input-writer").apply { start() }
    private val writer = Handler(writerThread.looper)
    private val refreshHz = AtomicInteger(90)

    fun isConnected(): Boolean = connected.get()

    fun setRefreshHz(hz: Int) {
        val rate = hz.coerceIn(60, 120)
        refreshHz.set(rate)
        if (connected.get()) {
            sendLine("REFRESH $rate")
        }
    }

    fun connect(address: String, port: Int = XrBridgeProtocol.CONTROL_PORT) {
        val myGen = generation.incrementAndGet()
        synchronized(lock) {
            runCatching { out?.flush() }
            runCatching { socket?.close() }
            out = null
            socket = null
            connected.set(false)
            job?.cancel()
            job = null
        }
        job = scope.launch(Dispatchers.IO) {
            val sock = Socket()
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(address, port), 3_000)
                if (generation.get() != myGen || !isActive) {
                    runCatching { sock.close() }
                    return@launch
                }
                synchronized(lock) {
                    if (generation.get() != myGen) {
                        runCatching { sock.close() }
                        return@launch
                    }
                    socket = sock
                    out = BufferedOutputStream(sock.getOutputStream(), 256)
                    connected.set(true)
                    sentRel = 0
                }
                onLog("input → $address:$port")
                Log.i(TAG, "connected $address:$port")
                // Must not write from this coroutine's caller context assumptions —
                // queue DESKTOP on the writer thread like every other command.
                sendLine("DESKTOP ${refreshHz.get()}")
                sendLine("REFRESH ${refreshHz.get()}")
                val buf = ByteArray(256)
                while (isActive && generation.get() == myGen) {
                    val n = runCatching { sock.getInputStream().read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    if (n > 0) {
                        val msg = String(buf, 0, n, Charsets.UTF_8).trim()
                        if (msg.contains("ERR", ignoreCase = true)) onLog("input: $msg")
                        else if (msg.isNotBlank()) Log.i(TAG, "server: $msg")
                    }
                }
            } catch (err: Exception) {
                if (isActive && generation.get() == myGen) {
                    onLog("input ended: ${err.message}")
                    Log.w(TAG, "input ended: ${err.message}")
                }
            } finally {
                synchronized(lock) {
                    if (generation.get() == myGen) {
                        connected.set(false)
                        if (socket === sock) {
                            runCatching { out?.flush() }
                            runCatching { sock.close() }
                            out = null
                            socket = null
                        }
                    }
                }
            }
        }
    }

    fun pointer(u: Float, v: Float, buttons: Int = 0, wheel: Int = 0) {
        val line = "PTR ${"%.4f".format(Locale.US, u)} ${"%.4f".format(Locale.US, v)} $buttons $wheel"
        if (buttons == 0 && wheel == 0 && lastPtr.get() == line) return
        lastPtr.set(line)
        sendLine(line)
    }

    fun moveRel(dx: Int, dy: Int, buttons: Int = -1) {
        if (dx == 0 && dy == 0 && buttons < 0) return
        if (!connected.get()) {
            if (sentRel % 45 == 0) {
                onLog("input drop REL — socket down")
                Log.w(TAG, "drop REL — socket down")
            }
            sentRel++
            return
        }
        val b = if (buttons < 0) "" else " $buttons"
        sendLine("REL $dx $dy$b")
        sentRel++
        if (sentRel == 1 || sentRel % 30 == 0) {
            onLog("input REL#$sentRel ${dx},${dy}")
            Log.i(TAG, "REL#$sentRel $dx,$dy")
        }
    }

    fun sendWheel(delta: Int) {
        if (delta == 0) return
        sendLine("WHEEL $delta")
    }

    fun sendHWheel(delta: Int) {
        if (delta == 0) return
        sendLine("HWHEEL $delta")
    }

    fun sendZoom(ticks: Int) {
        if (ticks == 0) return
        sendLine("ZOOM $ticks")
    }

    fun sendChord(vararg vk: Int) {
        if (vk.isEmpty()) return
        sendLine("CHORD ${vk.joinToString(" ")}")
    }

    fun click(u: Float, v: Float, button: Int = 0) {
        sendLine("CLICK ${"%.4f".format(Locale.US, u)} ${"%.4f".format(Locale.US, v)} $button")
    }

    fun key(vk: Int, down: Boolean) {
        sendLine("KEY ${if (down) "down" else "up"} $vk")
    }

    fun disconnect() {
        generation.incrementAndGet()
        writer.post {
            synchronized(lock) {
                runCatching {
                    out?.write("DESKTOP_END\n".toByteArray(Charsets.UTF_8))
                    out?.flush()
                }
                connected.set(false)
                runCatching { socket?.close() }
                out = null
                socket = null
                job?.cancel()
                job = null
            }
        }
        lastPtr.set("")
        sentRel = 0
    }

    private fun sendLine(line: String) {
        writer.post {
            synchronized(lock) {
                val stream = out
                if (stream == null) {
                    Log.w(TAG, "write skip (no stream): $line")
                    return@synchronized
                }
                runCatching {
                    stream.write("$line\n".toByteArray(Charsets.UTF_8))
                    stream.flush()
                }.onFailure {
                    onLog("input write fail: ${it.message}")
                    Log.w(TAG, "write fail: ${it.message}")
                    connected.set(false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "XrInput"
    }
}
