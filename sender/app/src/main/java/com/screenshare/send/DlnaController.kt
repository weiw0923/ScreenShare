package com.screenshare.send

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Parsed pieces of a MediaRenderer's device description. */
data class RendererEndpoints(
    val friendlyName: String,
    val avTransportControlUrl: String // absolute URL
)

/**
 * Tiny UPnP/DLNA AVTransport control point: fetch description, send SOAP actions.
 */
object DlnaController {
    private val executor = Executors.newSingleThreadExecutor()

    fun describe(descriptionUrl: String, cb: (RendererEndpoints?) -> Unit) {
        executor.execute {
            try {
                val xml = httpGet(descriptionUrl)
                val name = Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)
                    ?: "Unknown device"
                val base = Regex("<URLBase>([^<]+)</URLBase>").find(xml)?.groupValues?.get(1)
                    ?.trim()?.removeSuffix("/")
                    ?: descriptionUrl.substringBeforeLast('/')
                // Locate the AVTransport <service> block, then its controlURL inside it.
                // Order-independent and immune to minified XML (adjacent tags, no whitespace).
                val svcBlock = Regex(
                    "<service>(?:(?!</service>).)*?AVTransport:1(?:(?!</service>).)*?</service>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(xml)?.value
                val control = svcBlock?.let {
                    Regex("<controlURL>([^<]+)</controlURL>").find(it)?.groupValues?.get(1)?.trim()
                }
                if (control == null) { cb(null); return@execute }
                val abs = if (control.startsWith("http")) control else "$base/${control.removePrefix("/")}"
                cb(RendererEndpoints(name, abs))
            } catch (_: Exception) {
                cb(null)
            }
        }
    }

    fun setUriAndPlay(endpoints: RendererEndpoints, mediaUrl: String, title: String, cb: (Boolean) -> Unit) {
        executor.execute {
            val uriEsc = xmlEscape(mediaUrl)
            val meta = xmlEscape(title)
            val didl = xmlEscape(
                "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                    "<item id=\"1\" restricted=\"0\"><dc:title>" + meta + "</dc:title>" +
                    "<upnp:class>object.item.videoItem</upnp:class>" +
                    "<res protocolInfo=\"http-get:*:video/mp4:*\">" + uriEsc + "</res>" +
                    "</item></DIDL-Lite>"
            )
            val setBody = soapEnvelope(
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + uriEsc + "</CurrentURI>" +
                    "<CurrentURIMetaData>" + didl + "</CurrentURIMetaData>"
            )
            val setOk = soap(endpoints.avTransportControlUrl, "SetAVTransportURI", setBody)
            if (!setOk) { cb(false); return@execute }
            val playBody = soapEnvelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            cb(soap(endpoints.avTransportControlUrl, "Play", playBody))
        }
    }

    fun seekTo(endpoints: RendererEndpoints, seconds: Int, cb: (Boolean) -> Unit) {
        executor.execute {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val sec = seconds % 60
            val t = String.format("%02d:%02d:%02d", h, m, sec)
            val body = soapEnvelope("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$t</Target>")
            cb(soap(endpoints.avTransportControlUrl, "Seek", body))
        }
    }

    fun pause(endpoints: RendererEndpoints, cb: (Boolean) -> Unit) =
        simple(endpoints, "Pause", "<InstanceID>0</InstanceID>", cb)

    fun stop(endpoints: RendererEndpoints, cb: (Boolean) -> Unit) =
        simple(endpoints, "Stop", "<InstanceID>0</InstanceID>", cb)

    fun getPosition(endpoints: RendererEndpoints, cb: (Pair<Int, Int>?) -> Unit) {
        executor.execute {
            try {
                val body = soapEnvelope("GetPositionInfo", "<InstanceID>0</InstanceID>")
                val resp = soapRaw(endpoints.avTransportControlUrl, "GetPositionInfo", body)
                fun secs(v: String): Int {
                    val p = v.split(":").map { it.toIntOrNull() ?: 0 }
                    return if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else 0
                }
                val rel = Regex("<RelTime>([^<]*)</RelTime>").find(resp)?.groupValues?.get(1) ?: ""
                val dur = Regex("<TrackDuration>([^<]*)</TrackDuration>").find(resp)?.groupValues?.get(1) ?: ""
                if (rel.isBlank() || dur.isBlank()) { cb(null); return@execute }
                cb(secs(rel) to secs(dur))
            } catch (_: Exception) { cb(null) }
        }
    }

    private fun simple(e: RendererEndpoints, action: String, args: String, cb: (Boolean) -> Unit) {
        executor.execute { cb(soap(e.avTransportControlUrl, action, soapEnvelope(action, args))) }
    }

    private fun soapEnvelope(action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$args</u:$action>" +
            "</s:Body></s:Envelope>"

    private fun soap(url: String, action: String, body: String): Boolean {
        return try { soapRaw(url, action, body); true } catch (_: Exception) { false }
    }

    private fun soapRaw(url: String, action: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText) ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("SOAP $action -> $code: ${text.take(200)}")
        return text
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        conn.disconnect()
        return text
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
