package dev.glasslauncher.sonos

import dev.glasslauncher.data.AudioOutputConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** P136: the tvOS picker's toggle (select, deselect, switch) and the volume row that never fires on select. */
class OutputToggleTest {
    private val kitchen = AudioOutputConfig("10.0.0.5", "Kitchen")
    private val den = AudioOutputConfig("10.0.0.6", "Den")
    private fun row(s: AudioOutputConfig?, selected: Boolean = false) = AudioOutputRow(s, s?.room ?: "This TV", null, selected)

    @Test fun selectingASpeakerStartsTheRoute() =
        assertEquals(OutputChange(kitchen, listOf(OutputCommand.Start(kitchen))), OutputToggle.click(null, row(kitchen)))

    @Test fun selectingTheSelectedSpeakerStopsAndReturnsToThisTv() =
        assertEquals(OutputChange(null, listOf(OutputCommand.Stop)), OutputToggle.click(kitchen, row(kitchen, true)))

    @Test fun switchingStopsThenStartsTheOtherSpeaker() =
        assertEquals(OutputChange(den, listOf(OutputCommand.Stop, OutputCommand.Start(den))), OutputToggle.click(kitchen, row(den)))

    @Test fun thisTvStopsAnyRoute() =
        assertEquals(OutputChange(null, listOf(OutputCommand.Stop)), OutputToggle.click(kitchen, row(null)))

    @Test fun thisTvWhileOnThisTvDoesNothing() =
        assertEquals(OutputChange(null, emptyList()), OutputToggle.click(null, row(null, true)))

    @Test fun neverMoreThanOneSelected() {
        var current: AudioOutputConfig? = null
        for (r in listOf(row(kitchen), row(den), row(den), row(kitchen), row(null))) {
            current = OutputToggle.click(current, r).choice
            assertTrue(AudioOutputs.rows(listOf(SonosSpeaker(kitchen.host, "Kitchen", ""), SonosSpeaker(den.host, "Den", "")), current).count { it.selected } == 1)
        }
        assertNull(current)
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

    @Test fun throttleSendsFirstAtOnceThenOnlyTheLatest() = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<Int>())
        val t = ThrottledVolume(CoroutineScope(Dispatchers.Default), 80) { sent += it }
        t.set(10)
        delay(20)
        assertEquals(listOf(10), sent.toList())
        for (v in listOf(15, 20, 25)) t.set(v)
        delay(20)
        assertEquals(listOf(10), sent.toList())
        delay(250)
        assertEquals(25, sent.last())
        assertTrue("at most first and latest: $sent", sent.size <= 2)
        t.close()
    }
}
