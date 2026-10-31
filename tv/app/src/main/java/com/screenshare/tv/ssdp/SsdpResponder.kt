package com.screenshare.tv.ssdp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Answers SSDP M-SEARCH probes so senders can discover this renderer.
 * UPnP 1.0 subset: MediaRenderer device, single AVTransport service.
 */
class SsdpResponder(
    private val friendlyName: String,
    private val httpPort: Int,
    private val uuid: String
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        executor.execute {
            try {
                val ss = DatagramSocket(1900, InetAddress.getByName("239.255.255.250"))
                ss.reuseAddress = true
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
                    val stReply = if (st == "ssdp:all" || st == "upnp:rootdevice")
                        "urn:schemas-upnp-org:device:MediaRenderer:1" else st
                    val resp = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("EXT:\r\n")
                        append("LOCATION: http://${pkt.address.hostAddress}:$httpPort/description.xml\r\n")
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
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
