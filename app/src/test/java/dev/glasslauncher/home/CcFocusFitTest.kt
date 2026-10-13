package dev.glasslauncher.home

import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P138: a focused Control Center control grows without touching its neighbours, for every size in the grid and at every
 * text scale. The grid's gap is 8 dp; the focus growth is 10 dp in total (5 dp a side, tvOS's 96 -> 116 px), so the gap never closes.
 */
class CcFocusFitTest {
    private class R(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun overlaps(o: R) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    private fun tiles(): List<List<CcTile>> = listOf(
        // Every size beside every other, several rows: the default page, and a page of each size mixed.
        CcLayout.default().page("controls")!!.tiles,
        CcLayout.default().page("more")!!.tiles,
        listOf(CcTile("a", CcSize.S1x1), CcTile("b", CcSize.S1x1), CcTile("c", CcSize.S2x1), CcTile("d", CcSize.S2x2), CcTile("e", CcSize.S2x1),
            CcTile("f", CcSize.S4x1), CcTile("g", CcSize.S1x1), CcTile("h", CcSize.S2x1), CcTile("i", CcSize.S1x1)),
        listOf(CcTile("a", CcSize.S2x2), CcTile("b", CcSize.S2x2), CcTile("c", CcSize.S4x1), CcTile("d", CcSize.S1x1), CcTile("e", CcSize.S1x1), CcTile("f", CcSize.S2x1)),
    )

    @Test fun aFocusedControlNeverReachesItsNeighbours() {
        for (k in listOf(1f, 1.15f, 1.3f, 1.5f)) {
            val sz = CcSizes(k)
            val u = sz.u.value; val g = sz.gap.value
            for (page in tiles()) {
                val placed = CcPacking.pack(page)
                val rest = placed.map { p ->
                    val w = u * p.tile.size.w + g * (p.tile.size.w - 1); val h = u * p.tile.size.h + g * (p.tile.size.h - 1)
                    R((u + g) * p.col, (u + g) * p.row, (u + g) * p.col + w, (u + g) * p.row + h) to p.tile.size
                }
                for ((i, me) in rest.withIndex()) {
                    val (r, size) = me
                    val w = r.r - r.l; val h = r.b - r.t
                    val s = sz.focusScale(Dp(w))
                    val cx = (r.l + r.r) / 2; val cy = (r.t + r.b) / 2
                    val grown = R(cx - w * s / 2, cy - h * s / 2, cx + w * s / 2, cy + h * s / 2)
                    for ((j, other) in rest.withIndex()) if (i != j) assertTrue("$size focused at k=$k touches a neighbour (scale $s)", !grown.overlaps(other.first))
                }
            }
        }
    }

    @Test fun theLiftIsTvosTenDpAndTheCircleIsItsOneTwentyOnePercent() {
        val sz = CcSizes(1f)
        assertEquals(1.208f, sz.focusScale(sz.round), 0.002f)
        assertTrue("a pill grows less than a circle", sz.focusScale(sz.pillWidth) < 1.11f)
        assertTrue("a full row barely grows", sz.focusScale(sz.column) < 1.05f)
    }
}
