package dev.glasslauncher.sonos

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class SonosTest {
    // The Roam binds the action prefix on the envelope (xmlns:u), as here.
    private fun resp(inner: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><s:Body>$inner</s:Body></s:Envelope>"

    @Test fun envelopeEscapesUriAndMetadata() = runBlocking<Unit> {
        MockWebServer().use { srv ->
            srv.enqueue(MockResponse().setBody(resp("<u:SetAVTransportURIResponse/>")))
            srv.start()
            SonosControl(srv.url("/").toString().trimEnd('/'), LanHttp { _, _ -> true }).setAvTransportUri("http://1.2.3.4:5/a.wav?x=1&y=2")
            val r = srv.takeRequest()
            assertEquals("/MediaRenderer/AVTransport/Control", r.path)
            assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", r.getHeader("SOAPACTION"))
            val b = r.body.readUtf8()
            assertTrue(b, b.contains("<CurrentURI>http://1.2.3.4:5/a.wav?x=1&amp;y=2</CurrentURI>"))
            assertTrue(b, b.contains("&lt;DIDL-Lite"))
            assertFalse(b.contains("<DIDL-Lite"))
            SafeXml.parse(b) // well-formed
        }
    }

    @Test fun volumeAndTransportParsing() = runBlocking<Unit> {
        MockWebServer().use { srv ->
            srv.enqueue(MockResponse().setBody(resp("<u:GetVolumeResponse><CurrentVolume>37</CurrentVolume></u:GetVolumeResponse>")))
            srv.enqueue(MockResponse().setBody(resp("<u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus></u:GetTransportInfoResponse>")))
            srv.start()
            val c = SonosControl(srv.url("/").toString().trimEnd('/'), LanHttp { _, _ -> true })
            assertEquals(37, c.getVolume())
            assertEquals(TransportInfo("PLAYING", "OK"), c.getTransportInfo())
            assertEquals("/MediaRenderer/RenderingControl/Control", srv.takeRequest().path)
        }
    }

    @Test fun faultThrowsWithCode() = runBlocking<Unit> {
        MockWebServer().use { srv ->
            srv.enqueue(MockResponse().setResponseCode(500).setBody(resp(
                "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault>")))
            srv.start()
            try {
                SonosControl(srv.url("/").toString().trimEnd('/'), LanHttp { _, _ -> true }).play()
                fail()
            } catch (e: SonosException) {
                assertEquals(701, e.upnpCode)
            }
        }
    }

    @Test fun rejectsDoctypeAndOversize() {
        try { SafeXml.parse("<!DOCTYPE x [<!ENTITY a \"b\">]><x>&a;</x>"); fail() } catch (_: SonosXmlException) {}
        try { SafeXml.parse("<x>" + "a".repeat(SafeXml.MAX_BYTES) + "</x>"); fail() } catch (_: SonosXmlException) {}
    }

    @Test fun ssdpResponseParsing() {
        val ok = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nLOCATION: http://192.168.1.50:1400/xml/device_description.xml\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        assertEquals("http://192.168.1.50:1400/xml/device_description.xml", SonosDiscovery.parseResponse(ok))
        assertEquals("192.168.1.50", SonosDiscovery.hostOf(SonosDiscovery.parseResponse(ok)!!))
        assertNull(SonosDiscovery.parseResponse(ok.replace("ZonePlayer", "MediaRenderer")))
        assertNull(SonosDiscovery.parseResponse("NOTIFY * HTTP/1.1\r\n\r\n"))
        assertTrue(SonosDiscovery.buildSearch().contains("ST: urn:schemas-upnp-org:device:ZonePlayer:1"))
    }

    @Test fun descriptionParsing() {
        val xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><roomName>Kitchen</roomName><modelName>Sonos Roam 2</modelName></device></root>"
        assertEquals(SonosSpeaker("h", "Kitchen", "Sonos Roam 2"), SonosDiscovery.parseDescription("h", xml))
    }

    @Test fun wavHeaderLayout() {
        val h = java.nio.ByteBuffer.wrap(PcmHttpServer.wavHeader()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(PcmHttpServer.wavHeader(), 0, 4))
        assertEquals(-1, h.getInt(4))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(48000, h.getInt(24))
        assertEquals(192000, h.getInt(28))
        assertEquals(4, h.getShort(32).toInt())
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(PcmHttpServer.wavHeader(), 36, 4))
        assertEquals(-1, h.getInt(40))
    }

    @Test fun ringUnderrunGivesSilence() {
        val r = PcmRingBuffer(8)
        r.write(byteArrayOf(1, 2, 3))
        val d = ByteArray(5) { 9 }
        assertEquals(3, r.read(d))
        assertEquals(listOf<Byte>(1, 2, 3, 0, 0), d.toList())
        assertEquals(0, r.read(d))
        assertTrue(d.all { it == 0.toByte() })
    }

    @Test fun ringWrapsAndDropsOldOnOverrun() {
        val r = PcmRingBuffer(4)
        r.write(byteArrayOf(1, 2, 3))
        r.read(ByteArray(2))
        r.write(byteArrayOf(4, 5, 6)) // wraps
        val d = ByteArray(4)
        assertEquals(4, r.read(d))
        assertEquals(listOf<Byte>(3, 4, 5, 6), d.toList())
        r.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        r.read(d)
        assertEquals(listOf<Byte>(3, 4, 5, 6), d.toList())
    }

    @Test fun serverServesWavToAllowedClientOnly() {
        val lo = InetAddress.getByName("127.0.0.1")
        val s = PcmHttpServer(lo, lo)
        val port = s.start()
        try {
            Socket(lo, port).use { c ->
                c.soTimeout = 3000
                c.getOutputStream().write("GET ${s.path} HTTP/1.1\r\n\r\n".toByteArray())
                val buf = ByteArray(400)
                var n = 0
                while (n < buf.size) { val k = c.getInputStream().read(buf, n, buf.size - n); if (k < 0) break; n += k }
                val head = String(buf, 0, n, Charsets.ISO_8859_1)
                assertTrue(head.startsWith("HTTP/1.1 200"))
                assertTrue(head.contains("RIFF"))
            }
        } finally { s.stop() }
    }

    @Test fun probeConnectionClosesEarlyThenReconnectStreams() {
        val lo = InetAddress.getByName("127.0.0.1")
        val s = PcmHttpServer(lo, lo)
        val port = s.start()
        try {
            // Probe: send request, read a little, drop abruptly.
            Socket(lo, port).use { c ->
                c.soTimeout = 3000
                c.getOutputStream().write("GET ${s.path} HTTP/1.1\r\n\r\n".toByteArray())
                c.getInputStream().read(ByteArray(10))
            }
            s.ring.write(ByteArray(PcmHttpServer.CHUNK_BYTES * 3) { 7 })
            Socket(lo, port).use { c ->
                c.soTimeout = 3000
                c.getOutputStream().write("GET ${s.path} HTTP/1.1\r\n\r\n".toByteArray())
                val buf = ByteArray(300)
                var n = 0
                while (n < buf.size) { val k = c.getInputStream().read(buf, n, buf.size - n); if (k < 0) break; n += k }
                assertTrue(String(buf, 0, n, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200"))
            }
        } finally { s.stop() }
    }
}
