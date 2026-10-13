package dev.glasslauncher.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DownloadEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val servers = mutableListOf<FileServer>()

    private fun serve(content: ByteArray, etag: String? = "\"v1\"", ranges: Boolean = true) =
        FileServer(content, etag, ranges).also { servers += it }

    private fun engine(free: Long = Long.MAX_VALUE, retries: Int = 3, keepFree: Long = 0) =
        DownloadEngine(freeSpace = { free }, rangeMinBytes = 1 shl 20, retries = retries, backoffMs = { 0 }, keepFreeBytes = keepFree)

    private fun request(server: FileServer, id: String = "a", sha: String? = null, segments: Int = 4) =
        DownloadRequest(id, server.url(), tmp.root, sha256 = sha, segments = segments)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @After fun stop() = servers.forEach { it.stop() }

    @Test fun rangedDownloadUsesSegmentsAndMatchesBytes() = runBlocking {
        val data = testBytes(3 * 1024 * 1024 + 123)
        val s = serve(data)
        val done = engine().download(request(s)) {}
        assertArrayEquals(data, done.file.readBytes())
        assertEquals("file.bin", done.file.name)
        assertNull(done.sha256) // nothing asked for a check, so nothing is hashed
        val ranges = s.gets.mapNotNull { it.headers["Range"] }.filter { it != "bytes=0-0" }
        assertEquals(4, ranges.toSet().size)
        assertTrue(s.gets.all { it.headers["Accept-Encoding"] == "identity" })
        assertFalse(File(tmp.root, "a.part").exists() || File(tmp.root, "a.part.json").exists())
    }

    @Test fun serverWithoutRangesUsesOneStream() = runBlocking {
        val data = testBytes(2 * 1024 * 1024)
        val s = serve(data, ranges = false)
        val done = engine().download(request(s)) {}
        assertArrayEquals(data, done.file.readBytes())
        assertEquals(1, s.gets.count { it.headers["Range"] == null })
    }

    @Test fun smallFileUsesOneStreamEvenWithRanges() = runBlocking {
        val data = testBytes(10_000)
        val s = serve(data)
        val done = engine().download(request(s)) {}
        assertArrayEquals(data, done.file.readBytes())
        assertEquals(2, s.gets.size) // the probe and the file
        assertTrue(s.requests.none { it.method == "HEAD" })
    }

    @Test fun resumesFromTheWrittenOffsets() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val s = serve(data)
        s.cutNext.set(4)
        try {
            engine(retries = 0).download(request(s)) {}
            fail("the cut transfer should fail")
        } catch (e: DownloadFailure.Network) { /* expected */ }
        assertTrue(File(tmp.root, "a.part").exists())
        val meta = kotlinx.serialization.json.Json.decodeFromString<PartMeta>(File(tmp.root, "a.part.json").readText())
        assertTrue(meta.segments.sumOf { it.written } > 0)

        s.requests.clear()
        val progress = mutableListOf<Progress>()
        val done = engine().download(request(s)) { progress += it }
        assertArrayEquals(data, done.file.readBytes())
        assertFalse(done.restarted)
        val resumed = s.gets.filter { it.headers["If-Range"] == "\"v1\"" }
        assertTrue(resumed.isNotEmpty())
        assertTrue(s.gets.all { it.headers["Range"]!!.substringAfter('=').substringBefore('-').toLong() > 0 || it.headers["Range"]!!.startsWith("bytes=0-") })
        assertTrue(progress.first().downloaded > 0)
    }

    @Test fun changedEtagRestartsAndSaysSo() = runBlocking {
        val old = testBytes(3 * 1024 * 1024, seed = 1)
        val s = serve(old)
        s.cutNext.set(4)
        try { engine(retries = 0).download(request(s)) {} } catch (e: DownloadFailure) { /* leaves a partial file */ }
        val fresh = testBytes(3 * 1024 * 1024, seed = 2)
        s.content = fresh; s.etag = "\"v2\""
        val done = engine().download(request(s)) {}
        assertTrue(done.restarted)
        assertArrayEquals(fresh, done.file.readBytes())
    }

    @Test fun fullBodyInsteadOfPartialRestartsAndSaysSo() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val s = serve(data)
        s.cutNext.set(4)
        try { engine(retries = 0).download(request(s)) {} } catch (e: DownloadFailure) { /* partial */ }
        s.ignoreRange = true
        val done = engine().download(request(s)) {}
        assertTrue(done.restarted)
        assertArrayEquals(data, done.file.readBytes())
    }

    @Test fun matchingHashPasses() = runBlocking {
        val data = testBytes(50_000)
        val s = serve(data)
        val done = engine().download(request(s, sha = sha256(data).uppercase())) {}
        assertEquals(sha256(data), done.sha256)
    }

    @Test fun wrongHashFailsAndKeepsNoFile() = runBlocking {
        val s = serve(testBytes(50_000))
        try {
            engine().download(request(s, sha = "00".repeat(32))) {}
            fail("hash should not match")
        } catch (e: DownloadFailure.HashMismatch) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun refusesWhenSizeDoesNotFit() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val s = serve(data)
        try {
            engine(free = 1000).download(request(s)) {}
            fail("should refuse")
        } catch (e: DownloadFailure.NoSpace) {
            assertEquals(data.size.toLong(), e.needed)
            assertTrue(e.message!!.contains("3.0 MB"))
        }
        assertEquals(1, s.gets.size) // only the probe
    }

    @Test fun retriesThenSucceeds() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val s = serve(data)
        s.failNext.set(2); s.cutNext.set(1)
        val done = engine().download(request(s)) {}
        assertArrayEquals(data, done.file.readBytes())
    }

    @Test fun givesUpWithTheHttpStatus() = runBlocking {
        val s = serve(testBytes(20_000))
        s.failNext.set(10)
        try {
            engine(retries = 2).download(request(s)) {}
            fail("should fail")
        } catch (e: DownloadFailure.Http) {
            assertEquals(503, e.code)
        }
        assertEquals(4, s.gets.size) // the probe, then the file three times
    }

    @Test fun nameComesFromContentDisposition() {
        assertEquals("a b.apk", DownloadEngine.fileNameOf("""attachment; filename*=UTF-8''a%20b.apk"""))
        assertEquals("x.zip", DownloadEngine.fileNameOf("""attachment; filename="x.zip""""))
        assertEquals("evil.sh", DownloadEngine.safeName("../../evil.sh"))
    }

    private fun other(server: FileServer) = server.server.url("/file.bin").newBuilder().host("127.0.0.1").build().toString()

    @Test fun crossHostRedirectStopsUntilAllowed() = runBlocking {
        val data = testBytes(40_000)
        val target = serve(data)
        val origin = serve(ByteArray(0)).also { it.redirectTo = other(target) }
        try {
            engine().download(request(origin)) {}
            fail("should stop at the other host")
        } catch (e: DownloadFailure.CrossHost) {
            assertEquals("127.0.0.1", e.host)
        }
        assertTrue(target.gets.size <= 1) // nothing was downloaded from it
        val done = engine().download(request(origin).copy(allowedHosts = setOf("127.0.0.1"))) {}
        assertArrayEquals(data, done.file.readBytes())
    }

    @Test fun segmentsGoToTheFinalUrlOnly() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val target = serve(data)
        val origin = serve(ByteArray(0)).also { it.redirectTo = other(target) }
        val done = engine().download(request(origin).copy(allowedHosts = setOf("127.0.0.1"))) {}
        assertArrayEquals(data, done.file.readBytes())
        assertEquals(1, origin.requests.size) // the probe; the 4 segments did not ask it again
        assertTrue(target.gets.size >= 5)
    }

    @Test fun weakEtagWithoutLastModifiedIsNotResumable() = runBlocking {
        val data = testBytes(3 * 1024 * 1024)
        val s = serve(data, etag = "W/\"v1\"")
        s.cutNext.set(4)
        try { engine(retries = 0).download(request(s)) {} } catch (e: DownloadFailure) { /* partial */ }
        s.requests.clear()
        val done = engine().download(request(s)) {}
        assertTrue(done.restarted)
        assertArrayEquals(data, done.file.readBytes())
    }

    @Test fun validatorRules() {
        fun probe(etag: String?, lm: String?) = Probe("u", 1, true, etag, lm, null)
        assertTrue(DownloadEngine.validated(probe("\"a\"", null)))
        assertTrue(DownloadEngine.validated(probe("W/\"a\"", "Mon")))
        assertFalse(DownloadEngine.validated(probe("W/\"a\"", null)))
        assertFalse(DownloadEngine.validated(probe(null, null)))
    }

    @Test fun contentRangeMustMatchTheRequestAndTheSize() {
        assertTrue(DownloadEngine.rangeMatches("bytes 10-19/100", 10, 19, 100))
        assertTrue(DownloadEngine.rangeMatches("bytes 10-19/*", 10, 19, 100))
        assertFalse(DownloadEngine.rangeMatches("bytes 10-19/100", 10, 19, 99)) // the file changed size
        assertFalse(DownloadEngine.rangeMatches("bytes 10-18/100", 10, 19, 100))
        assertFalse(DownloadEngine.rangeMatches("bytes 11-19/100", 10, 19, 100))
        assertFalse(DownloadEngine.rangeMatches(null, 10, 19, 100))
    }

    @Test fun unknownSizeRetryDropsTheOldTail() = runBlocking {
        val first = testBytes(300_000, seed = 1)
        val second = testBytes(100_000, seed = 2)
        val s = serve(first, ranges = false).also { it.chunked = true; it.cutNext.set(1); it.afterCut = second }
        val done = engine().download(request(s)) {}
        assertArrayEquals(second, done.file.readBytes())
    }

    @Test fun usesNoHeadRequest() = runBlocking {
        val s = serve(testBytes(10_000))
        engine().download(request(s)) {}
        assertTrue(s.requests.none { it.method == "HEAD" })
        assertEquals("bytes=0-0", s.requests.first().headers["Range"])
    }

    @Test fun keepsFreeSpaceBeyondTheFile() = runBlocking {
        val data = testBytes(1024 * 1024)
        val s = serve(data)
        try {
            engine(free = data.size + 99L, keepFree = 100).download(request(s)) {}
            fail("should refuse")
        } catch (e: DownloadFailure.NoSpace) {
            assertEquals(data.size + 100L, e.needed)
        }
        assertArrayEquals(data, engine(free = data.size + 100L, keepFree = 100).download(request(s)) {}.file.readBytes())
    }

    @Test fun anotherDownloadsRemainingBytesCount() = runBlocking {
        val big = testBytes(3 * 1024 * 1024)
        val a = serve(big).also { it.slow = true }
        val b = serve(testBytes(2 * 1024 * 1024))
        // Room for either file alone, not for the rest of `big` plus all of `small`.
        val shared = engine(free = big.size + 500L)
        val started = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            runCatching { shared.download(request(a, id = "big", segments = 1)) { if (it.downloaded > 0) started.complete(Unit) } }
        }
        withTimeout(10_000) { started.await() }
        try {
            shared.download(request(b, id = "small")) {}
            fail("the big download still has bytes to write")
        } catch (e: DownloadFailure.NoSpace) { /* expected */ }
        first.cancelAndJoin()
    }

    @Test fun sweepRemovesOnlyOrphanPartFiles() {
        val keep = listOf("k.part", "k.part.json", "plain.apk")
        val drop = listOf("x.part", "x.part.json", "x.part.json.tmp")
        (keep + drop).forEach { File(tmp.root, it).writeText("1") }
        DownloadEngine.sweep(tmp.root, setOf("k"))
        assertEquals(keep.sorted(), tmp.root.list()!!.sorted())
    }

    @Test fun sidecarKeepsNoUrl() = runBlocking {
        val s = serve(testBytes(3 * 1024 * 1024))
        s.cutNext.set(4)
        val req = DownloadRequest("a", s.url("/file.bin?token=SECRET"), tmp.root, segments = 4)
        try { engine(retries = 0).download(req) {} } catch (e: DownloadFailure) { /* partial */ }
        val text = File(tmp.root, "a.part.json").readText()
        assertFalse(text.contains("SECRET") || text.contains("localhost"))
    }
}
