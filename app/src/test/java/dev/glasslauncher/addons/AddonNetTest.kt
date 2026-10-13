package dev.glasslauncher.addons

import dev.glasslauncher.addon.Choice
import dev.glasslauncher.addon.FormField
import dev.glasslauncher.addon.FormReply
import dev.glasslauncher.addon.AddonCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class AddonNetTest {
    private fun ip(s: String) = InetAddress.getByName(s) // literals only: no DNS

    @Test fun privateAndLocalAddressesAreRefused() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255", "169.254.1.1", "0.0.0.0", "0.1.2.3",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:192.168.1.1", "::ffff:127.0.0.1", "224.0.0.1", "ff02::1")) assertTrue(a, AddonHosts.isPrivate(ip(a)))
        for (a in listOf("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.32.0.1", "172.15.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8")) assertFalse(a, AddonHosts.isPrivate(ip(a)))
    }

    @Test fun localNamesAndIpLiteralsAreRefused() {
        for (h in listOf("localhost", "LOCALHOST", "foo.localhost", "printer.local", "nas.lan", "svc.internal", "intranet", "127.0.0.1", "192.168.1.5", "8.8.8.8", "2130706433", "0x7f.1", "[::1]", "::1", "a.b.1", "", "host.")) {
            if (h != "host.") assertTrue(h, AddonHosts.blockedName(h)) else assertTrue(h, AddonHosts.blockedName(h)) // no dot inside
        }
        for (h in listOf("cdn.example.com", "images.tmdb.org", "a-b.example.co.uk", "xn--bcher-kva.example")) assertFalse(h, AddonHosts.blockedName(h))
    }

    @Test fun imageUrlsNeedAPublicHttpsHost() {
        assertEquals("https://cdn.example.com/a.jpg?x=1", AddonLinks.image("https://cdn.example.com/a.jpg?x=1"))
        assertEquals("https://cdn.example.com:8443/a.jpg", AddonLinks.image("https://cdn.example.com:8443/a.jpg"))
        for (u in listOf("https://localhost/a.jpg", "https://127.0.0.1/a.jpg", "https://192.168.1.2:8080/a", "https://nas.local/a.png", "https://10.0.0.1/", "http://cdn.example.com/a.jpg", "https://[::1]/a", "https://user@cdn.example.com/a"))
            assertNull(u, AddonLinks.image(u))
    }

    @Test fun marketLinksAreOnlyAPackagePage() {
        assertEquals("market://details?id=com.example.app", AddonLinks.link("market://details?id=com.example.app"))
        for (u in listOf("market://search?q=x", "market://details?id=nodot", "market://details?id=a.b&x=1", "market://details?id=1a.b", "market://launch?id=a.b", "market://details?id=a.b#x"))
            assertNull(u, AddonLinks.link(u))
    }

    private class FakeDns(val answers: () -> List<String>) : Dns {
        val calls = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> { calls.incrementAndGet(); return answers().map { InetAddress.getByName(it) } }
    }

    @Test fun dnsAnswersAreCheckedOnEveryLookup() {
        var answer = listOf("93.184.216.34")
        val fake = FakeDns { answer }
        val dns = SafeDns(fake)
        assertEquals(1, dns.lookup("cdn.example.com").size)
        answer = listOf("127.0.0.1") // rebinding: the same name now points inside
        assertThrowsUnknownHost { dns.lookup("cdn.example.com") }
        answer = listOf("93.184.216.34", "192.168.0.9") // one private address among public ones is enough to refuse
        assertThrowsUnknownHost { dns.lookup("cdn.example.com") }
        answer = listOf("::1"); assertThrowsUnknownHost { dns.lookup("cdn.example.com") }
        answer = emptyList(); assertThrowsUnknownHost { dns.lookup("cdn.example.com") }
        val before = fake.calls.get()
        assertThrowsUnknownHost { dns.lookup("printer.local") } // refused by name, without asking DNS
        assertEquals(before, fake.calls.get())
    }

    private fun assertThrowsUnknownHost(f: () -> Unit) { try { f(); throw AssertionError("expected UnknownHostException") } catch (_: UnknownHostException) {} }

    // Minimal headers: just what the inspector reads.
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun png(w: Int, h: Int, animated: Boolean = false): ByteArray {
        val ihdr = be32(13) + ascii("IHDR") + be32(w) + be32(h) + ByteArray(5) + ByteArray(4)
        val actl = if (animated) be32(8) + ascii("acTL") + ByteArray(8) + ByteArray(4) else ByteArray(0)
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ihdr + actl + be32(0) + ascii("IDAT") + ByteArray(4)
    }
    private fun jpeg(w: Int, h: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0, 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
        (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 0, 0, 0)
    private fun le(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
    private fun webp(chunk: String, data: ByteArray) = ascii("RIFF") + le(4 + 8 + data.size, 4) + ascii("WEBP") + ascii(chunk) + le(data.size, 4) + data
    private fun vp8x(w: Int, h: Int, flags: Int) = webp("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + le(w - 1, 3) + le(h - 1, 3) + ByteArray(8))

    @Test fun onlyStaticBitmapsWithinTheSizeLimitPass() {
        assertEquals("png" to (1280 to 720), ArtFormat.inspect(png(1280, 720))!!.let { it.format to (it.width to it.height) })
        assertEquals("jpeg" to (1280 to 720), ArtFormat.inspect(jpeg(1280, 720))!!.let { it.format to (it.width to it.height) })
        assertEquals("webp" to (640 to 360), ArtFormat.inspect(vp8x(640, 360, 0))!!.let { it.format to (it.width to it.height) })
        assertNull(ArtFormat.inspect(png(1281, 720))); assertNull(ArtFormat.inspect(jpeg(720, 1281))); assertNull(ArtFormat.inspect(vp8x(2000, 100, 0)))
        assertNull(ArtFormat.inspect(png(0, 10)))
        assertNull(ArtFormat.inspect(png(100, 100, animated = true))) // APNG
        assertNull(ArtFormat.inspect(vp8x(100, 100, flags = 0x02))) // animated WebP
        assertNull(ArtFormat.inspect(ascii("GIF89a") + ByteArray(60))) // GIF is never accepted
        assertNull(ArtFormat.inspect(ByteArray(100))); assertNull(ArtFormat.inspect(ByteArray(0)))
    }

    @Test fun webpLosslessAndLossyHeadersAreRead() {
        // VP8L: width-1 = 99 in 14 bits, height-1 = 49 in the next 14.
        val w = 99; val h = 49
        val l = byteArrayOf(0x2F, (w and 0xFF).toByte(), (((w shr 8) and 0x3F) or ((h and 0x03) shl 6)).toByte(), ((h shr 2) and 0xFF).toByte(), ((h shr 10) and 0x0F).toByte(), 0, 0, 0)
        assertEquals(100 to 50, ArtFormat.inspect(webp("VP8L", l))!!.let { it.width to it.height })
        val lossy = byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le(320, 2) + le(180, 2) + ByteArray(4)
        assertEquals(320 to 180, ArtFormat.inspect(webp("VP8 ", lossy))!!.let { it.width to it.height })
    }

    @Test fun bodyIsCappedWhileReading() {
        fun src(n: Int) = Buffer().write(ByteArray(n))
        assertEquals(100, readCapped(src(100), 100)!!.size)
        assertNull(readCapped(src(101), 100))
        assertEquals(0, readCapped(src(0), 100)!!.size)
        assertNull(readCapped(src(10_000_000), ArtFormat.MAX_BYTES))
    }

    @Test fun attributionSurvivesTruncation() {
        val v = AddonContent.via("H".repeat(60), "Sample Add-on")
        assertTrue(v, v.endsWith(" · via Sample Add-on")); assertTrue(v.length <= 48)
        val long = AddonContent.via("Heading", "A".repeat(32))
        assertTrue(long, long.endsWith(" · via " + "A".repeat(32)) && long.startsWith("Heading".take(1)))
        assertEquals("Top · via S", AddonContent.via("Top", "S"))
    }

    @Test fun formDefaultsMustFitTheFieldType() {
        val fields = listOf(
            FormField("t1", "toggle", "T1", default = "true"), FormField("t2", "toggle", "T2", default = "maybe"),
            FormField("c1", "choice", "C1", choices = listOf(Choice("a", "A")), default = "a"), FormField("c2", "choice", "C2", choices = listOf(Choice("a", "A")), default = "zzz"),
            FormField("x", "text", "X", default = "y".repeat(200)), FormField("b", "action", "B", default = "x"),
        )
        val f = AddonContent.form(AddonCodec.encodeForm(FormReply(fields = fields)), "S")!!.fields.associateBy { it.id }
        assertEquals("true", f["t1"]!!.default); assertNull(f["t2"]!!.default)
        assertEquals("a", f["c1"]!!.default); assertNull(f["c2"]!!.default)
        assertEquals(AddonContent.MAX_VALUE, f["x"]!!.default!!.length); assertNull(f["b"]!!.default)
    }

    @Test fun storedSettingsArePurgedForUninstalledAddonsAndRemovedFields() {
        val s = mapOf("a.b/x" to "1", "a.b/y" to "2", "gone.app/x" to "3", "a.bc/x" to "4", "junk" to "5")
        assertEquals(setOf("gone.app/x"), AddonPurge.uninstalled(s) { it != "gone.app" })
        assertEquals(setOf("a.b/y"), AddonPurge.undeclared(s, "a.b", setOf("x"))) // other packages' keys are never touched
        assertTrue(AddonPurge.undeclared(s, "a.b", setOf("x", "y")).isEmpty())
    }

    @Test fun failedRowReadsKeepTheRowForAWhileAndRetrySoon() {
        assertTrue(AddonSurfaces.keepStale(1_000L, 1_000L + AddonSurfaces.STALE_MS))
        assertFalse(AddonSurfaces.keepStale(1_000L, 1_001L + AddonSurfaces.STALE_MS))
        assertFalse(AddonSurfaces.keepStale(null, 5L))
        var t = 0L
        val g = IntervalGate { t }
        g.retryIn("k", 1, AddonSurfaces.RETRY_MS)
        t = AddonSurfaces.RETRY_MS - 1; assertFalse(g.due("k", 1))
        t = AddonSurfaces.RETRY_MS; assertTrue(g.due("k", 1))
    }

    @Test fun onlyUserChangesReopenTheFifteenMinuteFloor() {
        val a = AddonSnapshot(mapOf("p" to "cert1"), mapOf("p/x" to "1"))
        assertEquals(AddonSurfaces.version(a, "p"), AddonSurfaces.version(a.copy(enabled = mapOf("p" to "cert2")), "p")) // a re-signed add-on does not
        assertFalse(AddonSurfaces.version(a, "p") == AddonSurfaces.version(a.copy(settings = mapOf("p/x" to "2")), "p")) // a setting the user changed does
        assertFalse(AddonSurfaces.version(a, "p") == AddonSurfaces.version(AddonSnapshot.NONE, "p")) // so does switching it on
    }

    // The call core: connecting is inside the timeout, and a provider client is always released.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun aBlockedAcquireTimesOutAndIsReleasedWhenItFinallyReturns() = runBlocking {
        val call = AddonCall(100, scope)
        val gate = CompletableDeferred<Unit>()
        val released = AtomicInteger()
        val t0 = System.nanoTime()
        val out = call.run("a", connect = { runBlocking { gate.await() }; "client" }, transact = { "late" }, release = { released.incrementAndGet() })
        assertNull(out)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000) // not held by the blocked acquire
        assertNull(call.run("a", { "c" }, { "x" })) // still in flight
        gate.complete(Unit); delay(150)
        assertEquals(1, released.get()) // closed in the end, though too late to be used
        assertEquals("ok", call.run("a", { "c" }, { "ok" })) // and free again
    }

    @Test fun clientIsReleasedAfterSuccessAndAfterTransactErrorsButNotWhenConnectFails() = runBlocking {
        val call = AddonCall(500, scope)
        val released = AtomicInteger()
        assertEquals("ok", call.run("a", { "c" }, { "ok" }, { released.incrementAndGet() }))
        assertNull(call.run("a", { "c" }, { throw android.os.DeadObjectException() }, { released.incrementAndGet() }))
        assertNull(call.run("a", { error("no provider") }, { "x" }, { released.incrementAndGet() }))
        delay(50)
        assertEquals(2, released.get())
        assertNotNull(call.run("a", { "c" }, { "fine" }))
    }
}
