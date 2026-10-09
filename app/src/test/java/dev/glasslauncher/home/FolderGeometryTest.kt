package dev.glasslauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tvOS 27's open folder in dp (the 1080p reference measurements halved). */
class FolderGeometryTest {
    private fun Float.near(expected: Float, what: String) = assertEquals(what, expected, this, 0.6f)
    private val g = FolderGeometry

    @Test fun panelAndTilesMatchTheReference() {
        g.panelWidth.value.near(604f, "panel width (357..1565 px)")
        g.panelHeight.value.near(385f, "panel height (150..920 px)")
        g.panelRadius.value.near(35f, "corner radius (70 px)")
        g.tileWidth.value.near(125f, "tile width (250 px)")
        g.tileHeight.value.near(75f, "tile height (150 px)")
        g.colPitch.value.near(200f, "column pitch (400 px)")
        g.rowPitch.value.near(121f, "row pitch (242 px)")
        g.padX.value.near(39f, "left padding (78 px)")
        g.padY.value.near(26f, "top padding (52 px)")
    }

    @Test fun nameCapsuleIsAboveThePanel() {
        assertTrue(g.capsuleTop + g.capsuleHeight < g.panelTop)
        g.capsuleWidth.value.near(115f, "capsule width (230 px)")
        g.capsuleHeight.value.near(50f, "capsule height (100 px)")
    }

    @Test fun tilePositionsInsideThePanelForFiveSixNineAndTenApps() {
        // Panel is fixed; 435/835/1233 px and 202/444/686 px on screen are 217.5/417.5/616.5 and 101/222/343 dp, panel left 178, top 75.
        for (n in listOf(5, 6, 9, 10)) {
            for (i in 0 until n) {
                val (x, y) = g.tileOffset(i)
                x.value.near(listOf(39.5f, 239.5f, 439.5f)[i % 3], "x of tile $i of $n")
                y.value.near(26f + 121f * (i / 3), "y of tile $i of $n")
            }
        }
        // A tile of the last visible row fits inside the fixed panel, with room under it for its name.
        val (_, lastY) = g.tileOffset(8)
        assertTrue(lastY + g.tileHeight + 20.dpValue() < g.panelHeight)
    }

    @Test fun onlyMoreThanNineAppsScroll() {
        assertEquals(listOf(2, 2, 3, 4), listOf(5, 6, 9, 10).map { g.rows(it) })
        assertEquals(listOf(false, false, false, true), listOf(5, 6, 9, 10).map { g.scrolls(it) })
        assertFalse(g.scrolls(0))
    }

    private fun Int.dpValue() = androidx.compose.ui.unit.Dp(this.toFloat())
}
