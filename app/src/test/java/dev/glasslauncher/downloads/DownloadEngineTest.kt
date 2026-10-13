package dev.glasslauncher.downloads

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun engine(free: Long = Long.MAX_VALUE, retries: Int = 3) =
        DownloadEngine(freeSpace = { free }, rangeMinBytes = 1 shl 20, retries = retries, backoffMs = { 0 })

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
        assertEquals(sha256(data), done.sha256)
        val ranges = s.gets.mapNotNull { it.headers["Range"] }
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
        assertEquals(1, s.gets.size)
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
        assertTrue(s.gets.isEmpty())
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
        assertEquals(3, s.gets.size)
    }

    @Test fun nameComesFromContentDisposition() {
        assertEquals("a b.apk", DownloadEngine.fileNameOf("""attachment; filename*=UTF-8''a%20b.apk"""))
        assertEquals("x.zip", DownloadEngine.fileNameOf("""attachment; filename="x.zip""""))
        assertEquals("evil.sh", DownloadEngine.safeName("../../evil.sh"))
    }
}
