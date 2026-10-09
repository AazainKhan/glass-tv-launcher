package dev.glasslauncher.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The lens edge: the middle of a surface is flat glass; the band at each edge shows content from beyond it. */
class LensWarpTest {
    private val length = 400f
    private val band = 12f
    private val beyond = 8f
    private fun x(t: Float) = LensWarp.place(t, length, band, beyond)

    @Test fun theMiddleIsUnbent() {
        for (t in listOf(beyond + band + 1f, 200f, length + beyond - band - 1f)) assertEquals(t - beyond, x(t), 0.01f)
    }

    @Test fun contentBeyondTheEdgeIsDrawnInsideIt() {
        assertEquals(0f, x(0f), 0.01f)
        assertEquals(length, x(length + beyond * 2), 0.01f)
        // The first pixel past the edge lands inside the surface.
        assertTrue(x(beyond - 1f) in 0f..band)
    }

    @Test fun itIsSmoothAndNeverFolds() {
        var last = -1f
        var lastSlope = 0f
        val step = 0.25f
        var t = 0f
        while (t <= length + beyond * 2) {
            val now = x(t)
            assertTrue("folds at $t", now >= last)
            val slope = (now - last) / step
            // No kink where the band meets the flat middle (slope ~1 on both sides).
            if (abs(t - (beyond + band)) < step) assertEquals(1f, slope, 0.15f)
            last = now; lastSlope = slope; t += step
        }
    }

    @Test fun noBandMeansNoBend() {
        for (t in listOf(0f, 50f, 300f)) assertEquals(t, LensWarp.place(t, length, 0f, 0f), 0.01f)
    }
}
