package dev.glasslauncher.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P80: the Aerial activity must not end itself when the TV is set to never sleep. */
class ScreensaverTimeoutTest {
    @Test fun neverMeansNoTimer() {
        assertNull(Screensaver.keepAwakeMs(-1L))
        assertNull(Screensaver.keepAwakeMs(0L))
    }

    @Test fun anExplicitTimeoutIsHonouredEvenEightHours() {
        assertEquals(8 * 3_600_000L, Screensaver.keepAwakeMs(8 * 3_600_000L))
        assertEquals(29_160_000L, Screensaver.keepAwakeMs(29_160_000L))
        assertEquals(10 * 3_600_000L, Screensaver.keepAwakeMs(10 * 3_600_000L))
    }

    @Test fun theFiveMinuteFloorOnlyAppliesToPositiveValues() {
        assertEquals(5 * 60_000L, Screensaver.keepAwakeMs(60_000L))
        assertEquals(20 * 60_000L, Screensaver.keepAwakeMs(20 * 60_000L))
    }

    @Test fun unreadableFallsBackToTwentyMinutes() {
        assertEquals(20 * 60_000L, Screensaver.keepAwakeMs(null))
    }
}
