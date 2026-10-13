package dev.glasslauncher.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class LanHttpTest {
    private fun read(s: String) = LanHttp.readResponse(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))

    @Test fun guardAllowsOnlyPrivateIpv4OnPort1400() {
        for (h in listOf("10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.1.50", "169.254.3.4")) assertTrue(h, isSonosLanEndpoint(h, 1400))
        for (h in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "127.0.0.1", "example.com", "192.168.1", "192.168.1.256", "192.168.1.1.1", "::1", "0x7f.0.0.1", "192.168.1.5/x", ""))
            assertFalse(h, isSonosLanEndpoint(h, 1400))
        assertFalse(isSonosLanEndpoint("192.168.1.50", 80))
        assertFalse(isSonosLanEndpoint("192.168.1.50", 1401))
    }

    @Test fun refusesBeforeOpeningASocket() {
        try { LanHttp().request("GET", "93.184.216.34", 1400, "/"); fail() } catch (e: LanHttpException) { assertTrue(e.message!!.startsWith("refused")) }
    }

    @Test fun requestFormatting() {
        val r = LanHttp.formatRequest("POST", "192.168.1.50", 1400, "/a/b", mapOf("SOAPACTION" to "\"x#y\""), 12)
        assertEquals("POST /a/b HTTP/1.1\r\nHost: 192.168.1.50:1400\r\nSOAPACTION: \"x#y\"\r\nContent-Length: 12\r\nConnection: close\r\n\r\n", r)
        try { LanHttp.formatRequest("GET", "h", 1400, "/a b", emptyMap(), null); fail() } catch (_: LanHttpException) {}
        try { LanHttp.formatRequest("GET", "h", 1400, "/", mapOf("X" to "a\r\nEvil: 1"), null); fail() } catch (_: LanHttpException) {}
    }

    @Test fun contentLengthResponse() {
        val r = read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA")
        assertEquals(200, r.code); assertEquals("hello", r.body)
    }

    @Test fun chunkedResponse() {
        val r = read("HTTP/1.1 500 Err\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n4;ext=1\r\ndefg\r\n0\r\n\r\n")
        assertEquals(500, r.code); assertEquals("abcdefg", r.body)
    }

    @Test fun capIsEnforcedForAllFramings() {
        val big = "a".repeat(SafeXml.MAX_BYTES + 1)
        try { read("HTTP/1.1 200 OK\r\nContent-Length: ${big.length}\r\n\r\n$big"); fail() } catch (_: LanHttpException) {}
        try { read("HTTP/1.1 200 OK\r\n\r\n$big"); fail() } catch (_: LanHttpException) {}
        try { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${Integer.toHexString(big.length)}\r\n$big\r\n0\r\n\r\n"); fail() } catch (_: LanHttpException) {}
        assertEquals(SafeXml.MAX_BYTES, read("HTTP/1.1 200 OK\r\n\r\n${"a".repeat(SafeXml.MAX_BYTES)}").body.length)
    }

    @Test fun rejectsLeadingZeroOctets() {
        assertFalse(isSonosLanEndpoint("010.0.0.1", 1400))
        assertFalse(isSonosLanEndpoint("192.168.01.5", 1400))
        assertTrue(isSonosLanEndpoint("10.0.0.0", 1400))
    }

    @Test fun rejectsNegativeChunkAndTooManyChunks() {
        try { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nabcde\r\n0\r\n\r\n"); fail() } catch (_: LanHttpException) {}
        val many = "1\r\na\r\n".repeat(LanHttp.MAX_CHUNKS + 1)
        try { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${many}0\r\n\r\n"); fail() } catch (e: LanHttpException) { assertEquals("too many chunks", e.message) }
    }

    @Test fun truncatedAndGarbageResponsesFail() {
        try { read("HTTP/1.1 200 OK\r\nContent-Le"); fail() } catch (_: LanHttpException) {}
        try { read("nonsense\r\n\r\n"); fail() } catch (_: LanHttpException) {}
        try { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab"); fail() } catch (_: LanHttpException) {}
    }

    @Test fun headerOverCapFails() {
        try { read("HTTP/1.1 200 OK\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"); fail() } catch (_: LanHttpException) {}
    }
}
