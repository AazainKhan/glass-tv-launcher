package dev.glasslauncher.dream

import dev.glasslauncher.dream.Screensaver.AlwaysOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnTest {
    @Test fun neverReadsAsOn() {
        assertTrue(AlwaysOn.isOn(-1L)); assertTrue(AlwaysOn.isOn(0L))
        assertFalse(AlwaysOn.isOn(20 * 60_000L)); assertFalse(AlwaysOn.isOn(null))
    }

    @Test fun turningOnWritesNeverAndRemembersTheOldTimeout() {
        val s = AlwaysOn.turnOn(current = 8 * 3_600_000L, saved = null)
        assertEquals(-1L, s.write)
        assertEquals(8 * 3_600_000L, s.remember)
    }

    @Test fun turningOnTwiceKeepsWhatWasRemembered() {
        val s = AlwaysOn.turnOn(current = -1L, saved = 1_800_000L)
        assertEquals(-1L, s.write)
        assertEquals("never overwritten by -1", 1_800_000L, s.remember)
        assertNull("nothing to remember when it was never set by us", AlwaysOn.turnOn(current = -1L, saved = null).remember)
    }

    @Test fun turningOffRestoresTheRememberedTimeoutOrADefault() {
        assertEquals(1_800_000L, AlwaysOn.turnOff(1_800_000L))
        assertEquals(AlwaysOn.DEFAULT_MS, AlwaysOn.turnOff(null))
        assertEquals("a remembered -1 is not a timeout", AlwaysOn.DEFAULT_MS, AlwaysOn.turnOff(-1L))
    }
}
