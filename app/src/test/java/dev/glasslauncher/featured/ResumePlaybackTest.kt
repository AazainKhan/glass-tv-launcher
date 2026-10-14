package dev.glasslauncher.featured

import dev.glasslauncher.featured.ResumePlayback.Decision
import dev.glasslauncher.featured.ResumePlayback.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePlaybackTest {
    private fun d(since: Long, window: Long?, s: Session) = ResumePlayback.decide(since, window, s)

    @Test fun waitsForTheAppsWindow() {
        assertEquals(Decision.Wait, d(1_000, null, Session.Paused))
        assertEquals(Decision.Wait, d(1_000, null, Session.None))
    }

    @Test fun anAppThatAlreadyPlaysGetsNoKey() {
        assertEquals(Decision.Nothing, d(300, null, Session.Playing))
        assertEquals(Decision.Nothing, d(2_000, 500, Session.Playing))
    }

    @Test fun aPausedSessionGetsMediaPlayOnceTheWindowHasSettled() {
        assertEquals(Decision.Wait, d(900, 500, Session.Paused))
        assertEquals(Decision.MediaPlay, d(500 + ResumePlayback.WINDOW_SETTLE_MS, 500, Session.Paused))
    }

    @Test fun noSessionMeansSelectOnTheFocusedControlAfterTheGrace() {
        val at = 500L
        assertEquals(Decision.Wait, d(at + ResumePlayback.WINDOW_SETTLE_MS, at, Session.None))
        assertEquals(Decision.Select, d(at + ResumePlayback.WINDOW_SETTLE_MS + ResumePlayback.SESSION_GRACE_MS, at, Session.None))
    }

    @Test fun givesUpAtTheTimeoutWithNoKey() {
        assertEquals(Decision.GiveUp, d(ResumePlayback.TIMEOUT_MS, null, Session.None))
        assertEquals(Decision.GiveUp, d(ResumePlayback.TIMEOUT_MS + 10, 3_900, Session.Paused))
    }

    @Test fun aSessionThatTurnsUpLateStillGetsOnlyMediaPlayNotSelect() {
        // No session at the select time would be Select; a paused one seen first wins.
        val at = 300L
        assertEquals(Decision.MediaPlay, d(at + ResumePlayback.WINDOW_SETTLE_MS + 100, at, Session.Paused))
    }
}
