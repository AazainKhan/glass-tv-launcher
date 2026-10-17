package dev.glasslauncher

import dev.glasslauncher.system.SleepTimer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact alarms need a permission from Android 12; without it the timer must fall back to an inexact alarm instead of throwing. */
class SleepTimerAlarmTest {
    @Test fun beforeAndroid12ExactIsAlwaysAllowed() {
        assertTrue(SleepTimer.exactAllowed(30, false))
        assertTrue(SleepTimer.exactAllowed(28, false))
    }

    @Test fun fromAndroid12ExactNeedsThePermission() {
        assertFalse(SleepTimer.exactAllowed(31, false))
        assertFalse(SleepTimer.exactAllowed(35, false))
        assertTrue(SleepTimer.exactAllowed(34, true))
    }
}
