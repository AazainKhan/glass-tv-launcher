package dev.glasslauncher

import androidx.compose.ui.geometry.Rect
import dev.glasslauncher.home.CcMorph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CcMorphTest {
    private val pill = Rect(1662f, 44f, 1876f, 108f)
    private val panel = Rect(1300f, 40f, 1880f, 1000f)

    @Test fun startsExactlyOnThePillAndEndsOnThePanel() {
        assertEquals(pill, CcMorph.rect(0f, pill, panel))
        assertEquals(panel, CcMorph.rect(1f, pill, panel))
    }

    @Test fun growsDownAndLeftFromThePillsCorner() {
        val mid = CcMorph.rect(0.5f, pill, panel)
        assertTrue(mid.right in pill.right..panel.right)
        assertTrue(mid.left < pill.left && mid.left > panel.left)
        assertTrue(mid.bottom > pill.bottom && mid.bottom < panel.bottom)
    }

    @Test fun cornerGoesFromCapsuleToTileRadius() {
        assertEquals(pill.height / 2, CcMorph.radius(0f, pill, 26f), 0.01f)
        assertEquals(26f, CcMorph.radius(1f, pill, 26f), 0.01f)
    }

    // Like an app opening: ~420 ms, a fast start and a long soft settle; closing is the same path, faster.
    @Test fun opensLikeAnAppAndClosesFaster() {
        assertTrue(CcMorph.OPEN_MS in 380..450)
        assertTrue(CcMorph.CLOSE_MS in 250..300)
        assertTrue("fast start", CcMorph.openEasing.transform(0.25f) > 0.5f)
        for (x in listOf(0.1f, 0.4f, 0.8f)) assertEquals(1f - CcMorph.openEasing.transform(1f - x), CcMorph.closeEasing.transform(x), 0.0001f)
    }

    @Test fun tilesComeInFromAQuarterRowByRow() {
        assertEquals(0f, CcMorph.tiles(0.25f, row = 0), 0.001f)
        assertTrue(CcMorph.tiles(0.35f, row = 0) > 0f)
        assertTrue("later rows follow", CcMorph.tiles(0.5f, row = 3) < CcMorph.tiles(0.5f, row = 0))
        for (row in 0..5) assertEquals(1f, CcMorph.tiles(1f, row), 0.001f)
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
