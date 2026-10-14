package dev.glasslauncher.net

import dev.glasslauncher.widgets.ArtFetcher
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class PublicHttpTest {
    private fun ip(s: String) = InetAddress.getByName(s) // literals only: no DNS
    private fun dnsReturning(vararg a: String) = Dns { _ -> a.map { ip(it) } }

    @Test fun namesThatResolveToNonPublicAddressesAreRefused() {
        for (a in listOf("127.0.0.1", "10.0.0.5", "172.20.1.1", "192.168.0.9", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::", "::1", "fe80::1", "fc00::1", "fd00::2", "224.0.0.1", "ff02::1")) {
            try { SafeDns(dnsReturning(a)).lookup("cdn.example.com"); fail("$a was allowed") } catch (_: UnknownHostException) {}
        }
        // One bad answer among good ones refuses the name (rebinding tricks mix them).
        try { SafeDns(dnsReturning("8.8.8.8", "10.0.0.1")).lookup("cdn.example.com"); fail("mixed answer allowed") } catch (_: UnknownHostException) {}
        assertEquals(1, SafeDns(dnsReturning("8.8.8.8")).lookup("cdn.example.com").size)
        assertEquals(1, SafeDns(dnsReturning("2001:4860:4860::8888")).lookup("cdn.example.com").size)
    }

    @Test fun privateNamesAndIpLiteralsAreRefusedBeforeDns() {
        for (h in listOf("localhost", "x.localhost", "tv.local", "nas.lan", "svc.internal", "10.0.0.1", "192.168.1.1", "100.64.1.1", "169.254.1.1", "[::1]", "fe80::1", "[fc00::1]", "0.0.0.0"))
            assertTrue(h, PublicHosts.blockedName(h))
        assertFalse(PublicHosts.blockedName("i.scdn.co"))
    }

    @Test fun everyHopMustBeHttpsAndPublic() {
        assertTrue(PublicHttp.hopAllowed("https", "i.scdn.co"))
        assertFalse("a redirect to http", PublicHttp.hopAllowed("http", "i.scdn.co"))
        assertFalse("a redirect to a private literal", PublicHttp.hopAllowed("https", "192.168.1.1"))
        assertFalse("a redirect to a local name", PublicHttp.hopAllowed("https", "router.lan"))
    }

    @Test fun atMostThreeRedirects() {
        assertFalse(PublicHttp.tooManyHops(3)); assertTrue(PublicHttp.tooManyHops(4))
    }

    @Test fun theClientRefusesPlainHttpEvenToAPublicNameOrLoopback() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        try {
            PublicHttp.client(dnsReturning("8.8.8.8")).newCall(Request.Builder().url(server.url("/a.jpg")).build()).execute().close()
            fail("http fetched")
        } catch (_: IOException) { /* refused */ } finally { server.shutdown() }
        assertEquals(0, server.requestCount)
    }

    // --- Now Playing art: who may be fetched for which session ---
    private val owners = mapOf("com.spotify.tv.android.art" to "com.spotify.tv.android", "com.other.provider" to "com.other")
    private fun ok(uri: String, pkg: String = "com.spotify.tv.android") = ArtFetcher.allowed(uri, pkg) { owners[it] }

    @Test fun aContentUriMustBelongToTheSessionsOwnApp() {
        assertTrue(ok("content://com.spotify.tv.android.art/cover/1"))
        assertFalse("another app's provider", ok("content://com.other.provider/secret"))
        assertFalse("an authority nobody declares", ok("content://nobody.here/x"))
        assertFalse("a system provider", ok("content://media/external/images/1"))
        assertTrue("userinfo is ignored: the authority after @ decides", ok("content://x@com.spotify.tv.android.art/x"))
        assertFalse(ok("content://com.spotify.tv.android.art@com.other.provider/x"))
    }

    @Test fun httpsToAPublicNameIsAllowedPrivateOrCleartextIsNot() {
        assertTrue(ok("https://i.scdn.co/image/abc"))
        assertFalse(ok("https://192.168.1.1/cover.jpg"))
        assertFalse(ok("https://localhost/cover.jpg"))
        assertFalse(ok("https://nas.local/cover.jpg"))
        assertFalse(ok("https://[::1]/cover.jpg"))
        assertFalse(ok("http://i.scdn.co/image/abc"))
        assertFalse(ok("file:///data/data/x"))
    }
}
