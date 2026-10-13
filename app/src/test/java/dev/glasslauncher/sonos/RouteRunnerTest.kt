package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RouteRunnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private class Muter(initial: Boolean = false) : StreamMuter {
        @Volatile var state = initial
        val calls = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        override fun isMuted() = state
        override fun setMuted(muted: Boolean) { state = muted; calls += muted }
    }
    private class Flag(override var heldByGlass: Boolean = false) : MuteFlag

    private class Cap(val openError: Boolean = false, val pcm: ByteArray? = ByteArray(3840) { 3 }, val endAfter: Int = Int.MAX_VALUE) : PcmCapture {
        val released = AtomicInteger(); var reads = 0
        override fun open() { if (openError) error("open failed") }
        override fun read(buf: ByteArray): Int {
            if (reads++ >= endAfter) return -1
            Thread.sleep(2)
            if (pcm == null) return buf.size // all zeros
            System.arraycopy(pcm, 0, buf, 0, buf.size); return buf.size
        }
        override fun release() { released.incrementAndGet() }
    }

    private class Spk(val gate: CompletableDeferred<Unit>? = null, var playing: Boolean = true) : SpeakerSession {
        override val ring = PcmRingBuffer(48_000 * 4)
        val started = AtomicInteger(); val stopped = AtomicInteger()
        override suspend fun start() { started.incrementAndGet(); gate?.await() }
        override suspend fun stop() { stopped.incrementAndGet() }
        override suspend fun isPlaying() = playing
    }

    private val room = AudioOutputConfig("192.168.1.5", "Kitchen")
    private var tornDown = AtomicInteger()

    private fun runner(m: Muter, f: Flag, spk: Spk, app: () -> PlayingApp? = { null }) =
        RouteRunner(scope, m, f, { spk }, app, { tornDown.incrementAndGet() }, checkEveryMs = 20, transportEveryMs = 40)

    private fun RouteRunner.go(cap: PcmCapture, mute: Boolean = true) {
        assertTrue(begin(room, mute)); consentGranted(); run(cap)
    }

    private fun RouteRunner.await(pred: (RouteState) -> Boolean) = runBlocking { withTimeout(5_000) { state.first(pred) } }

    private fun waitUntil(cond: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!cond()) delay(5) } }

    @Test fun muteOnlyAfterRealPcmAndRestoredOnStop() {
        val m = Muter(); val f = Flag(); val spk = Spk()
        val r = runner(m, f, spk)
        r.go(Cap())
        r.await { it == RouteState.Playing }
        waitUntil { m.state }
        assertTrue(f.heldByGlass)
        r.stop()
        waitUntil { spk.stopped.get() >= 1 && !m.state }
        assertFalse(f.heldByGlass); assertEquals(RouteState.Idle, r.state.value)
    }

    @Test fun silentCaptureNeverMutesTheTv() {
        val m = Muter(); val spk = Spk()
        val r = runner(m, Flag(), spk)
        r.go(Cap(pcm = null))
        r.await { it == RouteState.Playing }
        runBlocking { delay(150) }
        assertFalse(m.state); assertTrue(m.calls.isEmpty())
        r.stop(); waitUntil { spk.stopped.get() >= 1 }
    }

    @Test fun openFailureReleasesCaptureAndTearsDown() {
        val cap = Cap(openError = true); val spk = Spk()
        val r = runner(Muter(), Flag(), spk)
        r.go(cap)
        r.await { it is RouteState.Failed }
        waitUntil { cap.released.get() >= 1 && tornDown.get() >= 1 }
        assertEquals(0, spk.started.get())
    }

    @Test fun captureEndStopsTheSessionAndRestoresMute() {
        val m = Muter(); val f = Flag(); val spk = Spk()
        val r = runner(m, f, spk)
        r.go(Cap(endAfter = 30))
        r.await { it is RouteState.Failed }
        waitUntil { spk.stopped.get() >= 1 && !m.state && tornDown.get() >= 1 }
        assertFalse(f.heldByGlass)
    }

    @Test fun speakerDroppingStopsTheRoute() {
        val m = Muter(); val spk = Spk(playing = false)
        val r = runner(m, Flag(), spk)
        val cap = Cap()
        r.go(cap)
        r.await { it is RouteState.Failed }
        waitUntil { spk.stopped.get() >= 1 && cap.released.get() >= 1 }
    }

    @Test fun stopDuringSpeakerStartCleansUpTheHalfStartedSession() {
        val gate = CompletableDeferred<Unit>(); val spk = Spk(gate)
        val m = Muter(); val cap = Cap()
        val r = runner(m, Flag(), spk)
        r.go(cap)
        waitUntil { spk.started.get() == 1 }
        r.stop()
        waitUntil { spk.stopped.get() >= 1 && cap.released.get() >= 1 && tornDown.get() >= 1 }
        assertEquals(RouteState.Idle, r.state.value)
        assertTrue(m.calls.isEmpty()) // never reached Playing, never muted
    }

    @Test fun stopBeforeTheProjectionArrivesTearsDownAndLateCaptureIsReleased() {
        val r = runner(Muter(), Flag(), Spk())
        assertTrue(r.begin(room, false))
        r.stop()
        waitUntil { tornDown.get() >= 1 }
        val late = Cap()
        r.run(late)
        assertEquals(1, late.released.get())
        assertEquals(RouteState.Idle, r.state.value)
    }

    @Test fun optedOutAppBlocksAndGivesTheTvItsSoundBack() {
        val m = Muter(); val spk = Spk()
        val r = runner(m, Flag(), spk) { PlayingApp("Netflix", true, false) }
        r.go(Cap())
        r.await { it is RouteState.Blocked }
        assertEquals(RouteState.Blocked("Netflix"), r.state.value)
        waitUntil { !m.state }
        r.stop(); waitUntil { spk.stopped.get() >= 1 }
    }

    @Test fun recoverMuteUnmutesAfterACrash() {
        val m = Muter(true); val f = Flag(true)
        runner(m, f, Spk()).recoverMute()
        assertFalse(m.state); assertFalse(f.heldByGlass)
    }

    @Test fun localSubnetPickMatchesTheSpeakersNetwork() {
        fun ip(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()
        assertTrue(LocalNet.sameSubnet(ip("192.168.1.20"), ip("192.168.1.99"), 24))
        assertFalse(LocalNet.sameSubnet(ip("192.168.2.20"), ip("192.168.1.99"), 24))
        assertTrue(LocalNet.sameSubnet(ip("10.0.1.2"), ip("10.0.200.9"), 16))
        assertFalse(LocalNet.sameSubnet(ip("10.0.1.2"), ip("10.1.200.9"), 16))
    }
}
