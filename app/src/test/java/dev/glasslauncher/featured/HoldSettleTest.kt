package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldSettleTest {
    /** A press followed by repeats every [gap] ms for [holdMs]: how many times the settled item (hero, title, bake) changes. */
    private fun settles(holdMs: Int, gap: Int, firstRepeatAt: Int = 500): Int {
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
        // 2 s at a 30 ms repeat is ~50 focus changes; the hero follows the press and then the item the hold rests on.
        val n = settles(holdMs = 2_000, gap = 30)
        assertTrue("settled $n times", n <= 3)
    }

    @Test fun aSlowRepeatStillCountsAsHeld() {
        assertTrue(HoldSettle.holding(80)); assertTrue(HoldSettle.holding(HoldSettle.HOLD_WINDOW_MS - 1))
        assertFalse(HoldSettle.holding(HoldSettle.HOLD_WINDOW_MS))
        assertFalse(HoldSettle.holding(Long.MAX_VALUE / 2))
    }

    @Test fun theCoverFlowStepsAtASteadyPaceWhileAKeyRepeats() {
        assertTrue("a press always steps", HoldSettle.acceptStep(0, 0))
        assertFalse("a repeat right after a step is dropped", HoldSettle.acceptStep(3, 30))
        assertTrue(HoldSettle.acceptStep(3, HoldSettle.REPEAT_STEP_MS))
        // 2 s of repeats every 30 ms: about 22 steps, not 66.
        var last = -1_000L; var steps = 0
        var t = 500L
        while (t <= 2_500) { if (HoldSettle.acceptStep(1, t - last)) { steps++; last = t }; t += 30 }
        assertTrue("took $steps steps", steps in 18..24)
    }
}
