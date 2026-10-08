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

    @Test fun tilesOnlyAppearInTheLastSixtyPercent() {
        assertEquals(0f, CcMorph.tiles(0.4f), 0.001f)
        assertEquals(1f, CcMorph.tiles(1f), 0.001f)
        assertTrue(CcMorph.tiles(0.7f) in 0.01f..0.99f)
    }
}
