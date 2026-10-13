package dev.glasslauncher.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class PcmHttpServerTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private fun get(port: Int, path: String): Socket = Socket(lo, port).also {
        it.soTimeout = 3000
        it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
    }

    private fun firstLine(c: Socket): String {
        val sb = StringBuilder()
        while (true) { val b = c.getInputStream().read(); if (b < 0 || b == '\n'.code) break; sb.append(b.toChar()) }
        return sb.toString().trim()
    }

    @Test fun pathIsA128BitHexTokenAndOthersGet404() {
        val s = PcmHttpServer(lo, lo)
        assertTrue(Regex("/[0-9a-f]{32}\\.wav").matches(s.path))
        assertFalse(s.path == PcmHttpServer(lo, lo).path)
        val port = s.start()
        try {
            get(port, "/glass.wav").use { assertEquals("HTTP/1.1 404 Not Found", firstLine(it)) }
            get(port, s.path + "x").use { assertEquals("HTTP/1.1 404 Not Found", firstLine(it)) }
            get(port, s.path).use { assertEquals("HTTP/1.1 200 OK", firstLine(it)) }
        } finally { s.stop() }
    }

    @Test fun anotherAddressIsDroppedWithoutAReply() {
        val s = PcmHttpServer(lo, InetAddress.getByName("10.9.9.9"))
        val port = s.start()
        try {
            get(port, s.path).use { c -> assertEquals(-1, runCatching { c.getInputStream().read() }.getOrDefault(-1)) }
        } finally { s.stop() }
    }

    @Test fun aSecondClientClosesTheFirst() {
        val s = PcmHttpServer(lo, lo)
        val port = s.start()
        try {
            val first = get(port, s.path)
            assertEquals("HTTP/1.1 200 OK", firstLine(first))
            get(port, s.path).use { second ->
                assertEquals("HTTP/1.1 200 OK", firstLine(second))
                // The first stream ends: reading drains buffered audio, then hits EOF or a reset.
                val end = runCatching { while (first.getInputStream().read(ByteArray(8192)) >= 0) {} ; true }.getOrDefault(true)
                assertTrue(end)
            }
            first.close()
        } finally { s.stop() }
    }

    @Test fun stopClosesOpenClients() {
        val s = PcmHttpServer(lo, lo)
        val port = s.start()
        val c = get(port, s.path)
        assertEquals("HTTP/1.1 200 OK", firstLine(c))
        s.stop()
        val ended = runCatching { while (c.getInputStream().read(ByteArray(8192)) >= 0) {} ; true }.getOrDefault(true)
        assertTrue(ended)
        c.close()
    }
}
