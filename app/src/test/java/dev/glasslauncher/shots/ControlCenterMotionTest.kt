package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Control Center's opening, frame by frame on a paused clock: the panel grows in over many frames like an
 * app opening, with no frame where the tiles suddenly appear.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class ControlCenterMotionTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Text wins over a smaller grid (§8.8): no Control Center label is cut off by its tile.
    @Test fun labelsFitTheirTiles() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            val labels = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("control-center").let { androidx.compose.ui.test.hasAnyAncestor(it) }
                .and(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("found no labels to check", labels.size >= 5)
            val overflowing = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("control-center").let { androidx.compose.ui.test.hasAnyAncestor(it) }
                .and(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)), useUnmergedTree = true)
                .fetchSemanticsNodes().mapNotNull { node ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    // An ellipsis shortens the text to fit, so look for one rather than for overflow.
                    results.firstOrNull()?.takeIf { r -> r.hasVisualOverflow || (0 until r.lineCount).any { r.isLineEllipsized(it) } }?.layoutInput?.text?.text
                }
            assertTrue("labels cut off in Control Center: $overflowing", overflowing.isEmpty())
        }
    }

    @Test fun opensWithAContinuousGrow() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            // Up expands the shelf, Up reaches Play, Up the status pill.
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val shots = compose.frames(Button.Select, frames = 36, stepMs = 16)
            // Mean brightness of the tiles' area (the right third, under the header) per frame.
            val levels = shots.map { b ->
                var sum = 0L; var n = 0
                for (y in (b.height * 0.15f).toInt() until (b.height * 0.7f).toInt() step 2)
                    for (x in (b.width * 0.68f).toInt() until (b.width * 0.97f).toInt() step 2) {
                        val c = b.getPixel(x, y)
                        sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10; n++
                    }
                sum.toFloat() / n
            }
            val total = kotlin.math.abs(levels.last() - levels.first())
            val steps = levels.zipWithNext { a, b -> kotlin.math.abs(b - a) }
            val moving = steps.count { it > total * 0.02f }
            val worst = steps.maxOrNull() ?: 0f
            assertTrue("nothing changed while opening: $levels", total > 3f)
            assertTrue("a sudden jump: ${"%.1f".format(worst)} of ${"%.1f".format(total)} in one 16 ms frame: $levels", worst < total * 0.3f)
            assertTrue("the opening spans only $moving frames (want a grow over many): $levels", moving >= 12)
        }
    }
}
