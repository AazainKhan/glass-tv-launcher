package dev.glasslauncher

import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.glasslauncher.home.CcMorph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Control Center's open/close (P49): the pill splits into two drops, the controls grow out of them, all from one value. */
class CcMorphTest {
    private val pill = Rect(1662f, 44f, 1876f, 108f)
    private val controlsIcon = Rect(1740f, 48f, 1820f, 128f)
    private val alexaIcon = Rect(1840f, 48f, 1920f, 128f)

    // Frame 1 is the pill: the drops start as its two end circles, as tall as it is, and the neck between them is
    // the pill's full height (so drops + neck are exactly its capsule).
    @Test fun theSplitStartsAsThePillsCapsule() {
        val (l, r) = CcMorph.pillEnds(pill)
        assertEquals(pill.left + pill.height / 2, l.x, 0.01f); assertEquals(pill.right - pill.height / 2, r.x, 0.01f)
        val (c, d) = CcMorph.drop(CcMorph.split(0f), l, pill.height, controlsIcon)
        assertEquals(l, c); assertEquals(pill.height, d, 0.01f)
        val (ends, waist) = CcMorph.neck(0f, pill.height / 2)!!
        assertEquals(pill.height / 2, ends, 0.01f); assertEquals(pill.height / 2, waist, 0.01f)
    }

    // The drops land exactly on the page icons by the end of the split, and the neck has parted well before.
    @Test fun theDropsLandOnThePageIcons() {
        val q = CcMorph.split(CcMorph.SPLIT_END)
        assertEquals(1f, q, 0f)
        val (c, d) = CcMorph.drop(q, CcMorph.pillEnds(pill).second, pill.height, alexaIcon)
        assertEquals(alexaIcon.center, c); assertEquals(alexaIcon.width, d, 0.01f)
        assertNull(CcMorph.neck(0.7f, 20f))
        // The neck only thins (its waist faster than its ends: it pinches).
        var last = Float.MAX_VALUE
        for (i in 0..69) { val (e, w) = CcMorph.neck(i / 100f, 20f)!!; assertTrue(w <= e + 0.001f); assertTrue(w <= last + 0.001f); last = w }
    }

    // Every control starts as a drop-sized shape at its drop and ends exactly in its slot; nothing jumps.
    @Test fun controlsGrowOutOfTheirDrop() {
        val slot = Rect(1400f, 200f, 1600f, 300f)
        val drop = controlsIcon.center
        val start = CcMorph.emerge(0f, slot, drop, 80f)
        assertEquals(80f / 100f, start.scale, 0.001f)
        assertEquals(drop.x - slot.center.x, start.dx, 0.01f); assertEquals(drop.y - slot.center.y, start.dy, 0.01f)
        assertEquals(0f, start.round, 0f)
        assertTrue(CcMorph.emerge(1f, slot, drop, 80f).still)
        // Continuous along the way: no step bigger than a small fraction of the travel per 1% of progress.
        var prev = start
        for (i in 1..100) {
            val f = CcMorph.emerge(i / 100f, slot, drop, 80f)
            assertTrue(kotlin.math.abs(f.dx - prev.dx) < 3f && kotlin.math.abs(f.dy - prev.dy) < 3f && kotlin.math.abs(f.scale - prev.scale) < 0.01f)
            prev = f
        }
    }

    // Near controls start first; everything has started by 45% and finishes at 1.
    @Test fun theStaggerStartsNearControlsFirst() {
        assertEquals(0.2f, CcMorph.tileStart(0f, 500f), 0.001f)
        assertEquals(0.45f, CcMorph.tileStart(500f, 500f), 0.001f)
        assertTrue(CcMorph.tileStart(100f, 500f) < CcMorph.tileStart(300f, 500f))
        assertEquals(0f, CcMorph.tile(0.2f, 0.3f), 0f)
        assertEquals(1f, CcMorph.tile(1f, 0.45f), 0f)
    }

    // Home's pill (its copy) fades as the drops form; the icons' glyphs only come in as they land.
    @Test fun thePillFadesAndTheGlyphsArriveLate() {
        assertEquals(1f, CcMorph.pillAlpha(0f), 0f); assertEquals(0f, CcMorph.pillAlpha(0.3f), 0f)
        assertEquals(0f, CcMorph.glyphAlpha(0.2f), 0f); assertEquals(1f, CcMorph.glyphAlpha(CcMorph.SPLIT_END), 0.001f)
    }

    // The only fades in Control Center (the page icons' glyphs, the header) last at most two frames (28 ms each) of the
    // open spring, the ghost rule's limit; every glass shape is opaque throughout.
    @Test fun theOnlyFadesAreAtMostTwoFrames() {
        val v = CcMorph.openSpring.vectorize(Float.VectorConverter)
        val a = androidx.compose.animation.core.AnimationVector1D(0f); val b = androidx.compose.animation.core.AnimationVector1D(1f)
        val z = androidx.compose.animation.core.AnimationVector1D(0f)
        fun halfFrames(alpha: (Float) -> Float) = (0..1000 step 28).count { ms -> alpha(v.getValueFromNanos(ms * 1_000_000L, a, b, z).value) in 0.2f..0.8f }
        assertTrue("glyphs ${halfFrames(CcMorph::glyphAlpha)}", halfFrames(CcMorph::glyphAlpha) <= 2)
        assertTrue("header ${halfFrames(CcMorph::headerAlpha)}", halfFrames(CcMorph::headerAlpha) <= 2)
    }

    // Closing runs the open's path back: the progress is the spring value, never past where the close began.
    @Test fun closingRunsThePathBackAndReversingMidOpenDoesNotJump() {
        for (from in listOf(0.2f, 0.5f, 0.75f, 0.9f, 1f)) {
            assertEquals("from $from", CcMorph.bubble(from, closing = false), CcMorph.bubble(from, closing = true, from = from), 0.0001f)
            assertTrue(CcMorph.bubble(1.04f, closing = true, from = from) <= from + 0.0001f)
        }
        assertEquals(0f, CcMorph.bubble(-0.02f, closing = true), 0f)
        assertEquals(1f, CcMorph.bubble(1.03f, closing = false), 0f)
    }

    // User (P49): crisp, no bounce; open ~350-400 ms, close ~250 ms.
    @Test fun springsAreCrispWithoutBounce() {
        fun path(spec: androidx.compose.animation.core.SpringSpec<Float>, from: Float, to: Float): List<Float> {
            val v = spec.vectorize(Float.VectorConverter)
            val a = androidx.compose.animation.core.AnimationVector1D(from); val b = androidx.compose.animation.core.AnimationVector1D(to)
            val z = androidx.compose.animation.core.AnimationVector1D(0f)
            val ms = v.getDurationNanos(a, b, z) / 1_000_000
            return (0..ms step 4).map { v.getValueFromNanos(it * 1_000_000, a, b, z).value }
        }
        val open = path(CcMorph.openSpring, 0f, 1f)
        assertTrue("open overshoot ${open.max()}", open.max() < 1.005f)
        val openMs = open.indexOfFirst { it >= 0.99f } * 4
        assertTrue("open visibly done at $openMs ms", openMs in 280..420)
        val close = path(CcMorph.closeSpring, 1f, 0f)
        assertTrue("close undershoot ${close.min()}", close.min() > -0.005f)
        // The window and the in-launcher overlay are removed once the close has visibly landed (within 1% of the
        // pill), cutting the spring's invisible tail: CLOSE_MS is that time, to within a frame or two.
        val landedMs = close.indexOfFirst { it <= 0.01f } * 4L
        assertTrue("close lands at $landedMs ms", landedMs in 200..330)
        assertTrue("CLOSE_MS ${CcMorph.CLOSE_MS} < visible landing $landedMs", CcMorph.CLOSE_MS >= landedMs)
        assertTrue("CLOSE_MS ${CcMorph.CLOSE_MS} keeps the invisible tail (landing $landedMs)", CcMorph.CLOSE_MS <= landedMs + 40)
    }
}

class ReduceMotionTest {
    @Test fun overlaysOnlyFade() {
        assertEquals(1f, dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = true), 0f)
        assertEquals(0f, dev.glasslauncher.home.OverlayMotion.slide(0.3f, reduceMotion = true), 0f)
        assertTrue(dev.glasslauncher.home.OverlayMotion.scale(0.3f, reduceMotion = false) < 1f)
    }
}
