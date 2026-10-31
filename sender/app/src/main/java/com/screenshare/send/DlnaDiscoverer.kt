package com.screenshare.send

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** Device found on the local network via SSDP. */
data class CastDevice(val friendlyName: String, val descriptionUrl: String, val ip: String)

/**
 * Minimal SSDP control point: M-SEARCH for media renderers, parse LOCATION headers.
 */
object DlnaDiscoverer {
    private val executor = Executors.newSingleThreadExecutor()

    fun search(timeoutMs: Int, onResult: (List<CastDevice>) -> Unit) {
        executor.execute {
            val found = LinkedHashMap<String, CastDevice>()
            try {
                val msearch = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(java.net.InetSocketAddress(0))
                    sock.broadcast = true
                    sock.soTimeout = 300
                    val group = InetAddress.getByName("239.255.255.250")
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(4096)
                    val sts = listOf(
                        "urn:schemas-upnp-org:device:MediaRenderer:1",
                        "ssdp:all"
                    )
                    var sent = 0
                    while (System.currentTimeMillis() < deadline) {
                        for (st in sts) {
                            val m = ("M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 1\r\n" +
                                "ST: $st\r\n\r\n").toByteArray(Charsets.US_ASCII)
                            sock.send(DatagramPacket(m, m.size, group, 1900))
                            sent++
                        }
                        try {
                            while (System.currentTimeMillis() < deadline) {
                                val pkt = DatagramPacket(buf, buf.size)
                                sock.receive(pkt)
                                val text = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                                parse(text, pkt.address.hostAddress ?: "")?.let { dev ->
                                    found.putIfAbsent(dev.descriptionUrl, dev)
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            onResult(found.values.toList())
        }
    }

    /** Extract USN + LOCATION from an SSDP response; renderer-ness confirmed later via description. */
    private fun parse(response: String, ip: String): CastDevice? {
        if (!response.startsWith("HTTP/1.", true) && !response.startsWith("NOTIFY", true)) return null
        val location = response.lineValue("LOCATION") ?: return null
        return CastDevice(friendlyName = "", descriptionUrl = location, ip = ip)
    }

    private fun String.lineValue(header: String): String? {
        for (line in lines()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(header, true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }
}
