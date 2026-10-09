package dev.glasslauncher

import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Rect
import dev.glasslauncher.home.CcMorph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CcMorphTest {
    private val pill = Rect(1662f, 44f, 1876f, 108f)
    private val panel = Rect(1300f, 40f, 1880f, 1000f)

    private fun assertRect(want: Rect, got: Rect) {
        assertEquals(want.left, got.left, 0.01f); assertEquals(want.top, got.top, 0.01f)
        assertEquals(want.right, got.right, 0.01f); assertEquals(want.bottom, got.bottom, 0.01f)
    }

    @Test fun startsExactlyOnThePillAndEndsOnThePanel() {
        assertRect(pill, CcMorph.rect(0f, pill, panel))
        assertRect(panel, CcMorph.rect(1f, pill, panel))
    }

    // The bubble stretches out of the pill: its width grows with the open and its sides, top and bottom travel
    // linearly to the panel's, except that the bottom lags (the capsule stretches down late, so it is never a disc).
    @Test fun stretchesOutOfThePill() {
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
            val r = CcMorph.rect(t, pill, panel)
            assertEquals(pill.left + (panel.left - pill.left) * t, r.left, 0.01f)
            assertEquals(pill.right + (panel.right - pill.right) * t, r.right, 0.01f)
            assertEquals(pill.top + (panel.top - pill.top) * t, r.top, 0.01f)
            assertEquals(pill.width + (panel.width - pill.width) * t, r.width, 0.01f)
            assertTrue("t=$t the bottom lags the linear path", r.bottom < pill.bottom + (panel.bottom - pill.bottom) * t - 1f)
            assertTrue("t=$t the bubble only grows", r.height >= pill.height - 0.01f)
        }
    }

    // The settle bounce overshoots a little; a spring undershooting on close never gives a negative size.
    @Test fun overshootGrowsPastThePanelAndNeverGoesNegative() {
        assertTrue(CcMorph.rect(1.03f, pill, panel).width > panel.width)
        val under = CcMorph.rect(-0.05f, pill, panel)
        assertEquals(pill.width, under.width, 0.01f)
        assertTrue(under.height >= 0f)
    }

    // Very round throughout (a capsule), reaching the tiles' radius only at the end.
    @Test fun cornersStayCapsuleRoundUntilLate() {
        assertEquals(pill.height / 2, CcMorph.radius(0f, CcMorph.rect(0f, pill, panel), 26f), 0.01f)
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.6f)) {
            val r = CcMorph.rect(t, pill, panel)
            assertTrue("t=$t radius ${CcMorph.radius(t, r, 26f)} of capsule ${minOf(r.width, r.height) / 2}",
                CcMorph.radius(t, r, 26f) >= 0.85f * minOf(r.width, r.height) / 2)
        }
        assertEquals(26f, CcMorph.radius(1f, panel, 26f), 0.01f)
        assertEquals(26f, CcMorph.radius(1.03f, panel, 26f), 0.01f)
        // Never more than a capsule's half (a radius past it would draw a pinched shape).
        assertTrue(CcMorph.radius(0.05f, CcMorph.rect(0.05f, pill, panel), 26f) <= pill.height)
    }

    // Closing squeezes the bubble to ~96% of its height on the way back into the pill; opening doesn't.
    @Test fun closingSqueezesVerticallyToAboutNinetySixPercent() {
        assertEquals(1f, CcMorph.squeeze(0.5f, closing = false), 0f)
        assertEquals(1f, CcMorph.squeeze(1f, closing = true), 0.0001f)
        assertEquals(1f, CcMorph.squeeze(0f, closing = true), 0.0001f)
        val lowest = (0..100).minOf { CcMorph.squeeze(it / 100f, closing = true) }
        assertEquals(0.96f, lowest, 0.005f)
        val squeezed = CcMorph.rect(0.4f, pill, panel, CcMorph.squeeze(0.4f, closing = true))
        assertTrue(squeezed.height < CcMorph.rect(0.4f, pill, panel).height)
    }

    // Closing runs the open's path back: the bubble is the spring value, never past where the close began.
    @Test fun closingRunsTheBubbleBackAndReversingMidOpenDoesNotJump() {
        assertEquals(0.5f, CcMorph.bubble(0.5f, closing = false), 0f)
        assertEquals(0.5f, CcMorph.bubble(0.5f, closing = true), 0f)
        for (from in listOf(0.2f, 0.5f, 0.75f, 0.9f, 1f)) {
            assertEquals("bubble from $from", CcMorph.bubble(from, closing = false), CcMorph.bubble(from, closing = true, from = from), 0.0001f)
            // The bounce's overshoot never makes a close start past where it began.
            assertTrue(CcMorph.bubble(1.04f, closing = true, from = from) <= from + 0.0001f)
        }
        assertEquals(0f, CcMorph.bubble(0f, closing = true), 0f)
        assertEquals(0f, CcMorph.bubble(-0.02f, closing = true), 0f)
        for (from in listOf(0.2f, 0.5f, 0.75f, 1f)) {
            assertEquals("squeeze from $from", 1f, CcMorph.squeeze(CcMorph.bubble(from, true, from), true, from), 0.0001f)
            assertEquals(0.96f, (0..100).minOf { CcMorph.squeeze(from * it / 100f, true, from) }, 0.005f)
        }
    }

    // Springs (user spec): open 0.76 / 140 with a slight settle bounce; close 0.92 / 225, landing without a wobble.
    @Test fun springsOvershootSlightlyOnOpenAndLandOnClose() {
        fun path(spec: androidx.compose.animation.core.SpringSpec<Float>, from: Float, to: Float): Pair<List<Float>, Long> {
            val v = spec.vectorize(Float.VectorConverter)
            val a = androidx.compose.animation.core.AnimationVector1D(from); val b = androidx.compose.animation.core.AnimationVector1D(to)
            val z = androidx.compose.animation.core.AnimationVector1D(0f)
            val ms = v.getDurationNanos(a, b, z) / 1_000_000
            return (0..ms step 4).map { v.getValueFromNanos(it * 1_000_000, a, b, z).value } to ms
        }
        val (open, openMs) = path(CcMorph.openSpring, 0f, 1f)
        val peak = open.max()
        assertTrue("open peak $peak should bounce a little", peak in 1.01f..1.06f)
        assertEquals(1f, open.last(), 0.002f)
        val (close, closeMs) = path(CcMorph.closeSpring, 1f, 0f)
        assertTrue("close undershoot ${close.min()}", close.min() > -0.02f)
        assertEquals(0f, close.last(), 0.002f)
        // The window and the in-launcher overlay are removed once the close has visibly landed (within 1% of the
        // pill), cutting the spring's invisible tail: CLOSE_MS is that time, to within a frame or two.
        val landedMs = close.indexOfFirst { it <= 0.01f } * 4L
        assertTrue("CLOSE_MS ${CcMorph.CLOSE_MS} < visible landing $landedMs", CcMorph.CLOSE_MS >= landedMs)
        assertTrue("CLOSE_MS ${CcMorph.CLOSE_MS} keeps the invisible tail (landing $landedMs, settle $closeMs)", CcMorph.CLOSE_MS <= landedMs + 40)
        assertTrue("open settles in ${openMs} ms", openMs < 1000)
    }

    // The first frames are the pill's capsule stretching, never a round disc: while the bubble is less than a
    // quarter open its aspect stays within 25% of the pill's (the user's phone video: "a dark round disc").
    @Test fun earlyBubbleKeepsThePillsCapsuleAspect() {
        val aspect = pill.width / pill.height
        for (i in 0..25) {
            val t = i / 100f
            val r = CcMorph.rect(t, pill, panel)
            val got = r.width / r.height
            assertTrue("t=$t aspect $got vs the pill's $aspect", got in aspect * 0.75f..aspect * 1.25f)
        }
        // Along the real open spring, the frames before 25% progress are the same capsule.
        val v = CcMorph.openSpring.vectorize(Float.VectorConverter)
        val a = androidx.compose.animation.core.AnimationVector1D(0f); val b = androidx.compose.animation.core.AnimationVector1D(1f)
        val z = androidx.compose.animation.core.AnimationVector1D(0f)
        var seen = 0
        for (ms in 0..300 step 4) {
            val e = v.getValueFromNanos(ms * 1_000_000L, a, b, z).value
            if (e > 0.25f) break
            val r = CcMorph.rect(CcMorph.bubble(e, closing = false), pill, panel)
            assertTrue("at $ms ms (e=$e) the bubble is ${r.width} x ${r.height}", r.width / r.height >= aspect * 0.75f)
            seen++
        }
        assertTrue("the spring has early frames to check", seen >= 3)
        // It still ends as the tall panel.
        assertRect(panel, CcMorph.rect(1f, pill, panel))
    }

    // The bubble stays attached to the pill: its top and right edges leave the pill's smoothly, no drift away from it.
    @Test fun earlyBubbleStaysAttachedToThePill() {
        for (t in listOf(0.05f, 0.1f, 0.2f)) {
            val r = CcMorph.rect(t, pill, panel)
            assertTrue("top $t", r.top in panel.top - 0.01f..pill.top + 0.01f)
            assertTrue("t=$t bubble covers the pill's centre", r.contains(pill.center))
        }
    }

    // The tiles are clipped to the bubble, so the bubble is what fills the gaps between them: solid until the
    // panel is nearly open, then it fades to leave the gaps clear (and back in as the close begins).
    @Test fun bubbleStaysSolidUntilThePanelIsNearlyOpen() {
        assertEquals(1f, CcMorph.bubbleAlpha(0f), 0f)
        assertEquals(1f, CcMorph.bubbleAlpha(0.8f), 0f)
        assertEquals(0f, CcMorph.bubbleAlpha(1f), 0f)
        assertEquals(0f, CcMorph.bubbleAlpha(1.04f), 0f)
        assertTrue(CcMorph.bubbleAlpha(0.9f) in 0.01f..0.99f)
    }
}

class ReduceMotionTest {
    @Test fun overlaysOnlyFade() {
        assertEquals(1f, dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = true), 0f)
        assertEquals(0f, dev.glasslauncher.home.OverlayMotion.slide(0.3f, reduceMotion = true), 0f)
        assertTrue(dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = false) < 1f)
    }
}
