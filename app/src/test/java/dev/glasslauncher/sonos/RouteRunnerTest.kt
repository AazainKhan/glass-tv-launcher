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
        /** An app that opts out of capture delivers silence; flipping this is how a test plays that app. */
        @Volatile var silent = false
        override fun open() { if (openError) error("open failed") }
        override fun read(buf: ByteArray): Int {
            if (reads++ >= endAfter) return -1
            Thread.sleep(2)
            if (pcm == null || silent) { java.util.Arrays.fill(buf, 0); return buf.size } // all zeros
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

    @Test fun muteToggledOnWhilePlayingEngagesLiveAndOffReleases() {
        val m = Muter(); val f = Flag(); val spk = Spk()
        val r = runner(m, f, spk)
        r.go(Cap(), mute = false)
        r.await { it == RouteState.Playing }
        Thread.sleep(150) // PCM verified, still loud
        assertFalse(m.state); assertFalse(f.heldByGlass)
        r.setMuteTv(true)
        assertTrue(m.state); assertTrue(f.heldByGlass)
        r.setMuteTv(false)
        assertFalse(m.state); assertFalse(f.heldByGlass)
        r.stop()
    }

    @Test fun muteToggledOnBeforePcmIsVerifiedWaitsForPcm() {
        val m = Muter(); val spk = Spk()
        val r = runner(m, Flag(), spk)
        r.go(Cap(pcm = null), mute = false) // silence only
        r.await { it == RouteState.Playing }
        r.setMuteTv(true)
        Thread.sleep(100)
        assertFalse(m.state)
        r.stop()
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
        val cap = Cap()
        // The app opts out once the TV is muted (so PCM was flowing), and from then on its capture is silent, as a real opted-out
        // app's is. Loud PCM after the block would resume the route, which is the runner's job but not what this test is about.
        val r = runner(m, Flag(), spk) { if (m.state) { cap.silent = true; PlayingApp("Netflix", true, false) } else PlayingApp("Netflix", true, null) }
        r.go(cap)
        r.await { it is RouteState.Blocked }
        waitUntil { !m.state }
        assertEquals(RouteState.Blocked("Netflix"), r.state.value)
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

    // --- P148: several network speakers at once -------------------------------------------------------------------

    private class FailSpk : SpeakerSession {
        override val ring: PcmRingBuffer? = null
        val stopped = AtomicInteger()
        override suspend fun start() { error("speaker unreachable") }
        override suspend fun stop() { stopped.incrementAndGet() }
        override suspend fun isPlaying() = false
    }

    private val den = AudioOutputConfig("192.168.1.6", "Den")
    private val bedroom = AudioOutputConfig("192.168.1.7", "Bedroom")

    private fun multi(sessions: Map<String, SpeakerSession>) =
        RouteRunner(scope, Muter(), Flag(), { sessions.getValue(it.host) }, { null }, { tornDown.incrementAndGet() }, checkEveryMs = 20, transportEveryMs = 40)

    private fun RouteRunner.goAll(vararg speakers: AudioOutputConfig, cap: PcmCapture = Cap()) {
        assertTrue(begin(speakers.toList(), false)); consentGranted(); run(cap)
    }

    @Test fun twoSpeakersBothStartAndBothGetTheSamePcm() {
        val a = Spk(); val b = Spk()
        val r = multi(mapOf(room.host to a, den.host to b))
        r.goAll(room, den)
        r.await { it == RouteState.Playing }
        waitUntil { a.ring.available() > 0 && b.ring.available() > 0 }
        r.stop()
        waitUntil { a.stopped.get() >= 1 && b.stopped.get() >= 1 }
        assertEquals(RouteState.Idle, r.state.value)
    }

    @Test fun oneSpeakerThatCannotStartDoesNotStopTheOthers() {
        val ok = Spk(); val bad = FailSpk()
        val r = multi(mapOf(room.host to ok, den.host to bad))
        r.goAll(room, den)
        r.await { it == RouteState.Playing }
        waitUntil { ok.ring.available() > 0 && bad.stopped.get() >= 1 }
        r.stop()
    }

    @Test fun noSpeakerStartingFailsTheRouteWithTheReason() {
        val r = multi(mapOf(room.host to FailSpk(), den.host to FailSpk()))
        r.goAll(room, den)
        val failed = r.await { it is RouteState.Failed } as RouteState.Failed
        assertTrue(failed.reason, failed.reason.contains("unreachable"))
    }

    @Test fun aSpeakerCanBeAddedWhilePlayingAndRemovedWithoutStoppingTheRoute() {
        val a = Spk(); val b = Spk()
        val r = multi(mapOf(room.host to a, den.host to b))
        r.goAll(room)
        r.await { it == RouteState.Playing }
        assertTrue(r.addSpeaker(den))
        assertFalse("already there", r.addSpeaker(den))
        waitUntil { b.started.get() == 1 && b.ring.available() > 0 }
        r.removeSpeaker(den.host)
        waitUntil { b.stopped.get() == 1 }
        assertEquals(RouteState.Playing, r.state.value)
        assertEquals("the first speaker was not touched", 0, a.stopped.get())
        r.stop()
    }

    @Test fun stopWhileASpeakerIsBeingAddedLeavesNoLegBehind() {
        val gate = CompletableDeferred<Unit>()
        val a = Spk(); val b = Spk(gate)
        val r = multi(mapOf(room.host to a, den.host to b))
        r.goAll(room)
        r.await { it == RouteState.Playing }
        assertTrue(r.addSpeaker(den))
        waitUntil { b.started.get() == 1 }
        r.stop()
        waitUntil { a.stopped.get() >= 1 && tornDown.get() >= 1 }
        val stoppedBefore = b.stopped.get()
        gate.complete(Unit) // the add finishes after the route is gone
        waitUntil { b.stopped.get() > stoppedBefore }
        assertEquals(RouteState.Idle, r.state.value)
    }

    @Test fun removingTheLastSpeakerStopsTheRoute() {
        val a = Spk()
        val r = multi(mapOf(room.host to a))
        r.goAll(room)
        r.await { it == RouteState.Playing }
        r.removeSpeaker(room.host)
        r.await { it == RouteState.Idle }
        waitUntil { a.stopped.get() >= 1 && tornDown.get() >= 1 }
    }

    @Test fun addingWhenNothingPlaysIsRefused() {
        val r = multi(mapOf(room.host to Spk()))
        assertFalse(r.addSpeaker(room))
    }

    @Test fun aSpeakerThatStopsPlayingIsDroppedAndTheRouteContinuesUntilNoneIsLeft() {
        val a = Spk(); val b = Spk()
        val r = multi(mapOf(room.host to a, den.host to b))
        r.goAll(room, den)
        r.await { it == RouteState.Playing }
        a.playing = false
        waitUntil { a.stopped.get() >= 1 }
        assertEquals("the other one keeps playing", RouteState.Playing, r.state.value)
        b.playing = false
        val failed = r.await { it is RouteState.Failed } as RouteState.Failed
        assertEquals("speaker stopped", failed.reason)
    }
}
