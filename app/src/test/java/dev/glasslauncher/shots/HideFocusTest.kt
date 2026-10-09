package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Hide (and Uninstall) take a tile off Home: focus moves to its neighbour in place, and the screen doesn't scroll. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class HideFocusTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private fun top(tag: String): Float? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    @Test fun hidingAGridAppFocusesItsNeighbourWithoutScrolling() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            // The second grid row (the list scrolls to it), second tile.
            compose.press(Button.Down, Button.Down, Button.Right)
            compose.settle()
            val victim = compose.focused()!!
            check(victim.startsWith("app:")) { "focus is on $victim" }
            val trayBefore = top("tray")
            check(trayBefore != null) { "the tray isn't composed" }
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down) // Edit Home Screen, Move to…, Change Icon, Hide
            compose.settle()
            compose.press(Button.Select)
            compose.settle()
            val after = compose.focused()
            check(after != null && after != victim) { "focus is ${after} (was $victim)" }
            // The tile that took its place is its neighbour in the grid (the next tile, here the Settings tile),
            // not the first tray app (which also scrolled the whole page to the top).
            check(after != "app:com.netflix.ninja") { "focus jumped to the first tray app" }
            assertEquals("the neighbour", "settings-tile", after)
            assertEquals("the screen did not scroll", trayBefore, top("tray"))
        }
    }
}
