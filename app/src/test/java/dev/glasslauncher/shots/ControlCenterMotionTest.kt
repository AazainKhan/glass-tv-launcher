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

    /**
     * Plan §11 (the user's video): the tiles keep one colour through open and close. Each tile's area may not
     * jump between consecutive frames once it is in place, and closing never brightens it past its settled
     * look (the old swap flashed white, then grey, then smoky).
     */
    @Test fun tilesKeepOneColourThroughOpenAndClose() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val opening = compose.frames(Button.Select, frames = 72, stepMs = 16)
            compose.settle()
            val closing = compose.frames({ scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }, frames = 24, stepMs = 16)
            // The Wi-Fi and Bluetooth pills (not the focused Settings tile, whose white fill fades in by design).
            fun level(b: android.graphics.Bitmap): Float {
                var sum = 0L; var n = 0
                for (y in (b.height * 0.17f).toInt() until (b.height * 0.33f).toInt() step 2)
                    for (x in (b.width * 0.86f).toInt() until (b.width * 0.95f).toInt() step 2) {
                        val c = b.getPixel(x, y)
                        sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10; n++
                    }
                return sum.toFloat() / n
            }
            val open = opening.map(::level)
            val settled = open.last()
            // The tiles are in by ~60% of the spring (~frame 14), bouncing with the bubble until it settles
            // (~700 ms); from frame 40 on only the texture's detail may arrive (it waits for the settle).
            val landed = open.drop(40)
            val worst = landed.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f
            assertTrue("tiles changed colour after landing (${"%.1f".format(worst)}/255 in a frame): $landed", worst <= 8f)
            // The landed fill is the glass's own colour: the texture fading in may not shift it (without the
            // matched fill it dropped ~10/255, 57 to 47).
            val shift = landed.maxOf { kotlin.math.abs(it - settled) }
            assertTrue("the tiles shifted ${"%.1f".format(shift)}/255 while their glass faded in: $landed", shift <= 4f)
            val close = closing.map(::level)
            val brightest = close.maxOrNull() ?: 0f
            assertTrue("closing never moved (Back didn't close Control Center): $close", kotlin.math.abs(close.last() - close.first()) > 3f)
            assertTrue("closing brightened the tiles to ${"%.1f".format(brightest)} (settled ${"%.1f".format(settled)}): $close", brightest <= settled + 4f)
        }
    }
}
