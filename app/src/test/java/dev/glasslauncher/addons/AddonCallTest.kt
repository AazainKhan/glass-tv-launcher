package dev.glasslauncher.addons

import dev.glasslauncher.addon.AddonApi
import dev.glasslauncher.addon.AddonCodec
import dev.glasslauncher.addon.AddonHandler
import dev.glasslauncher.addon.AddonRequest
import dev.glasslauncher.addon.AddonServer
import dev.glasslauncher.addon.StateReply
import dev.glasslauncher.addon.TileState
import dev.glasslauncher.addon.TilesReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class AddonCallTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val call = AddonCall(150, scope)
    @After fun stop() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    @Test fun returnsTheReply() = runBlocking {
        assertEquals("ok", call.run("a", { 1 }, { "ok" }))
    }

    @Test fun timeoutIsNullAndTheStuckCallKeepsTheAddonBusy() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = System.nanoTime()
        assertNull(call.run("a", { 1 }, { runBlocking { release.await() }; "late" }))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000) // gave up near the timeout, not when the add-on returned
        assertNull(call.run("a", { 1 }, { "second" })) // still in flight: no second call
        assertEquals("other", call.run("b", { 1 }, { "other" })) // other add-ons are unaffected
        release.complete(Unit)
        delay(100)
        assertEquals("again", call.run("a", { 1 }, { "again" })) // free once it really returned
    }

    @Test fun deadObjectAndConnectErrorsAreNull() = runBlocking {
        assertNull(call.run("a", { 1 }, { throw android.os.DeadObjectException() }))
        assertNull(call.run("a", { error("disconnected") }, { "x" }))
        assertEquals("ok", call.run("a", { 1 }, { "ok" })) // and the add-on is not left busy
    }

    @Test fun neverHangsWhenConnectNeverCompletes() = runBlocking {
        assertNull(call.run("a", { CompletableDeferred<Int>().await() }, { "x" }))
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val job = async { call.run("a", { delay(10_000); 1 }, { "x" }) }
        delay(30)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        // The slot is given back by a 200 ms join inside the cancelled call (AddonClient), so the next call can still be
        // refused if it arrives first: on a loaded machine 30 ms of wall clock is not enough (P304). Wait for the slot.
        var reply: String? = null
        repeat(40) { if (reply == null) { delay(25); reply = call.run("a", { 1 }, { "ok" }) } }
        assertEquals("ok", reply)
    }

    // The add-on side: junk and failures become error replies, a caller without the permission gets nothing.
    private val boom = object : AddonHandler {
        override fun tiles(): List<TileState> = throw IllegalStateException("x")
    }
    private val fine = object : AddonHandler {
        override fun tiles() = listOf(TileState("a", "A"))
    }
    private val req = AddonCodec.encodeRequest(AddonRequest(tile = "a"))

    @Test fun serverTurnsExceptionsIntoErrorReplies() {
        assertEquals("IllegalStateException", AddonCodec.decodeTiles(AddonServer.handle(AddonApi.GET_TILES, req, boom, true))!!.error)
        assertEquals("IllegalStateException", AddonCodec.decodeState(AddonServer.handle(AddonApi.ON_TAP, req, boom, true))!!.error)
    }

    @Test fun serverRefusesWithoutPermissionAndUnknownCodes() {
        assertNull(AddonServer.handle(AddonApi.GET_TILES, req, fine, permitted = false))
        assertNull(AddonServer.handle(4, req, fine, true)) // the dropped long-press code
        assertNull(AddonServer.handle(99, req, fine, true))
        assertNotNull(AddonServer.handle(AddonApi.GET_TILES, req, fine, true))
    }

    @Test fun serverToleratesAJunkRequest() {
        assertEquals(StateReply(null), AddonCodec.decodeState(AddonServer.handle(AddonApi.ON_TAP, "junk", fine, true)))
        assertEquals(listOf(TileState("a", "A")), AddonCodec.decodeTiles(AddonServer.handle(AddonApi.GET_TILES, null, fine, true))!!.tiles)
    }

    @Test fun hostTreatsErrorAndJunkRepliesAsFailures() {
        assertNull(AddonReplies.tiles("not json"))
        assertNull(AddonReplies.tiles(null))
        assertNull(AddonReplies.tiles(AddonCodec.encodeTiles(TilesReply(error = "boom"))))
        assertNull(AddonReplies.state(AddonCodec.encodeState(StateReply(error = "boom")), "a"))
        assertNull(AddonReplies.state(AddonCodec.encodeState(StateReply(TileState("other", "O"))), "a")) // wrong tile
        assertNull(AddonReplies.state(AddonCodec.encodeState(StateReply(null)), "a"))
        assertEquals("A", AddonReplies.state(AddonCodec.encodeState(StateReply(TileState("a", "A"))), "a")!!.label)
    }
}
