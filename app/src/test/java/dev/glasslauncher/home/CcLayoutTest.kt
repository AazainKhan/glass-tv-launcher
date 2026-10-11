package dev.glasslauncher.home

import dev.glasslauncher.home.CcSize.S1x1
import dev.glasslauncher.home.CcSize.S2x1
import dev.glasslauncher.home.CcSize.S2x2
import dev.glasslauncher.home.CcSize.S4x1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P83 M1: the layout model and its next-fit packing: pure, no Compose. */
class CcLayoutTest {
    private fun t(id: String, s: CcSize = S1x1) = CcTile(id, s)
    private fun at(p: List<CcPlaced>, id: String) = p.first { it.tile.id == id }.let { it.row to it.col }

    @Test fun theDefaultPacksToTheSpecsGrid() {
        val p = CcPacking.pack(CcLayout.default().page("controls")!!.tiles)
        assertEquals(0 to 0, at(p, "fire_settings"))
        assertEquals(0 to 2, at(p, "wifi"))
        assertEquals("beside the big tile, below Wi-Fi", 1 to 2, at(p, "bluetooth"))
        // The new pill row (M5): Sleep Timer and VPN, then Launcher Settings full width.
        assertEquals(2 to 0, at(p, "sleep_timer")); assertEquals(2 to 2, at(p, "vpn"))
        assertEquals(3 to 0, at(p, "launcher"))
        // The eight round buttons: four to a row.
        assertEquals(listOf(4 to 0, 4 to 1, 4 to 2, 4 to 3, 5 to 0, 5 to 1, 5 to 2, 5 to 3),
            listOf("controllers", "appearance", "screensaver", "switcher", "airplay", "performance", "cleanup", "restart").map { at(p, it) })
    }

    @Test fun todaysLayoutIsTheSameWithoutTheNewTiles() {
        // The layout this was before the quick wins: Settings 2x2 with the two pills, Launcher full width, rounds.
        val old = CcLayout.default().page("controls")!!.tiles.filter { it.id !in setOf("sleep_timer", "vpn", "cleanup", "restart") }
        val p = CcPacking.pack(old)
        assertEquals(2 to 0, at(p, "launcher"))
        assertEquals(listOf(3 to 0, 3 to 1, 3 to 2, 3 to 3, 4 to 0, 4 to 1),
            listOf("controllers", "appearance", "screensaver", "switcher", "airplay", "performance").map { at(p, it) })
    }

    @Test fun freeMemoryIsNowCleanUp() {
        assertTrue("memory" !in CcLayout.default().page("controls")!!.tiles.map { it.id })
        val stored = CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("memory", S1x1))))).toJson()
        assertEquals(listOf("cleanup"), CcLayout.fromJson(stored)!!.page("controls")!!.tiles.map { it.id })
        val hidden = CcLayout.default(hidden = setOf("memory")).page("controls")!!.tiles.map { it.id }
        assertTrue("cleanup" !in hidden)
    }

    @Test fun anAbsentControlLeavesNoHole() {
        // No AirPlay installed, not rooted (no Performance), no VPN app: the rest close up.
        val shown = CcLayout.default().page("controls")!!.tiles.filter { it.id !in setOf("airplay", "performance", "vpn") }
        val p = CcPacking.pack(shown)
        assertEquals("Sleep Timer sits alone, left-aligned", 2 to 0, at(p, "sleep_timer"))
        assertEquals(3 to 0, at(p, "launcher"))
        assertEquals(4 to 3, at(p, "switcher"))
        assertEquals(5 to 1, at(p, "restart"))
    }

    @Test fun aTwoWideTileThatDoesNotFitStartsTheNextRowAndNothingBackfills() {
        val p = CcPacking.pack(listOf(t("a"), t("b"), t("c"), t("wide", S2x1), t("d")))
        assertEquals(1 to 0, at(p, "wide"))
        // d follows the wide tile; it does not jump back into the free cell at (0,3).
        assertEquals(1 to 2, at(p, "d"))
    }

    @Test fun aFourWideTileTakesARowOfItsOwn() {
        val p = CcPacking.pack(listOf(t("a"), t("full", S4x1), t("b")))
        assertEquals(1 to 0, at(p, "full"))
        assertEquals(2 to 0, at(p, "b"))
    }

    @Test fun aSquareTileOccupiesTwoRowsAndLaterOnesWrapBesideIt() {
        val p = CcPacking.pack(listOf(t("big", S2x2), t("x", S2x1), t("y", S2x1), t("z", S2x1)))
        assertEquals(0 to 2, at(p, "x")); assertEquals(1 to 2, at(p, "y")); assertEquals(2 to 0, at(p, "z"))
    }

    @Test fun reorderingOneTileOnlyShiftsTheTilesBetween() {
        val base = CcLayout(listOf(CcPage("controls", "grid", listOf("a", "b", "c", "d", "e", "f").map { t(it) })))
        val before = CcPacking.pack(base.page("controls")!!.tiles).associate { it.tile.id to (it.row to it.col) }
        val after = CcPacking.pack(base.move("controls", 1, 3).page("controls")!!.tiles).associate { it.tile.id to (it.row to it.col) }
        assertEquals(before["a"], after["a"]); assertEquals(before["e"], after["e"]); assertEquals(before["f"], after["f"])
        // a c d b e f: the tiles between slide one place back, b lands where d was.
        assertEquals(before["b"], after["c"]); assertEquals(before["c"], after["d"]); assertEquals(before["d"], after["b"])
    }

    @Test fun resizeCyclesTheAllowedSizesAndAOneSizeControlIsUnchanged() {
        var l = CcLayout.default()
        l = l.resize("controls", "wifi"); assertEquals(S1x1, l.page("controls")!!.tiles.first { it.id == "wifi" }.size)
        l = l.resize("controls", "wifi"); assertEquals(S2x1, l.page("controls")!!.tiles.first { it.id == "wifi" }.size)
        assertEquals(l, l.resize("controls", "fire_settings"))
        assertEquals(S2x1, CcLayout.default().resize("controls", "launcher").page("controls")!!.tiles.first { it.id == "launcher" }.size)
    }

    @Test fun fireSettingsCannotBeRemovedOthersCan() {
        val l = CcLayout.default()
        assertEquals(l, l.remove("controls", "fire_settings"))
        assertTrue(l.remove("controls", "wifi").page("controls")!!.tiles.none { it.id == "wifi" })
    }

    @Test fun addPutsItAtTheEndAtItsDefaultSizeAndNeverTwiceUnlessParameterized() {
        var l = CcLayout.default().remove("controls", "wifi")
        l = l.add("controls", "wifi")
        assertEquals(CcTile("wifi", S2x1), l.page("controls")!!.tiles.last())
        assertEquals("already there: not again", l, l.add("controls", "wifi"))
        l = l.add("controls", "addon:ha/scene#a").add("controls", "addon:ha/scene#b")
        assertEquals(2, l.page("controls")!!.tiles.count { it.id.startsWith("addon:ha/scene#") })
        assertEquals("add-ons may be 1x1 or 2x1 only", S2x1, l.page("controls")!!.tiles.last().size)
        assertEquals("a 2x2 is refused for an add-on", S2x1, l.add("controls", "addon:x/y", S2x2).page("controls")!!.tiles.last().size)
    }

    @Test fun movingToAnotherPageAppendsThereAndAnEmptyPageIsDropped() {
        val l = CcLayout(listOf(CcPage("controls", "grid", listOf(t("a"), t("b"))), CcPage("media", "music", listOf(t("m")))))
        val moved = l.moveToPage("controls", "a", "media")
        assertEquals(listOf("m", "a"), moved.page("media")!!.tiles.map { it.id })
        val emptied = moved.moveToPage("media", "m", "controls").moveToPage("media", "a", "controls")
        assertEquals(1, emptied.withoutEmptyPages().pages.size)
    }

    @Test fun upAndDownFindTheNearestTileInTheRowAboveOrBelow() {
        val l = CcLayout(listOf(CcPage("controls", "grid", listOf("a", "b", "c", "d", "e", "f", "g", "h").map { t(it) })))
        // 4 columns: a b c d / e f g h. From g (row 1, col 2): up is c, down is none.
        assertEquals(2, l.neighbour("controls", 6, -1))
        assertNull(l.neighbour("controls", 6, +1))
        assertEquals(5, l.neighbour("controls", 1, +1))
    }

    @Test fun jsonRoundTripsAndABadValueFallsBackToTheDefault() {
        val l = CcLayout.default(hidden = setOf("airplay"))
        assertEquals(l, CcLayout.fromJson(l.toJson()))
        assertNull(CcLayout.fromJson("{not json"))
        assertNull(CcLayout.fromJson(""))
        assertEquals(CcLayout.default(), CcLayout.resolve("garbage", emptySet()))
    }

    @Test fun theOldHiddenSwitchesMigrateOutOfTheDefault() {
        val l = CcLayout.resolve(null, setOf("wifi", "memory", "alexa"))
        val ids = l.page("controls")!!.tiles.map { it.id }
        assertTrue("wifi" !in ids && "cleanup" !in ids && "bluetooth" in ids)
        assertNull("alexa page off", l.page("alexa"))
        assertTrue(CcLayout.default().page("alexa") != null)
    }

    @Test fun anUnknownIdKeepsItsPositionAndIsSkippedWhileAvailableFiltersIt() {
        val l = CcLayout(listOf(CcPage("controls", "grid", listOf(t("a"), t("addon:gone/x"), t("b")))))
        val p = CcPacking.pack(l.page("controls")!!.tiles.filter { it.id != "addon:gone/x" })
        assertEquals(0 to 1, at(p, "b"))
        assertEquals(listOf("a", "addon:gone/x", "b"), CcLayout.fromJson(l.toJson())!!.page("controls")!!.tiles.map { it.id })
    }
}
