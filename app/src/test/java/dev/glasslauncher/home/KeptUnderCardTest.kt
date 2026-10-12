package dev.glasslauncher.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** P64 follow-up: under a confirm card the whole stack of overlays beneath stays drawn, nested confirms included. */
class KeptUnderCardTest {
    private fun confirm(title: String) = Overlay.Confirm(title, "msg", "OK") {}

    @Test fun theOverlayUnderASingleConfirmStaysDrawn() {
        val stack = listOf(Overlay.Settings, confirm("one"))
        assertEquals(listOf(true, false), stack.indices.map { keptUnderCard(stack, it) }.let { listOf(it[0], it[1]) })
    }

    @Test fun nestedConfirmsKeepSettingsAndTheConfirmsBeneathDrawn() {
        val stack = listOf(Overlay.Settings, confirm("one"), confirm("two"))
        // Settings and the first confirm stay under the second (the top, drawn on its own account).
        assertEquals(listOf(true, true), listOf(keptUnderCard(stack, 0), keptUnderCard(stack, 1)))
    }

    @Test fun threeDeepKeepsEverythingBeneath() {
        val stack = listOf(Overlay.Settings, confirm("one"), confirm("two"), confirm("three"))
        assertEquals(listOf(true, true, true), (0..2).map { keptUnderCard(stack, it) })
    }

    @Test fun withoutAConfirmOnTopOnlyTheTopIsDrawn() {
        val stack = listOf(Overlay.Settings, Overlay.Tips)
        assertEquals(listOf(false, false), listOf(keptUnderCard(stack, 0), keptUnderCard(stack, 1)))
    }

    @Test fun aSidePanelOrMenuIsNotKeptUnderTheCardButAFullPageIs() {
        // P105 #6: the card is the one glass layer over Home; a panel under it would be a second one.
        val folder = dev.glasslauncher.data.Folder("f", "Folder", emptyList())
        assertEquals(false, keptUnderCard(listOf(Overlay.FolderMenu(folder), confirm("one")), 0))
        assertEquals(true, keptUnderCard(listOf(Overlay.Settings, confirm("one")), 0))
        assertEquals(true, keptUnderCard(listOf(Overlay.ControlCenter, confirm("one")), 0))
        assertEquals("a page under a menu under the card: the menu goes, the page stays", listOf(true, false),
            listOf(Overlay.Settings, Overlay.FolderMenu(folder), confirm("one")).let { st -> listOf(keptUnderCard(st, 0), keptUnderCard(st, 1)) })
    }
}
