package org.opennebula.air.xr

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Listens for PC companion UDP beacons on [XrBridgeProtocol.DISCOVERY_PORT]. */
class XrDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHosts: (List<XrHost>) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val hosts = ConcurrentHashMap<String, Pair<XrHost, Long>>()
    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("opennebula-xr")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(XrBridgeProtocol.DISCOVERY_PORT))
                soTimeout = 800
            }
            socket = sock
            onLog("listening for PC bridge on :${XrBridgeProtocol.DISCOVERY_PORT}")
            val buf = ByteArray(512)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val parsed = XrBridgeProtocol.parseBeacon(text) ?: continue
                    val host = parsed.copy(address = packet.address.hostAddress ?: continue)
                    hosts[host.id] = host to System.currentTimeMillis()
                    prune()
                    onHosts(hosts.values.map { it.first }.sortedBy { it.name })
                } catch (_: Exception) {
                    prune()
                    onHosts(hosts.values.map { it.first }.sortedBy { it.name })
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        hosts.clear()
        onHosts(emptyList())
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        hosts.entries.removeIf { now - it.value.second > 5_000L }
    }
}
