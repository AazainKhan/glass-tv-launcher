package dev.glasslauncher.featured

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverAutoplayTest {
    @Test fun startsInTheMiddleWithCoversOnBothSides() {
        assertEquals(5, CoverAutoplay.startIndex(10))
        assertEquals(2, CoverAutoplay.startIndex(5))
        assertEquals(0, CoverAutoplay.startIndex(1))
        assertEquals(0, CoverAutoplay.startIndex(0))
    }

    @Test fun stepsOneCoverAtATimeAndTurnsAtTheEnds() {
        var i = 3; var d = 1
        val seen = buildList { repeat(8) { val (ni, nd) = CoverAutoplay.next(i, d, 5); i = ni; d = nd; add(i) } }
        // 4, then the end: turns back 3, 2, 1, 0, then forward again 1, 2, 3.
        assertEquals(listOf(4, 3, 2, 1, 0, 1, 2, 3), seen)
    }

    @Test fun neverJumpsMoreThanOneCover() {
        var i = 0; var d = -1
        repeat(40) { val (ni, nd) = CoverAutoplay.next(i, d, 7); assert(kotlin.math.abs(ni - i) == 1); i = ni; d = nd }
        assertEquals(0, CoverAutoplay.next(0, 1, 1).first)
    }
}

class TopShelfIdleTest {
    @org.junit.Test fun autoplayHoldsAfterAMinuteWithNoInputAndResumesOnAKey() {
        org.junit.Assert.assertEquals(false, TopShelfIdle.paused(0))
        org.junit.Assert.assertEquals(false, TopShelfIdle.paused(59_999))
        org.junit.Assert.assertEquals(true, TopShelfIdle.paused(60_000))
        org.junit.Assert.assertEquals(true, TopShelfIdle.paused(3_600_000))
        // A key resets the idle clock to ~0: not paused again.
        org.junit.Assert.assertEquals(false, TopShelfIdle.paused(12))
    }

    @org.junit.Test fun theResumePollIsCheapNotABakeLoop() {
        org.junit.Assert.assertTrue(TopShelfIdle.POLL_MS >= 1_000L)
    }
}
