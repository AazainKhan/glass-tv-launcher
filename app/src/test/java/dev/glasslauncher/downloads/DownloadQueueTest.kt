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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state get() = File(tmp.root, "state/queue.json")
    private val dir get() = File(tmp.root, "files")

    private fun queue() = DownloadQueue(scope, DownloadEngine(backoffMs = { 0 }), dir, state)

    @After fun stop() = scope.cancel()

    @Test fun queueSurvivesARestart() {
        val a = queue()
        val one = a.add("http://localhost/one.bin", name = "one.bin", sha256 = "ab".repeat(32), paused = true)
        a.add("http://localhost/two.bin", paused = true)
        a.setConcurrency(3); a.setSegments(6)
        a.moveToTop(a.items.value[1].id)

        val b = queue()
        b.load()
        assertEquals(a.items.value, b.items.value)
        assertEquals(3, b.concurrency); assertEquals(6, b.segments)
        assertEquals("ab".repeat(32), b.items.value.first { it.id == one }.expectedSha256)
        assertEquals(DownloadState.Paused, b.items.value.first().state)
    }

    @Test fun runningDownloadsComeBackQueued() {
        val server = MockWebServer().also { it.start() }
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        val a = queue()
        a.add(server.url("/x").toString())
        assertEquals(DownloadState.Active, a.items.value.single().state)
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
