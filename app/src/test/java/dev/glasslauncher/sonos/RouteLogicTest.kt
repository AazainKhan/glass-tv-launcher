package dev.glasslauncher.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLogicTest {
    private fun run(vararg e: RouteEvent) = e.fold<RouteEvent, RouteState>(RouteState.Idle) { s, ev -> RouteLifecycle.reduce(s, ev) }

    @Test fun happyPathIsConsentCaptureSpeaker() {
        assertEquals(RouteState.Playing, run(RouteEvent.Start, RouteEvent.ConsentGranted, RouteEvent.CaptureReady, RouteEvent.SpeakerStarted))
    }

    @Test fun speakerCannotStartBeforeConsentAndCapture() {
        assertEquals(RouteState.NeedsConsent, run(RouteEvent.Start, RouteEvent.SpeakerStarted, RouteEvent.CaptureReady))
        assertEquals(RouteState.Idle, run(RouteEvent.SpeakerStarted, RouteEvent.ConsentGranted))
    }

    @Test fun deniedConsentGoesIdleAndStopWorksFromAnywhere() {
        assertEquals(RouteState.Idle, run(RouteEvent.Start, RouteEvent.ConsentDenied))
        assertEquals(RouteState.Idle, run(RouteEvent.Start, RouteEvent.ConsentGranted, RouteEvent.Stop))
        assertEquals(RouteState.Idle, run(RouteEvent.Start, RouteEvent.ConsentGranted, RouteEvent.CaptureReady, RouteEvent.SpeakerStarted, RouteEvent.Stop))
    }

    @Test fun startWhileActiveIsIgnoredButRestartAfterFailureWorks() {
        val s = run(RouteEvent.Start, RouteEvent.ConsentGranted, RouteEvent.Start)
        assertEquals(RouteState.Capturing, s)
        val failed = run(RouteEvent.Start, RouteEvent.Error("x"))
        assertEquals(RouteState.NeedsConsent, RouteLifecycle.reduce(failed, RouteEvent.Start))
    }

    @Test fun optedOutAppBlocksAndKeepsTheTvThenResumes() {
        val play = arrayOf(RouteEvent.Start, RouteEvent.ConsentGranted, RouteEvent.CaptureReady, RouteEvent.SpeakerStarted)
        val blocked = run(*play, RouteEvent.OptedOut("Netflix"))
        assertEquals(RouteState.Blocked("Netflix"), blocked)
        assertEquals("Netflix doesn't allow playing on other speakers", RouteLifecycle.label(blocked))
        assertEquals(RouteState.Playing, RouteLifecycle.reduce(blocked, RouteEvent.AudioResumed))
        assertEquals(RouteState.Idle, run(RouteEvent.OptedOut("X")))
    }

    @Test fun optOutDecision() {
        assertTrue(CapturePolicy.blocked(false, true, 0))
        assertFalse(CapturePolicy.blocked(true, true, 1_000))
        assertTrue(CapturePolicy.blocked(true, true, CapturePolicy.SILENCE_LIMIT_MS))
        assertTrue(CapturePolicy.blocked(null, true, CapturePolicy.SILENCE_LIMIT_MS))
        assertFalse(CapturePolicy.blocked(null, true, CapturePolicy.SILENCE_LIMIT_MS - 1))
        assertFalse(CapturePolicy.blocked(false, false, 99_999)) // nothing playing: silence is normal
    }

    @Test fun silenceDetectorCountsZerosAndResetsOnSound() {
        var t = 0L
        val d = SilenceDetector { t }
        d.onPcm(ByteArray(8), 8); t = 3_000
        assertEquals(3_000, d.silentMs())
        d.onPcm(byteArrayOf(0, 1, 0, 0), 4)
        assertEquals(0, d.silentMs())
    }

    private class Fake(private var state: Boolean) : StreamMuter {
        var sets = mutableListOf<Boolean>()
        override fun isMuted() = state
        override fun setMuted(muted: Boolean) { state = muted; sets += muted }
    }

    @Test fun muteIsOffByDefaultAndRestoredOnRelease() {
        val m = Fake(false); val g = TvMuteGuard(m)
        g.engage(false); assertEquals(emptyList<Boolean>(), m.sets)
        g.engage(true); assertTrue(m.isMuted())
        g.release(); g.release()
        assertEquals(listOf(true, false), m.sets)
    }

    @Test fun alreadyMutedTvStaysMuted() {
        val m = Fake(true); val g = TvMuteGuard(m)
        g.engage(true); g.release()
        assertTrue(m.isMuted()); assertEquals(emptyList<Boolean>(), m.sets)
    }
}
