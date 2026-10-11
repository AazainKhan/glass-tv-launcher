package dev.glasslauncher.system

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchGateTest {
    @After fun reset() = LaunchGate.clear()

    @Test fun backCancelsOnlyUntilTheFirstWindowAppears() {
        LaunchGate.begin("com.example.tv", now = 1_000)
        assertTrue(LaunchGate.interruptible(now = 1_500))
        assertEquals("com.example.tv", LaunchGate.launching(now = 1_500))
        LaunchGate.windowShown("com.example.tv", now = 2_000)
        assertFalse("its window is up: Back belongs to the app", LaunchGate.interruptible(now = 2_100))
    }

    @Test fun aTrampolineWindowClosesTheGateToo() {
        LaunchGate.begin("com.amazon.firebat", now = 0)
        LaunchGate.windowShown("com.amazon.avod.thirdpartyclient", now = 900)
        assertFalse(LaunchGate.interruptible(now = 1_000))
    }

    @Test fun neverAfterTheWindowOfSixSeconds() {
        LaunchGate.begin("a.b", now = 0)
        assertTrue(LaunchGate.interruptible(now = LaunchGate.WINDOW_MS))
        assertFalse(LaunchGate.interruptible(now = LaunchGate.WINDOW_MS + 1))
    }

    @Test fun noLaunchNoInterrupt() {
        assertFalse(LaunchGate.interruptible(now = 0))
        LaunchGate.begin("a.b", now = 100)
        LaunchGate.clear()
        assertFalse(LaunchGate.interruptible(now = 200))
    }

    @Test fun aLateWindowAfterTheGateExpiredDoesNotReopenIt() {
        LaunchGate.begin("a.b", now = 0)
        LaunchGate.windowShown("a.b", now = 7_000)   // too late to matter
        assertFalse(LaunchGate.interruptible(now = 7_100))
    }
}
