package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldSettleTest {
    /** A press followed by repeats every [gap] ms for [holdMs]: how many times the settled item (hero, title, bake) changes. */
    private fun settles(holdMs: Int, gap: Int, firstRepeatAt: Int = 400): Int {
        var settledUpdates = 0
        var pendingAt = Long.MAX_VALUE
        var lastRepeat = Long.MIN_VALUE / 2
        var t = 0L
        var item = 0
        // The press at t = 0, then repeats from firstRepeatAt (Android's initial repeat delay), one item per event.
        val events = buildList { add(0L to 0); var r = firstRepeatAt.toLong(); while (r <= holdMs) { add(r to 1); r += gap } }
        for ((time, repeatCount) in events) {
            // A pending settle that came due before this event lands first.
            if (pendingAt <= time) { settledUpdates++; pendingAt = Long.MAX_VALUE }
            t = time
            if (repeatCount > 0) lastRepeat = t
            item++
            val wait = HoldSettle.delayMs(t - lastRepeat)
            if (wait == 0L) { settledUpdates++; pendingAt = Long.MAX_VALUE } else pendingAt = t + wait
        }
        if (pendingAt != Long.MAX_VALUE) settledUpdates++
        return settledUpdates
    }

    @Test fun aSinglePressSettlesAtOnce() {
        assertEquals(0L, HoldSettle.delayMs(Long.MAX_VALUE / 2))
        assertEquals(1, settles(holdMs = 0, gap = 30))
    }

    @Test fun twoSecondsOfHeldRightSettlesOncePerStopNotPerItem() {
        // 2 s at Fire's 50 ms repeat (after the 400 ms first gap) is ~30 focus changes; the hero follows the press and then the item the hold rests on.
        val n = settles(holdMs = 2_000, gap = 50)
        assertTrue("settled $n times", n <= 3)
    }

    @Test fun aSlowRepeatStillCountsAsHeld() {
        assertTrue(HoldSettle.holding(80)); assertTrue(HoldSettle.holding(HoldSettle.HOLD_WINDOW_MS - 1))
        assertFalse(HoldSettle.holding(HoldSettle.HOLD_WINDOW_MS))
        assertFalse(HoldSettle.holding(Long.MAX_VALUE / 2))
    }
}
