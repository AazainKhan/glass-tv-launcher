package dev.glasslauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTimeTest {
    @Test fun underAnHourIsMinutesAndSeconds() {
        assertEquals("0:00", NowPlayingTime.format(0))
        assertEquals("9:02", NowPlayingTime.format(542_000))
        assertEquals("59:59", NowPlayingTime.format(3_599_999))
    }

    @Test fun anHourOrMoreAddsHours() {
        assertEquals("1:00:00", NowPlayingTime.format(3_600_000))
        assertEquals("2:15:58", NowPlayingTime.format(8_158_000))
    }

    @Test fun negativeClampsToZero() = assertEquals("0:00", NowPlayingTime.format(-5_000))

    @Test fun remainingHasAMinusAndNeverGoesBelowZero() {
        assertEquals("-2:06:56", NowPlayingTime.remaining(8_158_000, 542_000))
        assertEquals("-0:00", NowPlayingTime.remaining(1_000, 5_000))
    }

    @Test fun bothFitWhenThereIsRoomAndTheRemainingDropsWhenNot() {
        assertEquals(NowPlayingTime.Fit.Both, NowPlayingTime.fit(227f, "9:02", "-2:06:56", 13f))
        assertEquals(NowPlayingTime.Fit.ElapsedOnly, NowPlayingTime.fit(90f, "9:02", "-2:06:56", 13f))
    }
}
