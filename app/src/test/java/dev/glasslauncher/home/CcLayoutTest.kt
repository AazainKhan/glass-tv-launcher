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

    @Test fun theDefaultPageStartsWithTheFourByFourBlockAndHoldsEveryDefault() {
        val p = CcPacking.pack(CcLayout.default().page("controls")!!.tiles)
        assertEquals(0 to 0, at(p, "fire_settings"))
        assertEquals(0 to 2, at(p, "wifi"))
        assertEquals("beside the big tile, below Wi-Fi", 1 to 2, at(p, "bluetooth"))
        // F01: Sleep Timer, Clean Up and Restart are 2x1 pills; AirPlay and Theme are circles.
        assertEquals(listOf(2 to 0, 2 to 2, 3 to 0), listOf("sleep_timer", "cleanup", "restart").map { at(p, it) })
        assertEquals(listOf(3 to 2, 3 to 3), listOf("airplay", "appearance").map { at(p, it) })
        assertEquals("the first four rows are the old first page", listOf("fire_settings", "wifi", "bluetooth", "sleep_timer", "cleanup", "restart", "airplay", "appearance"),
            p.filter { it.row < 4 }.map { it.tile.id })
        assertEquals("every default control is on this one page and it fits the panel's rows", 7, p.maxOf { it.row + it.tile.size.h })
        assertTrue(p.maxOf { it.row + it.tile.size.h } <= CcControls.MAX_ROWS)
    }

    @Test fun performanceIsAToggleSoItNeverStaysACircle() {
        assertEquals(listOf(S2x1, S2x2), CcControls.sizes("performance"))
        assertEquals(S2x1, CcLayout.default().page("controls")!!.tiles.first { it.id == "performance" }.size)
        // A layout saved when it was a 1x1 circle loads with the pill.
        val old = CcLayout(listOf(CcPage("more", "more", listOf(CcTile("performance", S1x1), CcTile("wifi", S1x1))))).toJson()
        val back = CcLayout.fromJson(old)!!.page("more")!!.tiles
        assertEquals(S2x1, back.first { it.id == "performance" }.size)
        assertEquals("a size the control allows is kept", S1x1, back.first { it.id == "wifi" }.size)
    }

    @Test fun theDefaultIsOnePageAndASecondOnlyComesFromThePlus() {
        val d = CcLayout.default()
        assertEquals(listOf("controls", "alexa"), d.pages.map { it.id })
        assertEquals(CcControls.DEFAULT_CONTROLS.map { it.id } + CcControls.DEFAULT_MORE.map { it.id }, d.page("controls")!!.tiles.map { it.id })
        assertEquals(CcSize.S2x1, d.page("controls")!!.tiles.last().size)
        assertEquals(null, d.page("more"))
    }

    @Test fun aLayoutSavedWithTheOldMorePageGetsOnePageOnce() {
        val saved = CcLayout(listOf(
            CcPage("controls", "grid", listOf(CcTile("wifi", S2x1), CcTile("launcher", S1x1))),
            CcPage("more", "more", listOf(CcTile("controllers", S1x1), CcTile("launcher", S1x1), CcTile("vpn", S2x1))),
            CcPage("page2-123", "auto", listOf(CcTile("downloads", S1x1))),
            CcPage("alexa", "alexa", emptyList()),
        )).toJson().replace("\"v\":3", "\"v\":2")
        val l = CcLayout.fromJson(saved)!!
        assertEquals(listOf("controls", "page2-123", "alexa"), l.pages.map { it.id })
        assertEquals("More's tiles follow, none twice", listOf("wifi", "launcher", "controllers", "vpn"), l.page("controls")!!.tiles.map { it.id })
        assertEquals("a page the user made stays", listOf("downloads"), l.page("page2-123")!!.tiles.map { it.id })
        // Saved again it is the current version: a page the user makes with the + and calls "more" is not folded.
        val again = l.copy(pages = l.pages + CcPage("more", "auto", listOf(CcTile("x", S1x1))))
        assertEquals(listOf("controls", "page2-123", "alexa", "more"), CcLayout.fromJson(again.toJson())!!.pages.map { it.id })
    }

    @Test fun freeMemoryIsNowCleanUp() {
        assertTrue("memory" !in CcLayout.default().pages.flatMap { it.tiles }.map { it.id })
        val stored = CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("memory", S1x1))))).toJson()
        assertEquals(listOf("cleanup"), CcLayout.fromJson(stored)!!.page("controls")!!.tiles.map { it.id })
        val hidden = CcLayout.default(hidden = setOf("memory")).pages.flatMap { it.tiles }.map { it.id }
        assertTrue("cleanup" !in hidden)
    }

    @Test fun anAbsentControlPacksTheRestLeftByOne() {
        // No AirPlay (PhairPlay not installed): Theme etc. close up one place; four rows still hold.
        val shown = CcLayout.default().page("controls")!!.tiles.filter { it.id != "airplay" }
        val p = CcPacking.pack(shown)
        assertEquals(3 to 2, at(p, "appearance"))
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
        l = l.resize("controls", "wifi"); assertEquals(S2x2, l.page("controls")!!.tiles.first { it.id == "wifi" }.size)
        l = l.resize("controls", "wifi"); assertEquals("wraps to the pill", S2x1, l.page("controls")!!.tiles.first { it.id == "wifi" }.size)
        assertEquals(l, l.resize("controls", "fire_settings"))
        assertEquals("1x1 -> 4x1 (wraps)", S4x1, CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("launcher", S1x1))))).resize("controls", "launcher").page("controls")!!.tiles.first { it.id == "launcher" }.size)
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
        val ids = l.pages.flatMap { it.tiles }.map { it.id }
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

    @Test fun aNewBuiltinIsGivenOnceToASavedLayoutAndARemovalSticks() {
        // A layout saved before "remotes" existed: no remotes tile, no autoAdded.
        val old = CcLayout(listOf(
            CcPage("controls", "grid", listOf(CcTile("wifi", CcSize.S2x1))),
            CcPage("more", "more", listOf(CcTile("controllers", CcSize.S1x1), CcTile("vpn", CcSize.S2x1))),
        ))
        val migrated = old.withNewBuiltins()
        assertEquals(listOf("controllers", "remotes", "vpn"), migrated.page("more")!!.tiles.map { it.id })
        assertEquals(listOf("remotes"), migrated.autoAdded)
        // Saved, loaded again, nothing more is added; and a user's removal sticks.
        val reloaded = CcLayout.fromJson(migrated.toJson())!!.withNewBuiltins()
        assertEquals(migrated.page("more")!!.tiles.map { it.id }, reloaded.page("more")!!.tiles.map { it.id })
        val removed = reloaded.remove("more", "remotes")
        assertEquals(false, CcLayout.fromJson(removed.toJson())!!.withNewBuiltins().shown("remotes"))
        // A fresh default already has it, with no second copy.
        assertEquals(1, CcLayout.default().withNewBuiltins().pages.sumOf { p -> p.tiles.count { it.id == "remotes" } })
    }
}

class CcAddFittingTest {
    private fun full(): CcLayout {
        // Seven rows of 1x1 circles, four across.
        val tiles = (0 until 28).map { CcTile("c$it", CcSize.S1x1) }
        return CcLayout(listOf(CcPage("controls", "grid", tiles), CcPage("alexa", "alexa", emptyList())))
    }

    @org.junit.Test fun aControlThatWouldMakeAPageTallerThanSevenRowsGoesToANewPage() {
        val out = full().addFitting("controls", "wifi", CcControls.MAX_ROWS)
        org.junit.Assert.assertEquals(28, out.page("controls")!!.tiles.size)
        val added = out.pages.first { p -> p.tiles.any { it.id == "wifi" } }
        org.junit.Assert.assertNotEquals("controls", added.id)
        org.junit.Assert.assertEquals("the new page sits before Alexa", "alexa", out.pages.last().id)
    }

    @org.junit.Test fun aPageWithRoomTakesItAsBefore() {
        val small = CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("a", CcSize.S1x1)))))
        val out = small.addFitting("controls", "wifi", CcControls.MAX_ROWS)
        org.junit.Assert.assertEquals(listOf("a", "wifi"), out.page("controls")!!.tiles.map { it.id })
    }

    @org.junit.Test fun theNextPageWithRoomIsUsedBeforeANewOneIsMade() {
        val l = full().let { it.copy(pages = it.pages.toMutableList().also { p -> p.add(1, CcPage("more", "more", listOf(CcTile("x", CcSize.S1x1)))) }) }
        val out = l.addFitting("controls", "wifi", CcControls.MAX_ROWS)
        org.junit.Assert.assertEquals(listOf("x", "wifi"), out.page("more")!!.tiles.map { it.id })
    }

    @Test fun aResizeThatWouldPushThePastTheRowLimitIsSkipped() {
        // Seven rows of four circles fill the page; a 2x1 anywhere adds a cell and an eighth row.
        val full = CcLayout(listOf(CcPage("controls", "grid", (1..28).map { CcTile("x$it", S1x1) } + listOf(CcTile("airplay", S1x1)))))
        assertEquals("a 29th circle already makes 8 rows; nothing fits", full, full.resize("controls", "airplay"))
        val roomy = CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("airplay", S1x1), CcTile("a", S1x1)))))
        assertEquals(S2x1, roomy.resize("controls", "airplay").page("controls")!!.tiles.first().size)
    }

    @Test fun anOldLayoutGetsThePillDefaultsOnceAndOnlyWhileTheyAreCircles() {
        // The pre-F01 default first page: all 1x1 where they were, sixteen cells in four rows.
        val old = CcLayout(listOf(
            CcPage("controls", "grid", listOf(
                CcTile("fire_settings", S2x2), CcTile("wifi", S2x1), CcTile("bluetooth", S2x1),
                CcTile("sleep_timer", S1x1), CcTile("cleanup", S1x1), CcTile("restart", S1x1), CcTile("airplay", S1x1),
                CcTile("appearance", S1x1), CcTile("screensaver", S1x1), CcTile("switcher", S1x1), CcTile("launcher", S1x1),
            )),
            CcPage("more", "more", listOf(CcTile("controllers", S1x1))),
        )).toJson().replace("\"v\":3", "\"v\":1")
        val migrated = CcLayout.fromJson(old)!!
        val controls = migrated.page("controls")!!.tiles
        assertEquals(listOf(S2x1, S2x1, S2x1), listOf("sleep_timer", "cleanup", "restart").map { id -> controls.first { it.id == id }.size })
        assertEquals("the old More page is folded in (v3), so one page holds everything", null, migrated.page("more"))
        assertEquals(listOf("airplay", "appearance", "screensaver", "switcher", "launcher", "controllers"), controls.map { it.id }.drop(6))
        // Stored again it is the current version: loading it does not touch a size the user then chose.
        val chosen = migrated.resize("controls", "cleanup").toJson()
        assertEquals("a choice sticks after the migration", S1x1, CcLayout.fromJson(chosen)!!.page("controls")!!.tiles.first { it.id == "cleanup" }.size)
    }

    @Test fun aSizeTheUserPickedBeforeTheMigrationIsLeftAlone() {
        val picked = CcLayout(listOf(CcPage("controls", "grid", listOf(CcTile("cleanup", S2x1), CcTile("restart", S1x1))))).toJson().replace("\"v\":2", "\"v\":1")
        val tiles = CcLayout.fromJson(picked)!!.page("controls")!!.tiles
        assertEquals(S2x1, tiles.first { it.id == "cleanup" }.size)
        assertEquals(S2x1, tiles.first { it.id == "restart" }.size)
    }

    @Test fun aTileWithoutASizeDoesNotDropTheLayout() {
        val json = """{"v":2,"pages":[{"id":"controls","icon":"grid","tiles":[{"id":"wifi"},{"id":"bluetooth","size":"2x1"}]}]}"""
        val l = CcLayout.fromJson(json)
        assertTrue(l != null && l.page("controls")!!.tiles.size == 2)
    }
}
