package dev.glasslauncher.shots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stableImage mask must not move with the width of the time/date text. */
class MaskRectTest {
    // Measured node bounds: the pill's clock ("9:41 AM" vs "10:05 AM"), Control Center's clock ("9:41:00 AM" vs "10:05:00 AM").
    private fun pill(width: Float) = maskRect(1876f - width, 44f, 1876f, 108f, MaskAnchor.Right)
    private fun cc(width: Float) = maskRect(1354f, 44f, 1354f + width, 86f, MaskAnchor.Left)

    @Test fun rightAnchoredEdgeIgnoresTextWidth() {
        val a = pill(201f); val b = pill(217f)
        assertEquals(a, b)
        for (w in 120..440) assertEquals("width $w", a, pill(w.toFloat()))
    }

    @Test fun leftAnchoredEdgeIgnoresTextWidth() {
        val a = cc(199f); val b = cc(222f)
        assertEquals(a, b)
        for (w in 100..440) assertEquals("width $w", a, cc(w.toFloat()))
    }

    @Test fun wideNodesAreCoveredAndOnlyTheFreeEdgeMoves() {
        val narrow = pill(201f); val wide = pill(700f)
        assertEquals(narrow.right, wide.right, 0f)
        assertTrue(wide.left <= 1876f - 700f - 24f)
        assertTrue(wide.left <= narrow.left)
        val ccNarrow = cc(199f); val ccWide = cc(700f)
        assertEquals(ccNarrow.left, ccWide.left, 0f)
        assertTrue(ccWide.right >= 1354f + 700f + 24f)
    }

    @Test fun coversTheWholeNode() {
        for (w in listOf(120f, 201f, 448f, 600f, 900f)) {
            val p = pill(w); assertTrue(p.left <= 1876f - w - 24f || w < 448f); assertTrue(p.right >= 1876f)
            val c = cc(w); assertTrue(c.left <= 1354f); assertTrue(c.right >= 1354f + w)
        }
    }
}
