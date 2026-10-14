package dev.glasslauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarqueeRuleTest {
    @Test fun scrollsOnlyWhenFocusedAndTooLong() {
        assertEquals(true, marqueeScrolls(overflows = true, active = true, reduceMotion = false))
        assertEquals("not focused: the ellipsis", false, marqueeScrolls(overflows = true, active = false, reduceMotion = false))
        assertEquals("fits: nothing to scroll", false, marqueeScrolls(overflows = false, active = true, reduceMotion = false))
        assertEquals("reduced motion: still", false, marqueeScrolls(overflows = true, active = true, reduceMotion = true))
    }
}
