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

    @Test fun noSpeakersShowsOnlyThisTv() {
        val rows = AudioOutputs.rows(emptyList(), null)
        assertEquals(listOf("This TV"), rows.map { it.title })
        assertTrue(rows.single().selected)
    }

    @Test fun rowsShowRoomAndModelSortedWithThisTvFirst() {
        val rows = AudioOutputs.rows(listOf(kitchen, den, kitchen), null)
        assertEquals(listOf("This TV", "Den", "Kitchen"), rows.map { it.title })
        assertEquals(listOf(null, "Sonos Beam", "Sonos Roam"), rows.map { it.subtitle })
        assertEquals(listOf(true, false, false), rows.map { it.selected })
    }

    @Test fun selectedSpeakerGetsTheCheckmarkAndThisTvLosesIt() {
        val rows = AudioOutputs.rows(listOf(kitchen, den), AudioOutputConfig("10.0.0.6", "Den"))
        assertEquals(listOf("This TV" to false, "Den" to true, "Kitchen" to false), rows.map { it.title to it.selected })
    }

    @Test fun storedChoiceNotFoundStaysListedAsSelected() {
        val rows = AudioOutputs.rows(emptyList(), AudioOutputConfig("10.0.0.9", "Bedroom"))
        assertEquals(listOf("This TV" to false, "Bedroom" to true), rows.map { it.title to it.selected })
        assertEquals("Not found", rows[1].subtitle)
    }

    @Test fun selectionStoresHostAndRoomAndThisTvClearsIt() {
        val row = AudioOutputs.rows(listOf(kitchen), null)[1]
        val cfg = LauncherConfig().copy(audioOutput = AudioOutputs.choice(row))
        assertEquals(AudioOutputConfig("10.0.0.5", "Kitchen"), cfg.audioOutput)
        assertEquals("Sonos output selected. Audio routing comes in the next update.", AudioOutputs.notice(row))
        val tv = AudioOutputs.rows(listOf(kitchen), cfg.audioOutput)[0]
        assertNull(cfg.copy(audioOutput = AudioOutputs.choice(tv)).audioOutput)
        assertNull(AudioOutputs.notice(tv))
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
