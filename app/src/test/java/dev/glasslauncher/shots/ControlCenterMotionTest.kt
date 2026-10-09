package dev.glasslauncher.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The ink-free band of a tile (below its sheen, above its glyph and labels), as fractions of its height. */
internal const val BAND0 = 0.15f
internal const val BAND1 = 0.22f

/** Mean colour of the baked sheet's cells under the window-pixel rectangle: what a tile showing the sheet should average to. */
internal fun sheetMean(sheet: dev.glasslauncher.home.CcSheet, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
    val px = sheet.pixels ?: error("no baked sheet (the flat fallback was used)")
    fun cx(x: Float) = ((x - sheet.panel.left) / sheet.panel.width * px.width).toInt().coerceIn(0, px.width - 1)
    fun cy(y: Float) = ((y - sheet.panel.top) / sheet.panel.height * px.height).toInt().coerceIn(0, px.height - 1)
    val sum = FloatArray(3); var n = 0
    for (j in cy(y0)..cy(y1)) for (i in cx(x0)..cx(x1)) {
        val c = px.pixels[i + j * px.width]
        sum[0] += (c shr 16 and 0xFF); sum[1] += (c shr 8 and 0xFF); sum[2] += (c and 0xFF); n++
    }
    return FloatArray(3) { sum[it] / n }
}

/** Mean colour (r, g, b) of the tile's ink-free top band: the material, without its glyph or label. */
internal fun material(b: android.graphics.Bitmap, rootW: Int, r: android.graphics.RectF): FloatArray {
    val k = b.width / rootW.toFloat()
    val x0 = ((r.left + r.width() * 0.35f) * k).toInt(); val x1 = ((r.left + r.width() * 0.65f) * k).toInt().coerceAtLeast(x0 + 1)
    val y0 = ((r.top + r.height() * BAND0) * k).toInt(); val y1 = ((r.top + r.height() * BAND1) * k).toInt().coerceAtLeast(y0 + 1)
    val sum = FloatArray(3); var n = 0
    for (y in y0 until y1) for (x in x0 until x1) {
        val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        sum[0] += (c shr 16 and 0xFF); sum[1] += (c shr 8 and 0xFF); sum[2] += (c and 0xFF); n++
    }
    return FloatArray(3) { sum[it] / n }
}


/** Fraction of the pixels in the top band of [r] (the tile's ink-free strip) that are neither near [lo] (not revealed) nor near [hi] (white). */
internal fun greyFraction(f: android.graphics.Bitmap, rootW: Int, r: android.graphics.RectF, lo: Float, hi: Float): Float {
    val k = f.width / rootW.toFloat(); val range = hi - lo
    var grey = 0; var n = 0
    for (y in ((r.top + r.height() * BAND0) * k).toInt()..((r.top + r.height() * BAND1) * k).toInt())
        for (x in ((r.left + r.width() * 0.35f) * k).toInt()..((r.left + r.width() * 0.65f) * k).toInt()) {
            val c = f.getPixel(x.coerceIn(0, f.width - 1), y.coerceIn(0, f.height - 1))
            val v = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10f
            if (v > lo + 0.25f * range && v < hi - 0.1f * range) grey++
            n++
        }
    return grey.toFloat() / n
}

/**
 * Control Center's opening, frame by frame on a paused clock: the panel grows in over many frames like an
 * app opening, with no frame where the tiles suddenly appear.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class ControlCenterMotionTest {

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

    /**
     * The user's phone video: tiles were not one colour while Control Center opened (each tinted from the scene
     * right behind it, a texture then swapping in tile by tile). Every tile now draws one flat shared fill:
     * after the tiles land none moves more than 3/255 between frames, and the unfocused tiles of every kind
     * (pills, round buttons, the wide pill) show the one baked sheet, on landing and when settled.
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
            val landed = 27   // ~430 ms: every control has grown out of its drop (P49: open ~350-400 ms, the farthest last)
            for ((name, r) in tiles) {
                val seq = frames.drop(landed).map { material(it, rootW, r) }
                val worst = seq.zipWithNext { a, b -> (0..2).maxOf { kotlin.math.abs(a[it] - b[it]) } }.maxOrNull() ?: 0f
                assertTrue("$name changed colour by ${"%.1f".format(worst)}/255 between frames after landing", worst <= 3f)
            }
            // One material: each unfocused tile shows its own part of the one baked sheet (the sheet's cells
            // under its band, already tinted and dimmed), within 4/255, on landing and when settled.
            val sheet = dev.glasslauncher.home.CcMaterial.last ?: error("Control Center made no sheet")
            for (at in listOf(landed, frames.lastIndex)) for ((name, r) in tiles.filterKeys { it != "Settings, Fire TV" }) {
                val want = sheetMean(sheet, r.left + r.width() * 0.35f, r.top + r.height() * BAND0, r.left + r.width() * 0.65f, r.top + r.height() * BAND1)
                val got = material(frames[at], rootW, r)
                for (c in 0..2) assertTrue("$name is not the sheet's colour in frame $at (channel $c: ${"%.1f".format(got[c])} vs ${"%.1f".format(want[c])})", kotlin.math.abs(got[c] - want[c]) <= 4f)
            }
        }
    }

    /**
     * Light appearance has its own glass: over the same scene its settled tiles are clearly brighter than the dark
     * appearance's, and Control Center's text keeps 4.5:1 on them. Control Center is one look in both themes (white
     * text on glass over the dimmed screen, see ControlCenter()), so the text is the dark palette's in both.
     */
    @Test fun lightAppearanceGetsALightGlassThatKeepsItsTextLegible() {
        fun lum(c: FloatArray): Float {
            fun lin(v: Float) = (v / 255f).let { if (it <= 0.04045f) it / 12.92f else Math.pow(((it + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
            return 0.2126f * lin(c[0]) + 0.7152f * lin(c[1]) + 0.0722f * lin(c[2])
        }
        fun ratio(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        // Mean luminance of the unfocused tiles' ink-free band once settled, and the worst text contrast on them.
        fun measure(theme: dev.glasslauncher.data.ThemeMode): Pair<Float, Float> {
            TvHarness.setUp(config = { it.copy(theme = theme) })
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome()
                compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
                compose.waitForTag("control-center")
                compose.settle()
                val frame = compose.frames({}, frames = 2, stepMs = 16, scale = 0.5f).last()
                val rootW = compose.onRoot().fetchSemanticsNode().size.width
                val text = lum(dev.glasslauncher.ui.Palette(light = false).primary.let { floatArrayOf(it.red * 255f, it.green * 255f, it.blue * 255f) })
                val tiles = tileBounds().filterKeys { it != "Settings, Fire TV" }.values.map { lum(material(frame, rootW, it)) }
                assertTrue("found no tiles", tiles.size >= 5)
                return tiles.average().toFloat() to tiles.minOf { ratio(text, it) }
            }
        }
        val (dark, darkContrast) = measure(dev.glasslauncher.data.ThemeMode.Dark)
        val (light, lightContrast) = measure(dev.glasslauncher.data.ThemeMode.Light)
        println("CC tile luminance: dark ${"%.3f".format(dark)} (text ${"%.1f".format(darkContrast)}:1), light ${"%.3f".format(light)} (text ${"%.1f".format(lightContrast)}:1)")
        assertTrue("light tiles ($light) are not clearly brighter than dark ones ($dark)", light > dark * 1.5f)
        assertTrue("light text is ${"%.2f".format(lightContrast)}:1 on the tiles", lightContrast >= 4.5f)
        assertTrue("dark text is ${"%.2f".format(darkContrast)}:1 on the tiles", darkContrast >= 4.5f)
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
            val worstGrey = frames.maxOf { f -> greyFraction(f, rootW, settings, lo, hi) }
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

    /**
     * The device measured the open-time bake at 224 ms, past the open's 60 ms head start, so every open drew the
     * flat fallback. The sheet is baked with the backdrop (off the main thread, before Control Center is asked
     * for): Home's backdrop already carries it, and opening Control Center bakes nothing and draws that very sheet.
     */
    @Test fun theSheetIsBakedWithTheBackdropNotAtOpen() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val home = dev.glasslauncher.home.ControlCenterWindow.homeBackdrop ?: error("Home has no backdrop")
            val baked = home.ccSheet
            org.junit.Assert.assertNotNull("Home's backdrop carries no Control Center sheet before Control Center opens", baked)
            val bakes = dev.glasslauncher.glass.GlassMatch.sheetBakes
            compose.frames(Button.Select, frames = 30, stepMs = 16, scale = 0.5f)
            compose.settle()
            org.junit.Assert.assertEquals("opening Control Center baked a sheet", bakes, dev.glasslauncher.glass.GlassMatch.sheetBakes)
            val used = dev.glasslauncher.home.CcMaterial.last ?: error("Control Center has no material")
            org.junit.Assert.assertSame("Control Center does not draw the backdrop's own sheet", baked!!.bitmap, used.bitmap)
        }
    }

    /**
     * The wash covers the whole screen, the expanded Top Shelf row included: at rest, what is outside the panel
     * is at most 0.58 as bright as it was before Control Center opened (CC_DIM_ALPHA is 0.42 black).
     */
    @Test fun theDimCoversTheWholeScreenAtRest() {
        // Two states: the featured row expanded (the Top Shelf), and the plain Home with the dock focused.
        for (ups in listOf(1, 0)) {
            TvHarness.setUp()
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome()
                compose.press(*Array(ups) { Button.Up })
                compose.settle()
                // Home as it is the moment before Select opens Control Center (focus on the status pill).
                compose.press(*Array(3 - ups) { Button.Up })
                compose.settle()
                fun luma(b: android.graphics.Bitmap, y0: Float, y1: Float, x0: Float = 0.02f, x1: Float = 0.64f): Float {
                    var sum = 0L; var n = 0
                    for (y in (b.height * y0).toInt() until (b.height * y1).toInt() step 4)
                        for (x in (b.width * x0).toInt() until (b.width * x1).toInt() step 4) {
                            val c = b.getPixel(x, y); sum += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10; n++
                        }
                    return sum.toFloat() / n
                }
                val b0 = compose.onRoot().captureToImage().asAndroidBitmap()
                compose.press(Button.Select)
                compose.settle()
                val b1 = compose.onRoot().captureToImage().asAndroidBitmap()
                // The whole screen, and the bottom band separately (the shelf's cards sit there).
                val ratios = listOf(Triple("screen", 0.04f, 0.96f), Triple("top", 0.04f, 0.3f), Triple("middle", 0.3f, 0.7f), Triple("bottom band", 0.70f, 0.96f)).map { (name, y0, y1) -> name to luma(b1, y0, y1) / luma(b0, y0, y1) }
                assertTrue("with $ups Ups first, outside the panel the screen is as bright as before by $ratios (want <= 0.58 each)", ratios.all { it.second <= 0.58f + 0.01f })
                // The right side below the panel (under the round buttons: the tray and shelf cards): dimmed too.
                val right = luma(b1, 0.80f, 0.96f, 0.70f, 0.98f) / luma(b0, 0.80f, 0.96f, 0.70f, 0.98f)
                assertTrue("with $ups Ups first, the right side under the panel is $right as bright as before (want <= 0.60)", right <= 0.60f)
            }
        }
    }
}
