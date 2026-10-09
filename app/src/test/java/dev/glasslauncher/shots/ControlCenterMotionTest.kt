package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
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
    private companion object { const val SPREAD = 10f }

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

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
    /** Where each Control Center tile is, in the captured image's pixels (by its accessibility label). */
    private fun tileBounds(): Map<String, android.graphics.RectF> {
        val labels = listOf("Settings, Fire TV", "Wi-Fi", "Bluetooth", "Launcher Settings", "Game Controllers", "Theme", "Screen Saver", "App Switcher", "Free Memory")
        val out = LinkedHashMap<String, android.graphics.RectF>()
        for (label in labels) {
            val node = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("label $label") { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it == label || (label != "Settings, Fire TV" && it.startsWith(label)) } == true
            }, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: continue
            val b = node.boundsInRoot
            out[label] = android.graphics.RectF(b.left, b.top, b.right, b.bottom)
        }
        return out
    }

    /** Mean colour (r, g, b) of the tile's ink-free top band: the material, without its glyph or label. */
    private fun material(b: android.graphics.Bitmap, rootW: Int, r: android.graphics.RectF): FloatArray {
        val k = b.width / rootW.toFloat()
        val x0 = ((r.left + r.width() * 0.35f) * k).toInt(); val x1 = ((r.left + r.width() * 0.65f) * k).toInt().coerceAtLeast(x0 + 1)
        val y0 = ((r.top + r.height() * 0.08f) * k).toInt(); val y1 = ((r.top + r.height() * 0.16f) * k).toInt().coerceAtLeast(y0 + 1)
        val sum = FloatArray(3); var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
            sum[0] += (c shr 16 and 0xFF); sum[1] += (c shr 8 and 0xFF); sum[2] += (c and 0xFF); n++
        }
        return FloatArray(3) { sum[it] / n }
    }

    /**
     * The user's phone video: tiles were not one colour while Control Center opened (each tinted from the scene
     * right behind it, a texture then swapping in tile by tile). Every tile now draws one flat shared fill:
     * after the tiles land none moves more than 3/255 between frames, and the unfocused tiles of every kind
     * (pills, round buttons, the wide pill) sit within SPREAD/255 of each other, on landing and when settled
     * (they are parts of one baked sheet of the scene, so they differ only by the sheet's slow gradient).
     */
    @Test fun everyTileIsOneMaterialFromLandingOn() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val frames = compose.frames(Button.Select, frames = 60, stepMs = 16, scale = 0.5f)
            compose.settle()
            val rootW = compose.onRoot().fetchSemanticsNode().size.width
            val tiles = tileBounds()
            assertTrue("found only ${tiles.keys}", tiles.size >= 5)
            val landed = 20   // ~340 ms: every row has arrived (the spring's first pass over 1 is ~280 ms)
            for ((name, r) in tiles) {
                val seq = frames.drop(landed).map { material(it, rootW, r) }
                val worst = seq.zipWithNext { a, b -> (0..2).maxOf { kotlin.math.abs(a[it] - b[it]) } }.maxOrNull() ?: 0f
                assertTrue("$name changed colour by ${"%.1f".format(worst)}/255 between frames after landing", worst <= 3f)
            }
            val unfocused = tiles.filterKeys { it != "Settings, Fire TV" }
            for (at in listOf(landed, frames.lastIndex)) {
                val means = unfocused.mapValues { material(frames[at], rootW, it.value) }
                for (c in 0..2) {
                    val lo = means.values.minOf { it[c] }; val hi = means.values.maxOf { it[c] }
                    assertTrue("tiles are not one material in frame $at (channel $c spread ${"%.1f".format(hi - lo)}/255): " +
                        means.mapValues { e -> e.value.map { "%.0f".format(it) } }, hi - lo <= SPREAD)
                }
            }
        }
    }

    /**
     * No ghosts: the focused white Settings tile goes from the bubble's colour to white within a frame or two
     * (not a stretch of grey), and the dim over the rest of the screen never steps.
     */
    @Test fun whiteTileIsNotAGhostAndTheDimIsContinuous() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val frames = compose.frames(Button.Select, frames = 40, stepMs = 16, scale = 0.5f)
            compose.settle()
            val rootW = compose.onRoot().fetchSemanticsNode().size.width
            val settings = tileBounds()["Settings, Fire TV"] ?: error("no Settings tile")
            val l = frames.map { f -> material(f, rootW, settings).let { (it[0] * 3 + it[1] * 6 + it[2]) / 10 } }
            val lo = l.min(); val hi = l.last()
            assertTrue("the Settings tile never turned white: $l", hi - lo > 60f)
            // Clipped in, never faded in: no frame has a grey Settings tile. Pixels of the tile's top band are either
            // the bubble's dark colour (not revealed yet) or white; only the clip's own antialiased edge may be
            // between (a fade shows as the whole band grey).
            val range = hi - lo
            val worstGrey = frames.maxOf { f ->
                val k = f.width / rootW.toFloat()
                var grey = 0; var n = 0
                for (y in ((settings.top + settings.height() * 0.08f) * k).toInt()..((settings.top + settings.height() * 0.16f) * k).toInt())
                    for (x in ((settings.left + settings.width() * 0.35f) * k).toInt()..((settings.left + settings.width() * 0.65f) * k).toInt()) {
                        val c = f.getPixel(x.coerceIn(0, f.width - 1), y.coerceIn(0, f.height - 1))
                        val v = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10f
                        if (v > lo + 0.25f * range && v < hi - 0.1f * range) grey++
                        n++
                    }
                grey.toFloat() / n
            }
            assertTrue("a frame has ${"%.0f".format(worstGrey * 100)}% grey pixels on the Settings tile: $l", worstGrey <= 0.25f)
            assertTrue("the Settings tile settled ${"%.1f".format(hi)} but never reached white-ish", hi > 200f)

            // The dim: a patch of the screen left of the panel darkens smoothly; no frame takes most of it at once.
            val dim = frames.map { f ->
                var sum = 0L; var n = 0
                for (y in (f.height * 0.1f).toInt() until (f.height * 0.9f).toInt() step 2)
                    for (x in (f.width * 0.05f).toInt() until (f.width * 0.35f).toInt() step 2) {
                        val c = f.getPixel(x, y); sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10; n++
                    }
                sum.toFloat() / n
            }
            val total = kotlin.math.abs(dim.last() - dim.first())
            val worst = dim.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f
            assertTrue("the dim stepped ${"%.1f".format(worst)} of ${"%.1f".format(total)} in one frame: $dim", total < 2f || worst <= total * 0.4f)
        }
    }
}
