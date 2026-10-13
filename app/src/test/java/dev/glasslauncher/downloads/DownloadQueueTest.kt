package dev.glasslauncher.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state get() = File(tmp.root, "state/queue.json")
    private val dir get() = File(tmp.root, "files")

    private var worked = 0
    private fun queue(cleartext: Boolean = true) =
        DownloadQueue(scope, DownloadEngine(backoffMs = { 0 }, keepFreeBytes = 0), dir, state, onWork = { worked++ }, saveDelayMs = 0, allowCleartext = cleartext)

    @After fun stop() = scope.cancel()

    @Test fun queueSurvivesARestart() = runBlocking {
        val a = queue()
        val one = a.add("http://localhost/one.bin", name = "one.bin", sha256 = "ab".repeat(32), paused = true)
        a.add("http://localhost/two.bin", paused = true)
        a.setConcurrency(3); a.setSegments(6)
        a.moveToTop(a.items.value[1].id)

        a.flush()
        val b = queue()
        b.load()
        assertEquals(a.items.value, b.items.value)
        assertEquals(3, b.concurrency); assertEquals(6, b.segments)
        assertEquals("ab".repeat(32), b.items.value.first { it.id == one }.expectedSha256)
        assertEquals(DownloadState.Paused, b.items.value.first().state)
    }

    @Test fun onlyHttpsLinksCanBeAdded() {
        val q = queue(cleartext = false)
        for (bad in listOf("http://example.com/a.bin", "ftp://example.com/a", "not a url")) {
            try { q.add(bad, paused = true); fail("accepted $bad") } catch (e: IllegalArgumentException) { /* expected */ }
        }
        q.add("https://example.com/a.bin", paused = true)
        assertEquals(1, q.items.value.size)
    }

    @Test fun pauseAllLeavesTheNextOneAlone() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        val q = queue().also { it.setConcurrency(1) }
        q.add(server.url("/a").toString())
        q.add(server.url("/b").toString())
        assertEquals(listOf(DownloadState.Active, DownloadState.Queued), q.items.value.map { it.state })
        assertEquals(1, worked)
        q.pauseAll()
        assertEquals(listOf(DownloadState.Paused, DownloadState.Paused), q.items.value.map { it.state })
        assertEquals(1, worked) // the second was never started, so no service start came from a paused queue
        server.close()
    }

    @Test fun aFinishedFileIsKeptEvenIfPauseWonTheRace() {
        val q = queue()
        val id = q.add("https://example.com/a.bin", paused = true)
        q.finish(id, Finished(File(dir, "a.bin"), 5, null, "https://example.com/a.bin", false))
        val item = q.items.value.single()
        assertEquals(DownloadState.Done, item.state)
        assertEquals("a.bin", item.file)
        assertEquals(5L, item.downloaded)
    }

    @Test fun removeFinishedDropsDoneAndFailedAndTheirFiles() = runBlocking {
        val server = FileServer(testBytes(20_000))
        val q = queue()
        q.add(server.url("/one.bin")); q.add(server.url("/two.bin"))
        val done = withTimeout(20_000) { q.items.first { list -> list.size == 2 && list.all { it.state == DownloadState.Done } } }
        val files = done.map { File(dir, it.file!!) }
        val running = q.add("https://example.com/later.bin", paused = true)
        q.removeFinished(deleteFiles = true)
        assertEquals(listOf(running), q.items.value.map { it.id })
        withTimeout(5_000) { while (files.any { it.exists() }) delay(20) }
        server.stop()
    }

    @Test fun crossHostRedirectWaitsForTheUserThenContinues() = runBlocking {
        val target = FileServer(testBytes(30_000))
        val origin = FileServer(ByteArray(0)).also {
            it.redirectTo = target.server.url("/f.bin").newBuilder().host("127.0.0.1").build().toString()
        }
        val q = queue()
        val id = q.add(origin.url())
        val stopped = withTimeout(20_000) { q.items.first { it.single().state == DownloadState.Failed } }.single()
        assertEquals("127.0.0.1", stopped.blockedHost)
        q.allowRedirect(id, "127.0.0.1")
        val done = withTimeout(20_000) { q.items.first { it.single().state == DownloadState.Done } }.single()
        assertEquals(30_000L, File(dir, done.file!!).length())
        origin.stop(); target.stop()
    }

    @Test fun runningDownloadsComeBackQueued() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        val a = queue()
        a.add(server.url("/x").toString())
        assertEquals(DownloadState.Active, a.items.value.single().state)
        runBlocking { a.flush() }
        val b = queue()
        b.load()
        assertEquals(DownloadState.Queued, b.items.value.single().state)
        a.remove(a.items.value.single().id)
        server.close()
    }

    @Test fun finishesQueuedDownloadsWithinConcurrency() = runBlocking {
        val data = testBytes(40_000)
        val server = FileServer(data)
        val q = queue()
        repeat(3) { q.add(server.url("/f$it.bin")) }
        val done = withTimeout(20_000) { q.items.first { list -> list.all { it.state == DownloadState.Done } } }
        done.forEach { assertArrayEquals(data, File(dir, it.file!!).readBytes()) }
        q.remove(done[0].id)
        val gone = File(dir, done[0].file!!)
        withTimeout(5_000) { while (gone.exists()) delay(20) }
        assertFalse(gone.exists())
        server.stop()
    }

    @Test fun failureIsReportedAndRetryWorks() = runBlocking {
        val server = FileServer(testBytes(10_000))
        server.failNext.set(100)
        val q = queue()
        val id = q.add(server.url())
        val failed = withTimeout(20_000) { q.items.first { it.single().state == DownloadState.Failed } }
        assertEquals("Server answered HTTP 503", failed.single().error)
        server.failNext.set(0)
        q.resume(id)
        withTimeout(20_000) { q.items.first { it.single().state == DownloadState.Done } }
        server.stop()
    }
}
