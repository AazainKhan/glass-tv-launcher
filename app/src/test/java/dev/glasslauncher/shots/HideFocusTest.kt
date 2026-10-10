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

    /**
     * Presses Select with the clock stopped, then waits in real time (the clock barely moving) until the hide's
     * config write has landed in Home's layout: it is on another thread, and fake time must not run out the
     * glide's tail before it arrives.
     */
    private fun selectAndWaitForLayout() {
        val seen = dev.glasslauncher.home.GlideTracker.layoutsSeen
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Button.Select.key) }
        repeat(2000) {
            Thread.sleep(10)
            // Reading the tree lets the main looper deliver what the background thread posted.
            appBoxes()
            compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
            if (dev.glasslauncher.home.GlideTracker.layoutsSeen > seen) return
        }
        error("the hide never landed")
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

    /**
     * P62: the hidden tile fades out where it stands (about 200 ms) before the others glide into the gap; it
     * doesn't vanish in one frame with the others already on the move.
     */
    @Test fun theHiddenTileFadesBeforeTheOthersGlide() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down, Button.Right) // first grid row, second tile
            compose.settle()
            val victim = compose.focused()!!
            val before = appBoxes()
            check(victim in before) { "$victim isn't on screen" }
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down)
            compose.settle()
            selectAndWaitForLayout()
            var landed = true           // the layout change is in
            var presentAfterLanding = 0 // frames the victim stayed on screen after that
            var movedWhilePresent = 0f  // how far any other tile moved while it was there
            var gone = false
            var reappeared = false
            var focusWhilePresent: String? = null
            repeat(150) {
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
                val now = appBoxes()
                if (gone && victim in now) reappeared = true
                if (landed && !gone) {
                    if (victim in now) {
                        compose.focused()?.let { f -> if (f != victim) focusWhilePresent = f }
                        presentAfterLanding++
                        for ((tag, x) in now) if (tag != victim) movedWhilePresent = maxOf(movedWhilePresent, kotlin.math.abs(x - (before[tag] ?: x)))
                    } else gone = true
                }
            }
            compose.mainClock.autoAdvance = true
            check(gone) { "$victim never left the screen" }
            check(!reappeared) { "$victim came back on screen after it had left (a flash at full opacity)" }
            assertEquals("focus stays on the neighbour when the layout swaps", focusWhilePresent, compose.focused())
            check(presentAfterLanding >= 8) { "$victim vanished after $presentAfterLanding frames: it should fade for ~200 ms" }
            check(movedWhilePresent < 20f) { "other tiles moved $movedWhilePresent px while $victim was still fading" }
        }
    }

    /** P62: a tile wrapping to the previous row glides beneath its row's other tiles, not across their faces. */
    @Test fun aTileWrappingToThePreviousRowGlidesBeneathTheOthers() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down) // first grid row, first tile: the second row's first tile wraps up into the gap
            compose.settle()
            val victim = compose.focused()!!
            val wraps = dev.glasslauncher.home.GlideTracker.wrapGlides
            fun pos() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("an app tile") { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("app:") == true
            }, useUnmergedTree = true).fetchSemanticsNodes().joinToString { "${it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("app:")}@${it.boundsInRoot.left.toInt()},${it.boundsInRoot.top.toInt()}" }
            val p0 = pos()
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down)
            compose.settle()
            selectAndWaitForLayout()
            repeat(150) {
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
            }
            compose.mainClock.autoAdvance = true
            check(dev.glasslauncher.home.GlideTracker.wrapGlides > wraps) { "no tile changed rows (wraps ${dev.glasslauncher.home.GlideTracker.wrapGlides} was $wraps); focus was ${compose.focused()}\nbefore $p0\nafter ${pos()}" }
        }
    }

    /**
     * P62 on the stick: the tiles after a hidden one popped into their final places for one frame (their glide
     * offset came a frame after their layout) before gliding from where they were. On the JVM every step of a
     * frame runs before the next is sampled, so this checks the cause: every glide starts from the position
     * predicted in the composing frame, none waits for the measured one.
     */
    @Test fun theGlideOffsetIsInTheSameFrameAsTheNewPlace() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down, Button.Right) // first grid row, second tile
            compose.settle()
            val predicted = dev.glasslauncher.home.GlideTracker.predictedGlides
            val measured = dev.glasslauncher.home.GlideTracker.measuredGlides
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down)
            compose.settle()
            selectAndWaitForLayout()
            repeat(120) {
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
                appBoxes()
            }
            compose.mainClock.autoAdvance = true
            val p = dev.glasslauncher.home.GlideTracker.predictedGlides - predicted
            val m = dev.glasslauncher.home.GlideTracker.measuredGlides - measured
            check(p > 0) { "no glide started from a predicted position ($p predicted, $m measured)" }
            assertEquals("glides that waited for the measured position (a frame late)", 0, m)
        }
    }
}
