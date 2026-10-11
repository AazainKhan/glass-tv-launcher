package dev.glasslauncher.system

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Sleep Timer tile's pure parts (P83 M5): the steps Select goes through, and its line of text. */
class SleepTimerTest {
    @Test fun selectStepsOffFifteenThirtySixtyNinetyOff() {
        var c = 0
        val seen = buildList { repeat(6) { c = SleepTimer.next(c); add(c) } }
        assertEquals(listOf(15, 30, 60, 90, 0, 15), seen)
    }

    @Test fun theLineReadsOffOrTheMinutesLeftRoundedUp() {
        assertEquals("Off", SleepTimer.label(0))
        assertEquals("28 min", SleepTimer.label(27 * 60_000L + 1))
        assertEquals("1 min", SleepTimer.label(1_000))
        assertEquals("90 min", SleepTimer.label(90 * 60_000L))
    }
}
