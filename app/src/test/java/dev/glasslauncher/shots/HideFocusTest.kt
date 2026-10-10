package dev.glasslauncher.shots

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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

    private fun appBoxes(): Map<String, Float> =
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("an app tile") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("app:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().associate {
            it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] to it.boundsInRoot.left
        }

    /** After Hide the tiles that followed it close the gap by gliding, not in one frame (the continuity rule). */
    @Test fun theTilesAfterAHiddenOneGlideIntoTheGap() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down, Button.Right) // first grid row, second tile
            compose.settle()
            val before = appBoxes()
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down)
            compose.settle()
            compose.mainClock.autoAdvance = false
            compose.onRoot().performKeyInput { pressKey(Button.Select.key) }
            var previous = before
            val biggest = HashMap<String, Float>()
            val total = HashMap<String, Float>()
            repeat(150) {
                // The hide is written on a background thread: give real time too, then the glide runs on fake time.
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
                val now = appBoxes()
                for ((tag, x) in now) {
                    val prev = previous[tag] ?: continue
                    biggest[tag] = maxOf(biggest[tag] ?: 0f, kotlin.math.abs(x - prev))
                    total[tag] = x - (before[tag] ?: x)
                }
                previous = now
            }
            compose.mainClock.autoAdvance = true
            val movedBy = total.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            check(movedBy > 100f) { "no tile moved into the gap (max shift $movedBy px)" }
            // A glide spreads a move over many frames: no frame carries more than a third of a tile's whole
            // travel (a jump carries all of it, in one frame).
            for ((tag, travel) in total) {
                if (kotlin.math.abs(travel) < 100f) continue
                val step = biggest.getValue(tag)
                check(step < kotlin.math.abs(travel) / 3f) { "$tag moved $step of its $travel px in one frame: it jumped instead of gliding" }
            }
        }
    }
}
