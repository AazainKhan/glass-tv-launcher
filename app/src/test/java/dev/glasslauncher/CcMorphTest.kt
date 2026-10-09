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

    // The bubble swells out of the pill's centre: its centre travels straight to the panel's centre while it grows.
    @Test fun swellsFromThePillsCentre() {
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
            val r = CcMorph.rect(t, pill, panel)
            assertEquals(pill.center.x + (panel.center.x - pill.center.x) * t, r.center.x, 0.01f)
            assertEquals(pill.center.y + (panel.center.y - pill.center.y) * t, r.center.y, 0.01f)
            assertEquals(pill.width + (panel.width - pill.width) * t, r.width, 0.01f)
            assertEquals(pill.height + (panel.height - pill.height) * t, r.height, 0.01f)
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

    // Opening: the tiles wait until the bubble is ~60% open, then arrive top row first.
    @Test fun tilesWaitForTheBubbleToBeSixtyPercentOpen() {
        for (row in 0..5) assertEquals("row $row", 0f, CcMorph.tiles(0.6f, row), 0.0001f)
        assertEquals(0f, CcMorph.tiles(0.3f, 0), 0f)
        assertTrue(CcMorph.tiles(0.7f, row = 0) > 0f)
        assertTrue("later rows follow", CcMorph.tiles(0.8f, row = 3) < CcMorph.tiles(0.8f, row = 0))
        for (row in 0..5) assertEquals(1f, CcMorph.tiles(1f, row), 0.001f)
        // The bounce overshoots 1: arrival stays 1.
        for (row in 0..5) assertEquals(1f, CcMorph.tiles(1.03f, row), 0.001f)
        assertEquals(0.5f, CcMorph.bubble(0.5f, closing = false), 0f)
    }

    // Closing: the tiles leave first (the bubble holds full size), then the bubble collapses.
    @Test fun closingTilesGoBeforeTheBubbleCollapses() {
        assertEquals(1f, CcMorph.bubble(0.9f, closing = true), 0f)
        assertTrue("tiles are leaving", CcMorph.tiles(0.95f, 0, closing = true) in 0.01f..0.99f)
        for (row in 0..5) assertEquals("row $row gone", 0f, CcMorph.tiles(0.85f, row, closing = true), 0.0001f)
        assertTrue("then the bubble shrinks", CcMorph.bubble(0.5f, closing = true) < 0.65f)
        assertEquals(0f, CcMorph.bubble(0f, closing = true), 0f)
        assertEquals(0f, CcMorph.bubble(-0.02f, closing = true), 0f)
    }

    // Back mid-open reverses from where it is: both directions agree at the moment of the turn.
    @Test fun reversingMidOpenDoesNotJump() {
        for (from in listOf(0.2f, 0.5f, 0.75f, 0.9f, 1f)) {
            assertEquals("bubble from $from", CcMorph.bubble(from, closing = false), CcMorph.bubble(from, closing = true, from = from), 0.0001f)
            for (row in 0..5) assertEquals("row $row from $from", CcMorph.tiles(from, row), CcMorph.tiles(from, row, closing = true, from = from), 0.0001f)
        }
        // Then it still collapses fully.
        assertEquals(0f, CcMorph.bubble(0f, closing = true, from = 0.5f), 0f)
        assertEquals(0f, CcMorph.tiles(0.1f, 0, closing = true, from = 0.9f), 0f)
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
        // The window and the in-launcher overlay stay up until the close has landed.
        assertTrue("CLOSE_MS ${CcMorph.CLOSE_MS} < spring $closeMs", CcMorph.CLOSE_MS >= closeMs)
        assertTrue("open settles in ${openMs} ms", openMs < 1000)
    }

    @Test fun tilesGrowSlightlyAsTheyArrive() {
        assertEquals(0.96f, CcMorph.scale(0f), 0.0001f)
        assertEquals(1f, CcMorph.scale(1f), 0.0001f)
    }
}

class ReduceMotionTest {
    // Reduce Motion (§9.2): things fade, nothing slides, rises or scales.
    @Test fun controlCenterRowsOnlyFade() {
        val m = CcMorph.row(0.4f, reduceMotion = true)
        assertEquals(0f, m.rise, 0f)
        assertEquals(1f, m.scale, 0f)
        assertTrue(m.alpha in 0f..1f)
        val full = CcMorph.row(0.4f, reduceMotion = false)
        assertTrue("normally rows rise", full.rise > 0f)
    }

    @Test fun overlaysOnlyFade() {
        assertEquals(1f, dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = true), 0f)
        assertEquals(0f, dev.glasslauncher.home.OverlayMotion.slide(0.3f, reduceMotion = true), 0f)
        assertTrue(dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = false) < 1f)
    }
}
