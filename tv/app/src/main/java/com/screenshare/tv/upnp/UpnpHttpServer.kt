package com.screenshare.tv.upnp

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Rendered state consumed by the player UI. */
data class PlaybackRequest(val uri: String, val title: String, val metaData: String?)

/** Minimal UPnP device-description + AVTransport SOAP server (zero dependencies). */
class UpnpHttpServer(
    private val friendlyName: String,
    private val httpPort: Int,
    private val uuid: String,
    private val onRequest: (PlaybackRequest) -> Unit,
    private val onControl: (action: String) -> Boolean  // Play/Pause/Stop → handled by player
) {
    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var lastPositionSec = 0
    @Volatile var durationSec = 0

    fun localIp(): String? {
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
        return null
    }

    fun start() {
        if (running) return
        executor.execute {
            try {
                val ss = ServerSocket(httpPort, 16, java.net.InetAddress.getByName("0.0.0.0"))
                server = ss
                running = true
                while (running) {
                    val client = ss.accept()
                    executor.execute { handle(client) }
                }
            } catch (_: Exception) { running = false }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 10000
                // Content-Length is a byte count. Reading that many UTF-8 characters
                // blocks forever once a title contains a multibyte character (Chinese
                // filenames), because the sender has already finished the body.
                val input = s.getInputStream()
                val headerText = readHttpHeaders(input) ?: return
                val lines = headerText.split('\n').map { it.trimEnd('\r') }
                val requestLine = lines.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
                var contentLength = 0
                var soapAction = ""
                for (line in lines) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                    if (line.lowercase().startsWith("soapaction:")) {
                        soapAction = line.substringAfter(':').trim()
                    }
                }
                if (contentLength < 0) contentLength = 0
                if (contentLength > 256 * 1024) contentLength = 256 * 1024
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                val out = s.getOutputStream()
                android.util.Log.i("UpnpServer", "req: $requestLine action-soap known")
                when {
                    requestLine.contains("GET /description.xml") -> {
                        android.util.Log.i("UpnpServer", "serving description")
                        val bytes = descriptionXml().toByteArray(Charsets.UTF_8)
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(bytes)
                        out.flush()
                    }
                    requestLine.contains("POST") && requestLine.contains("AVTransport_control") -> {
                        android.util.Log.i("UpnpServer", "soap action=$soapAction")
                        val action = Regex("#([A-Za-z]+)").find(soapAction)?.groupValues?.get(1)
                            ?: Regex("<u:([A-Za-z]+) xmlns").find(body)?.groupValues?.get(1) ?: ""
                        handleAction(action, body, out)
                    }
                    else -> {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Read until `\r\n\r\n` or `\n\n`. One byte at a time so the body stays in the
     * socket, tracking only the previous three bytes instead of copying the buffer.
     */
    private fun readHttpHeaders(input: java.io.InputStream): String? {
        val headerBytes = ByteArrayOutputStream()
        var p1 = -1
        var p2 = -1
        var p3 = -1
        var complete = false
        while (headerBytes.size() < 65536) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            val lfLf = p3 == '\n'.code && b == '\n'.code
            val crlfCrlf = p1 == '\r'.code && p2 == '\n'.code && p3 == '\r'.code && b == '\n'.code
            if (lfLf || crlfCrlf) {
                complete = true
                break
            }
            p1 = p2
            p2 = p3
            p3 = b
        }
        if (!complete) return null
        return headerBytes.toString(Charsets.ISO_8859_1)
    }

    private fun handleAction(action: String, body: String, out: java.io.OutputStream) {
        val response: String = when (action) {
            "SetAVTransportURI" -> {
                val uri = tag(body, "CurrentURI")?.let { xmlUnescape(it) } ?: ""
                val meta = tag(body, "CurrentURIMetaData")?.let { xmlUnescape(it) } ?: ""
                val title = Regex("<dc:title>([^<]*)</dc:title>").find(meta)?.groupValues?.get(1) ?: "投屏内容"
                if (uri.isNotEmpty()) {
                    onRequest(PlaybackRequest(uri, title, meta))
                    soapOk("SetAVTransportURI")
                } else soapError("SetAVTransportURI", "402", "Invalid CurrentURI")
            }
            "Play" -> { onControl("Play"); soapOk("Play") }
            "Pause" -> { onControl("Pause"); soapOk("Pause") }
            "Stop" -> { onControl("Stop"); soapOk("Stop") }
            "Seek" -> { onControl("Seek:" + (tag(body, "Target") ?: "")); soapOk("Seek") }
            "GetPositionInfo" -> {
                fun fmt(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
                """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>${fmt(durationSec)}</TrackDuration><TrackMetaData></TrackMetaData><TrackURI></TrackURI><RelTime>${fmt(lastPositionSec)}</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
            }
            "GetTransportInfo" -> """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse></s:Body></s:Envelope>"""
            else -> soapError(action, "401", "Invalid Action")
        }
        val bytes = response.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes); out.flush()
    }

    private fun tag(body: String, name: String): String? =
        Regex("<$name>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.get(1)?.trim()

    private fun xmlUnescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun soapOk(action: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"></u:${action}Response></s:Body></s:Envelope>"""

    private fun soapError(action: String, code: String, msg: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>$msg</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

    private fun descriptionXml(): String {
        val ip = localIp() ?: "127.0.0.1"
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://$ip:$httpPort</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$friendlyName</friendlyName><manufacturer>ScreenShare</manufacturer><modelName>ScreenShareTV</modelName><UDN>uuid:$uuid</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/avtransport.xml</SCPDURL><controlURL>/_urn:schemas-upnp-org:service:AVTransport_control</controlURL><eventSubURL>/_urn:schemas-upnp-org:service:AVTransport_event</eventSubURL></service></serviceList></device></root>"""
    }
}
