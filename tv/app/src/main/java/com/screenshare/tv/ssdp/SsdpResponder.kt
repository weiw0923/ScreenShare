package com.screenshare.tv.ssdp

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Answers SSDP M-SEARCH probes so senders can discover this renderer.
 * UPnP 1.0 subset: MediaRenderer device, single AVTransport service.
 */
class SsdpResponder(
    private val context: Context,
    private val friendlyName: String,
    private val httpPort: Int,
    private val uuid: String
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        // Many Wi-Fi drivers drop multicast unless some app holds a MulticastLock.
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("ScreenShareSsdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        executor.execute {
            var ss: MulticastSocket? = null
            try {
                // Bind the wildcard address, not 239.255.255.250. A socket bound to
                // the group address answers with that group as its source, and the
                // old LOCATION used the searcher's address, so phones fetched the
                // description from themselves and never saw this renderer.
                ss = MulticastSocket(null)
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(1900))
                val group = InetAddress.getByName("239.255.255.250")
                var joined = false
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    try {
                        ss.joinGroup(InetSocketAddress(group, 1900), nif)
                        joined = true
                    } catch (_: Exception) {
                    }
                }
                if (!joined) ss.joinGroup(group)
                socket = ss
                running = true
                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    ss.receive(pkt)
                    val req = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                    if (!req.startsWith("M-SEARCH", true)) continue
                    val st = Regex("ST:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE)
                        .find(req)?.groupValues?.get(1)?.trim() ?: continue
                    val respond = st == "ssdp:all" ||
                        st == "upnp:rootdevice" ||
                        st == "urn:schemas-upnp-org:device:MediaRenderer:1" ||
                        st == "urn:schemas-upnp-org:service:AVTransport:1" ||
                        st == uuid
                    if (!respond) continue
                    val host = localIpv4Toward(pkt.address) ?: continue
                    val stReply = if (st == "ssdp:all" || st == "upnp:rootdevice")
                        "urn:schemas-upnp-org:device:MediaRenderer:1" else st
                    val resp = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("EXT:\r\n")
                        append("LOCATION: http://$host:$httpPort/description.xml\r\n")
                        append("SERVER: Android/ScreenShare UPnP/1.0 ScreenShareTV/1.0\r\n")
                        append("ST: $stReply\r\n")
                        append("USN: $uuid::$stReply\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.ISO_8859_1)
                    // MX jitter: reply after a small random delay to avoid answer storms
                    Thread.sleep((0..120).random().toLong())
                    ss.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                }
            } catch (_: Exception) { running = false }
            finally { try { ss?.close() } catch (_: Exception) {} }
        }
    }

    /** IPv4 of the interface that would answer `remote`. LOCATION must be this host. */
    private fun localIpv4Toward(remote: InetAddress): String? {
        try {
            DatagramSocket().use { probe ->
                probe.connect(remote, 9)
                val local = probe.localAddress
                if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
                    return local.hostAddress
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
