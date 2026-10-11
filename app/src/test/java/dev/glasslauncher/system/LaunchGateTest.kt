package dev.glasslauncher.system

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchGateTest {
    @After fun reset() = LaunchGate.clear()

    @Test fun backCancelsUntilTheAppHasTheForeground() {
        LaunchGate.begin("com.example.tv", now = 1_000, wallClock = 50_000)
        assertTrue(LaunchGate.shouldInterrupt(now = 1_500, appInForeground = false))
        assertEquals("com.example.tv", LaunchGate.launching(now = 1_500))
        // A starting window means nothing: only the foreground check closes it (the stick's trace: window event at
        // +324 ms, Back still held by the system until the activity resumed).
        assertFalse("its activity resumed: Back belongs to the app", LaunchGate.shouldInterrupt(now = 2_000, appInForeground = true))
    }

    @Test fun neverAfterTheCap() {
        LaunchGate.begin("a.b", now = 0)
        assertTrue(LaunchGate.shouldInterrupt(now = LaunchGate.WINDOW_MS, appInForeground = false))
        assertFalse(LaunchGate.shouldInterrupt(now = LaunchGate.WINDOW_MS + 1, appInForeground = false))
    }

    @Test fun noLaunchNoInterrupt() {
        assertFalse(LaunchGate.shouldInterrupt(now = 0, appInForeground = false))
        LaunchGate.begin("a.b", now = 100)
        LaunchGate.clear()
        assertFalse(LaunchGate.shouldInterrupt(now = 200, appInForeground = false))
    }

    @Test fun theLaunchTimeIsKeptOnTheWallClockForUsageEvents() {
        LaunchGate.begin("a.b", now = 10, wallClock = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, LaunchGate.launchedAtWall())
    }
}
