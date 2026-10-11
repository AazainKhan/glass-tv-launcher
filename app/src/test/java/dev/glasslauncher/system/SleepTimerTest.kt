package dev.glasslauncher.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Sleep Timer tile's pure parts (P83 M5): the steps Select goes through, and its line of text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
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

    @Test fun theWarningIsOnlyBetweenTheBannerAndSleep() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("sleepTimer", 0)
        prefs.edit().putLong("endAt", 10_000L).putBoolean("warned", true).apply()
        assertEquals(true, SleepTimer.warning(ctx, now = 5_000L))
        assertEquals("after the end it is over", false, SleepTimer.warning(ctx, now = 10_001L))
        prefs.edit().putBoolean("warned", false).apply()
        assertEquals("before the banner it isn't a warning", false, SleepTimer.warning(ctx, now = 5_000L))
    }
}
