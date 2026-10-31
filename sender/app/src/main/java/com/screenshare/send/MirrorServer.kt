package com.screenshare.send

import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Serves the live MPEG-TS mirror stream at http://<phone>:8080/live.ts,
 * same shape as the vendor sender app so any DLNA renderer can play it.
 * Multiple clients allowed; each gets the stream from the next keyframe.
 */
object MirrorServer {
    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()

    /** Latest PAT+PMT pair, resent to every new client. */
    @Volatile var patPmt: ByteArray? = null
    @Volatile var running = false

    fun start() {
        if (running) return
        executor.execute {
            try {
                val ss = ServerSocket(8080, 16, java.net.InetAddress.getByName("0.0.0.0"))
                server = ss
                running = true
                while (running && !ss.isClosed) {
                    val s = ss.accept()
                    executor.execute { handle(s) }
                }
            } catch (_: Exception) { running = false }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        for (c in clients) { try { c.close() } catch (_: Exception) {} }
        clients.clear()
        server = null
    }

    fun localIp(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (!name.startsWith("wlan")) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            // read request head
            val buf = ByteArray(4096)
            var read = 0
            var head = 0
            while (head < buf.size) {
                val n = input.read(buf, head, buf.size - head)
                if (n < 0) return
                head += n
                val s = String(buf, 0, head, Charsets.ISO_8859_1)
                if (s.contains("\r\n\r\n")) break
                read = head
            }
            val req = String(buf, 0, head, Charsets.ISO_8859_1)
            if (!req.contains("/live.ts")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); sock.close(); return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            clients.add(out)
            // block until client goes away
            try { input.read(ByteArray(1)) } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            clients.remove(sock.getOutputStream())
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Broadcast PAT/PMT to current clients (called when a new client joins or stream restarts). */
    fun broadcastInit(patPmtBytes: ByteArray) {
        patPmt = patPmtBytes
        for (c in clients) { try { c.write(patPmtBytes); c.flush() } catch (_: Exception) { clients.remove(c) } }
    }

    fun broadcast(ts: ByteArray) {
        for (c in clients) { try { c.write(ts); c.flush() } catch (_: Exception) { clients.remove(c) } }
    }
}
