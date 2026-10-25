package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P136: the tvOS picker's toggle (select, deselect, switch) and the volume row that never fires on select. */
class OutputToggleTest {
    private val kitchen = AudioOutputConfig("10.0.0.5", "Kitchen")
    private val den = AudioOutputConfig("10.0.0.6", "Den")

    @Test fun selectingTheFirstSpeakerStartsTheRoute() =
        assertEquals(OutputChange(listOf(kitchen), listOf(OutputCommand.Start(listOf(kitchen)))), OutputToggle.click(emptyList(), kitchen))

    @Test fun selectingAnotherSpeakerJoinsTheRouteWithoutRestartingIt() =
        assertEquals(OutputChange(listOf(kitchen, den), listOf(OutputCommand.Add(den))), OutputToggle.click(listOf(kitchen), den))

    @Test fun deselectingOneOfSeveralTakesOnlyThatOneOff() =
        assertEquals(OutputChange(listOf(den), listOf(OutputCommand.Remove(kitchen.host))), OutputToggle.click(listOf(kitchen, den), kitchen))

    @Test fun deselectingTheLastSpeakerStopsTheRoute() =
        assertEquals(OutputChange(emptyList(), listOf(OutputCommand.Stop)), OutputToggle.click(listOf(kitchen), kitchen))

    @Test fun thisTvStopsAnyRouteAndClearsEverySpeaker() =
        assertEquals(OutputChange(emptyList(), listOf(OutputCommand.Stop)), OutputToggle.click(listOf(kitchen, den), null))

    @Test fun thisTvWhileOnThisTvDoesNothing() =
        assertEquals(OutputChange(emptyList(), emptyList()), OutputToggle.click(emptyList(), null))

    @Test fun networkSpeakersMultiSelectAndEveryCheckedOneShowsInThePicker() {
        var current = emptyList<AudioOutputConfig>()
        for (t in listOf(kitchen, den, den, kitchen, null)) {
            current = OutputToggle.click(current, t).choice
            val rows = OutputPicker.build(listOf(SonosSpeaker(kitchen.host, "Kitchen", ""), SonosSpeaker(den.host, "Den", "")), current, emptyList())
            assertEquals(current.size, rows.count { it.network != null && it.selected })
            assertEquals(current.isEmpty(), rows.first().selected)
        }
        assertTrue(current.isEmpty())
    }

    @Test fun loadingTheVolumeNeverSends() {
        val sent = mutableListOf<Int>()
        val m = VolumeRowModel { sent += it }
        m.load(37)
        assertEquals(37, m.volume)
        assertTrue(sent.isEmpty())
    }

    @Test fun nudgeBeforeTheVolumeIsKnownSendsNothing() {
        val sent = mutableListOf<Int>()
        val m = VolumeRowModel { sent += it }
        assertFalse(m.nudge(1))
        assertTrue(sent.isEmpty())
    }

    @Test fun leftAndRightMoveByAStepAndStopAtTheEnds() {
        val sent = mutableListOf<Int>()
        val m = VolumeRowModel(step = 5) { sent += it }
        m.load(98)
        assertTrue(m.nudge(1)); assertFalse(m.nudge(1))
        assertTrue(m.nudge(-1))
        assertEquals(listOf(100, 95), sent)
        assertEquals(95, m.volume)
    }

    @Test fun throttleSendsFirstAtOnceThenOnlyTheLatest() = runTest {
        // Virtual time: the old version used Dispatchers.Default and real delay(), so on a loaded Mac the two delay(20)
        // calls overran the 80 ms window and the "nothing sent yet" assertions failed at random (P291).
        val sent = mutableListOf<Int>()
        val t = ThrottledVolume(backgroundScope, 80) { sent += it }
        t.set(10)
        advanceTimeBy(20)
        assertEquals(listOf(10), sent.toList())
        for (v in listOf(15, 20, 25)) t.set(v)
        advanceTimeBy(20)
        assertEquals(listOf(10), sent.toList())
        advanceTimeBy(250)
        assertEquals(25, sent.last())
        assertTrue("at most first and latest: $sent", sent.size <= 2)
        t.close()
    }
}

/** P136: the consent dialog must not close Control Center, but a real app still does. */
class ConsentGateTest {
    @org.junit.After fun reset() = ConsentGate.end()

    @Test fun systemDialogIsIgnoredOnlyWhileConsentIsPending() {
        assertFalse(ConsentGate.ignores(1_000, launchableApp = false))
        ConsentGate.begin(1_000)
        assertTrue(ConsentGate.ignores(2_000, launchableApp = false))
        ConsentGate.end()
        assertFalse(ConsentGate.ignores(2_000, launchableApp = false))
    }

    @Test fun aRealAppStillClosesControlCenterDuringConsent() {
        ConsentGate.begin(1_000)
        assertFalse(ConsentGate.ignores(2_000, launchableApp = true))
    }

    @Test fun theGateExpires() {
        ConsentGate.begin(1_000)
        assertFalse(ConsentGate.ignores(1_000 + ConsentGate.WINDOW_MS, launchableApp = false))
    }
}
