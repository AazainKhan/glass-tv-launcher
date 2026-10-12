package dev.glasslauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** P109: the jiggle's curve is tvOS 26.6's (HBAppGridView) keyframes, linear between them. */
class JiggleTest {
    @Test fun rotationIsPlusMinusOnePointSixTwoDegreesEvenlySpaced() {
        assertEquals(-1.617f, Jiggle.rotationDeg(0f), 0.001f)
        assertEquals(1.617f, Jiggle.rotationDeg(0.5f), 0.001f)
        assertEquals(-1.617f, Jiggle.rotationDeg(1f), 0.001f)
        assertEquals("linear between keyframes", 0f, Jiggle.rotationDeg(0.25f), 0.001f)
    }

    @Test fun translationPassesThroughTheFiveKeyframes() {
        val xs = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { Jiggle.dx(it) }
        val ys = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { Jiggle.dy(it) }
        assertEquals(listOf(-1f, -1f, -1f, 1f, -1f), xs)
        assertEquals(listOf(1f, -2f, 0f, -1f, 1f), ys)
        assertEquals("linear: halfway from (-1,-1) to (1,...) on x", 0f, Jiggle.dx(0.625f), 0.001f)
    }

    @Test fun theLoopIsSeamless() {
        assertEquals(Jiggle.rotationDeg(0f), Jiggle.rotationDeg(1f), 0.0001f)
        assertEquals(Jiggle.dx(0f), Jiggle.dx(1f), 0.0001f)
        assertEquals(Jiggle.dy(0f), Jiggle.dy(1f), 0.0001f)
    }

    @Test fun periodAndExitAreTheDecodedOnes() {
        assertEquals(325, Jiggle.PERIOD_MS)
        assertEquals("0.0875 s rounded", 88, Jiggle.EXIT_MS)
    }
}
