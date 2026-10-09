package dev.glasslauncher.shots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The focused shelf card's shadow is soft and runs on past the bottom of the row's own bounds. A lazy row
 * clips its cross axis a fixed 30 dp past its bounds, so a row that ends close to the card cuts the halo off
 * there: a step in the shadow, which showed as a hard edge about 21 dp under the card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FeaturedRowShadowTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** FocusTile's default focusedScale, which the shelf's cards use. */
    private val FOCUSED_SCALE = 1.2f

    @Test fun theFocusedCardsShadowFadesOutBelowItWithoutAStep() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up)
            compose.settle()
            val tag = compose.focused()
            assertTrue("focus is on $tag, not a shelf card", tag != null && tag.startsWith("featured:"))
            val card = compose.onNode(hasTestTag(tag!!), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val image = compose.stableImage()

            // The bounds are the layout's; the focused card is drawn FOCUSED_SCALE times bigger about its centre.
            val bottom = card.bottom + card.height * (FOCUSED_SCALE - 1f) / 2f
            // Mean luma of a strip under the card's left end, per 3 px row, from just under its bottom edge.
            // The card's title is centred under it, so the strip sits at the left end, clear of the text.
            val x0 = card.left.roundToInt() + 8
            val x1 = card.left.roundToInt() + 44
            val top = bottom.roundToInt() + 4
            val rows = (top until image.height - 3 step 3).map { y ->
                var sum = 0.0
                var n = 0
                for (yy in y until y + 3) for (x in x0 until x1) {
                    val c = image.getPixel(x, yy)
                    sum += 0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)
                    n++
                }
                sum / n
            }
            assertTrue("only ${rows.size} rows below the card (bottom $bottom, screen ${image.height})", rows.size >= 12)
            val steps = rows.zipWithNext { a, b -> abs(b - a) }
            val worst = steps.max()
            val at = top + steps.indexOf(worst) * 3
            assertTrue(
                "a step of ${"%.1f".format(worst)} luma between 3 px rows at y=$at (card bottom $bottom); rows: ${rows.joinToString { "%.0f".format(it) }}",
                worst <= 3.0,
            )
        }
    }
}
