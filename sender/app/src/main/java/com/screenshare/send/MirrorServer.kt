package com.screenshare.send

import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Serves the live MPEG-TS mirror stream at http://<phone>:8080/live.ts,
 * same shape as the vendor sender app so any DLNA renderer can play it.
 * Multiple clients allowed; each gets the stream from the next keyframe.
 */
object MirrorServer {
    private class Sink(val out: OutputStream) {
        var primed = false
    }

    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Sink>()
    private val writeLock = Any()

    /** Latest PAT+PMT pair, resent on each keyframe so a joiner can start decoding. */
    @Volatile var patPmt: ByteArray? = null
    @Volatile var running = false

    /** Shared secret for this mirror session. The cast URL must carry `k=<token>`. */
    @Volatile var streamToken: String = ""
        private set

    fun start() {
        if (running) return
        if (streamToken.isEmpty()) {
            val raw = ByteArray(16)
            SecureRandom().nextBytes(raw)
            streamToken = raw.joinToString("") { "%02x".format(it) }
        }
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
        streamToken = ""
        try { server?.close() } catch (_: Exception) {}
        synchronized(writeLock) {
            for (c in clients) { try { c.out.close() } catch (_: Exception) {} }
            clients.clear()
        }
        server = null
        patPmt = null
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
        var sink: Sink? = null
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
            val target = req.lineSequence().firstOrNull()
                ?.substringAfter(' ')?.substringBefore(' ').orEmpty()
            val path = target.substringBefore('?')
            if (path != "/live.ts") {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); sock.close(); return
            }
            val token = streamToken
            val authed = token.isNotEmpty() &&
                target.substringAfter('?', "").split('&').any { it == "k=$token" }
            if (!authed) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); sock.close(); return
            }
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            out.flush()
            sink = Sink(out)
            synchronized(writeLock) { clients.add(sink!!) }
            // block until client goes away
            try { input.read(ByteArray(1)) } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            sink?.let { s -> synchronized(writeLock) { clients.remove(s) } }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Remember PAT/PMT. Callers resend it in front of every keyframe. */
    fun broadcastInit(patPmtBytes: ByteArray) {
        patPmt = patPmtBytes
    }

    /**
     * Write one TS burst. Video and audio call this from different threads;
     * the lock keeps 188-byte packets intact. A client stays silent until the
     * next keyframe, which is prefixed with PAT/PMT so the decoder can join.
     */
    fun broadcast(ts: ByteArray, keyframe: Boolean = false) {
        synchronized(writeLock) {
            val dead = ArrayList<Sink>()
            for (c in clients) {
                try {
                    if (keyframe) {
                        patPmt?.let { c.out.write(it) }
                        c.primed = true
                    }
                    if (!c.primed) continue
                    c.out.write(ts)
                    c.out.flush()
                } catch (_: Exception) {
                    dead.add(c)
                }
            }
            if (dead.isNotEmpty()) clients.removeAll(dead)
        }
    }
}
