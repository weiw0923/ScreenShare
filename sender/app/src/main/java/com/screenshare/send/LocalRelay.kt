package com.screenshare.send

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Streams a SAF (content://) Uri over local HTTP so the TV renderer can read it.
 * Supports HTTP Range requests so the player can seek.
 */
object LocalRelay {
    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var relayUri: Uri? = null
    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context, uri: Uri, onReady: (String) -> Unit) {
        stop()
        appContext = context.applicationContext
        relayUri = uri
        executor.execute {
            try {
                val ss = ServerSocket(0, 8, java.net.InetAddress.getByName("0.0.0.0"))
                server = ss
                val port = ss.localPort
                val ip = localWifiIp() ?: "127.0.0.1"
                onReady("http://$ip:$port/media")
                while (!ss.isClosed) {
                    val client = ss.accept()
                    executor.execute { handle(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                var rangeStart: Long = 0
                var rangeEndInclusive: Long = -1
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("range:")) {
                        val m = Regex("bytes=(\\d*)-(\\d*)").find(line)
                        if (m != null) {
                            rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                            rangeEndInclusive = m.groupValues[2].toLongOrNull() ?: -1L
                        }
                    }
                }
                if (!requestLine.contains("GET") && !requestLine.contains("HEAD")) return

                val ctx = appContext ?: return
                val uri = relayUri ?: return
                val size = querySize(ctx, uri)

                val out = sock.getOutputStream()
                if (requestLine.contains("HEAD")) {
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${contentTypeFor(uri)}\r\n")
                        if (size > 0) append("Content-Length: $size\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(headers.toByteArray(Charsets.ISO_8859_1)); out.flush()
                    return
                }

                val start = if (rangeStart > 0) rangeStart else 0L
                val end = if (rangeEndInclusive >= 0) rangeEndInclusive else (if (size > 0) size - 1 else Long.MAX_VALUE / 4)
                val partial = rangeStart > 0 || (size > 0 && end < size - 1)

                val headers = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${contentTypeFor(uri)}\r\n")
                    if (size > 0 && end != Long.MAX_VALUE / 4) {
                        append("Content-Range: bytes $start-$end/$size\r\n")
                        append("Content-Length: ${end - start + 1}\r\n")
                    } else {
                        append("Connection: close\r\n")
                    }
                    append("Accept-Ranges: bytes\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.ISO_8859_1)); out.flush()

                val input = BufferedInputStream(ctx.contentResolver.openInputStream(uri) ?: return)
                var skipped = 0L
                val skipBuf = ByteArray(65536)
                while (skipped < start) {
                    val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), start - skipped).toInt())
                    if (n < 0) break
                    skipped += n
                }
                var remaining = if (end == Long.MAX_VALUE / 4) Long.MAX_VALUE else end - start + 1
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.flush()
                input.close()
            } catch (_: Exception) {
            }
        }
    }

    fun contentTypeFor(uri: Uri): String {
        val n = (uri.lastPathSegment ?: "").lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp3") || n.endsWith(".m4a") -> "audio/mpeg"
            n.endsWith(".flac") -> "audio/flac"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    private fun querySize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    private fun localWifiIp(): String? {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
