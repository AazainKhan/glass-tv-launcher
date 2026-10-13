package dev.glasslauncher.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P141: the Control Center AirPlay control follows the route (tvOS's Audio pill); P144: the opt-out hint. */
class AirPlayPillTest {
    @Test fun idleAndFailedShowAudioOnThisTv() {
        for (s in listOf(RouteState.Idle, RouteState.Failed("x"))) {
            val p = AirPlayPill.of(s, "Roam 2")
            assertFalse(p.speaker)
            assertEquals("Audio", p.title)
        }
    }

    @Test fun routedStatesShowTheRoomWithTheSpeakerGlyph() {
        val routed = listOf(RouteState.NeedsConsent, RouteState.Capturing, RouteState.StartingSpeaker, RouteState.Playing, RouteState.Blocked("Netflix"))
        for (s in routed) {
            val p = AirPlayPill.of(s, "Roam 2")
            assertTrue(s.toString(), p.speaker)
            assertEquals("Roam 2", p.title)
        }
    }

    @Test fun aBlankRoomFallsBackToSonos() = assertEquals("Sonos", AirPlayPill.of(RouteState.Playing, " ").title)
}
