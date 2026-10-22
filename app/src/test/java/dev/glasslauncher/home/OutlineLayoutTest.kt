package dev.glasslauncher.home

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P233: the editor's callouts stay inside the screen's safe area and no two leader lines cross. */
class OutlineLayoutTest {
    private val styles = listOf(PadStyle.PlayStation, PadStyle.Xbox, PadStyle.Generic, PadStyle.Remote)
    private val w = 870f
    private val h = 318f

    private fun cross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
        fun o(p: Offset, q: Offset, r: Offset) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        val d1 = o(a, b, c); val d2 = o(a, b, d); val d3 = o(c, d, a); val d4 = o(c, d, b)
        return d1 * d2 < -1e-3f && d3 * d4 < -1e-3f
    }

    @Test fun everyCalloutIsInsideTheBoxAndTheSidesKeepTheirPitch() {
        for (style in styles) {
            val lay = OutlineLayout.compute(style, w, h)
            assertTrue("$style left edge ${lay.leftX}", lay.leftX >= 0f)
            assertTrue("$style right edge", lay.rightX + OutlineLayout.CALLOUT_W <= w)
            for (onLeft in listOf(true, false)) {
                val ys = lay.leaders.values.filter { it.onLeft == onLeft }.map { it.rowY }.sorted()
                assertTrue("$style rows inside the box: $ys", ys.first() - OutlineLayout.CALLOUT_H / 2 >= -0.01f && ys.last() + OutlineLayout.CALLOUT_H / 2 <= h + 0.01f)
                for (i in 1 until ys.size) assertTrue("$style pitch ${ys[i] - ys[i - 1]}", ys[i] - ys[i - 1] >= OutlineLayout.MIN_PITCH - 0.01f)
            }
        }
    }

    @Test fun noTwoLeaderLinesCross() {
        for (style in styles) {
            val leaders = OutlineLayout.compute(style, w, h).leaders.values.toList()
            for (i in leaders.indices) for (j in i + 1 until leaders.size) {
                val a = leaders[i].points; val b = leaders[j].points
                for (p in 0 until a.lastIndex) for (q in 0 until b.lastIndex) assertTrue(
                    "$style: ${leaders[i].id} crosses ${leaders[j].id}",
                    !cross(a[p], a[p + 1], b[q], b[q + 1]),
                )
            }
        }
    }

    @Test fun everySlotHasExactlyOneSide() {
        for (style in styles) {
            val (l, r) = OutlineLayout.members(style)
            assertEquals(emptySet<String>(), l.toSet() intersect r.toSet())
            assertEquals((l + r).toSet(), OutlineLayout.compute(style, w, h).leaders.keys)
        }
    }
}
