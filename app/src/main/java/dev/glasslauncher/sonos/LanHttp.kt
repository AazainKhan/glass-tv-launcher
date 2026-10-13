package dev.glasslauncher.sonos

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class LanHttpException(message: String) : Exception(message)

/**
 * A tiny HTTP/1.1 client for the Sonos LAN only. Android blocks app-wide cleartext, and the network security config
 * stays as it is: this talks over a raw Socket and only ever to a private IPv4 literal on port 1400 (RFC 1918 or
 * link-local). Anything else is refused before a socket is opened. [allow] exists for tests.
 */
class LanHttp(private val allow: (host: String, port: Int) -> Boolean = ::isSonosLanEndpoint) {
    class Response(val code: Int, val body: String)

    fun request(
        method: String, host: String, port: Int, path: String,
        headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
    ): Response {
        if (!allow(host, port)) throw LanHttpException("refused: $host:$port is not a private Sonos endpoint")
        val head = formatRequest(method, host, port, path, headers, body?.size)
        Socket().use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); if (body != null) write(body); flush() }
            return readResponse(s.getInputStream())
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 3_000
        private const val MAX_HEAD = 16 * 1024

        fun formatRequest(method: String, host: String, port: Int, path: String, headers: Map<String, String>, length: Int?): String {
            if (!path.startsWith("/") || path.any { it <= ' ' }) throw LanHttpException("bad path")
            if (method != "GET" && method != "POST") throw LanHttpException("bad method")
            return buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                for ((k, v) in headers) {
                    if (k.any { it <= ' ' || it == ':' } || v.any { it == '\r' || it == '\n' }) throw LanHttpException("bad header")
                    append(k).append(": ").append(v).append("\r\n")
                }
                if (length != null) append("Content-Length: ").append(length).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
        }

        /** Reads one response: Content-Length, chunked, or until close. Over [SafeXml.MAX_BYTES] of body throws. */
        fun readResponse(input: InputStream): Response {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw LanHttpException("closed before headers end")
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.size() > MAX_HEAD) throw LanHttpException("headers over cap")
            }
            val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            val code = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw LanHttpException("bad status line")
            val h = HashMap<String, String>()
            for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val out = ByteArrayOutputStream()
            fun take(n: Int, buf: ByteArray = ByteArray(4096)): Int {
                val r = input.read(buf, 0, minOf(buf.size, n))
                if (r > 0) { if (out.size() + r > SafeXml.MAX_BYTES) throw LanHttpException("response over cap"); out.write(buf, 0, r) }
                return r
            }
            if (h["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                while (true) {
                    val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw LanHttpException("bad chunk size")
                    if (size == 0) break
                    var left = size
                    if (out.size() + size > SafeXml.MAX_BYTES) throw LanHttpException("response over cap")
                    while (left > 0) { val r = take(left); if (r < 0) throw LanHttpException("closed mid-chunk"); left -= r }
                    readLine(input) // CRLF after the chunk
                }
            } else {
                val len = h["content-length"]?.toLongOrNull()
                if (len != null && len > SafeXml.MAX_BYTES) throw LanHttpException("response over cap")
                var left = len ?: Long.MAX_VALUE
                while (left > 0) { val r = take(minOf(left, 4096L).toInt()); if (r < 0) break; left -= r }
            }
            return Response(code, String(out.toByteArray(), Charsets.UTF_8))
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw LanHttpException("closed mid-response")
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 64) throw LanHttpException("bad chunk framing")
            }
        }
    }
}

/** True only for a dotted-quad private (10/8, 172.16/12, 192.168/16) or link-local (169.254/16) IPv4 and port 1400. */
fun isSonosLanEndpoint(host: String, port: Int): Boolean {
    if (port != 1400) return false
    val parts = host.split('.')
    if (parts.size != 4) return false
    val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false else p.toInt() }
    if (o.any { it > 255 }) return false
    return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
}
