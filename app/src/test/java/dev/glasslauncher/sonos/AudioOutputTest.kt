package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import dev.glasslauncher.data.LauncherConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputTest {
    private val kitchen = SonosSpeaker("10.0.0.5", "Kitchen", "Sonos Roam")
    private val den = SonosSpeaker("10.0.0.6", "Den", "Sonos Beam")

    @Test fun theLegacySingleSpeakerIsReadAsTheSpeakersList() {
        assertEquals(listOf(AudioOutputConfig("10.0.0.5", "Kitchen")), LauncherConfig().copy(audioOutput = AudioOutputConfig("10.0.0.5", "Kitchen")).speakers)
        assertEquals(listOf(AudioOutputConfig("10.0.0.6", "Den")), LauncherConfig().copy(audioOutput = AudioOutputConfig("10.0.0.5", "Kitchen"), audioOutputs = listOf(AudioOutputConfig("10.0.0.6", "Den"))).speakers)
        assertTrue(LauncherConfig().speakers.isEmpty())
    }

    @Test fun theAudioPillNamesOneRoomOrCountsTheRest() {
        assertNull(AirPlayPill.roomLabel(emptyList()))
        assertEquals("Kitchen", AirPlayPill.roomLabel(listOf(AudioOutputConfig("10.0.0.5", "Kitchen"))))
        assertEquals("Kitchen +1", AirPlayPill.roomLabel(listOf(AudioOutputConfig("10.0.0.5", "Kitchen"), AudioOutputConfig("10.0.0.6", "Den"))))
    }

    @Test fun scanIsThrottledAndFailureKeepsCache() = runBlocking<Unit> {
        var now = 1_000L
        var calls = 0
        var fail = false
        val s = ThrottledScan(10_000L, emptyList<SonosSpeaker>(), { now }) {
            calls++
            if (fail) error("no network")
            listOf(kitchen)
        }
        assertEquals(emptyList<SonosSpeaker>(), s.cached)
        assertEquals(listOf(kitchen), s.refresh())
        now += 9_999; s.refresh()
        assertEquals(1, calls)
        now += 1; fail = true
        assertEquals(listOf(kitchen), s.refresh())
        assertEquals(2, calls)
        now += 5_000; s.refresh()
        assertEquals(2, calls)
    }
}
