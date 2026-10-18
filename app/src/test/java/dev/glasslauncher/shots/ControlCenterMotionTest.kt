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

/** Where across a tile its ink-free band sits: the middle of a circle or square; a pill's gap between its glyph column and its text (P138). */
internal fun bandY0(w: Float, h: Float) = if (w > h * 1.5f) 0.40f else BAND0
internal fun bandY1(w: Float, h: Float) = if (w > h * 1.5f) 0.60f else BAND1
internal fun bandLo(w: Float, h: Float) = if (w > h * 1.5f) 0.31f else 0.35f
internal fun bandHi(w: Float, h: Float) = if (w > h * 1.5f) 0.37f else 0.65f

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
/**
 * [skipInk]: leave out near-white pixels (a glyph or label, luma > 200): the glass under them is what text sits on.
 * A focused tile is drawn scaled about its centre while [r] is its resting rectangle, so a big glyph (the Settings
 * gear) reaches into the band during the scale; the glass itself is capped far below that level (CcAdapt.CAP).
 */
internal fun material(b: android.graphics.Bitmap, rootW: Int, r: android.graphics.RectF, skipInk: Boolean = false): FloatArray {
    val k = b.width / rootW.toFloat()
    val x0 = ((r.left + r.width() * bandLo(r.width(), r.height())) * k).toInt(); val x1 = ((r.left + r.width() * bandHi(r.width(), r.height())) * k).toInt().coerceAtLeast(x0 + 1)
    val y0 = ((r.top + r.height() * bandY0(r.width(), r.height())) * k).toInt(); val y1 = ((r.top + r.height() * bandY1(r.width(), r.height())) * k).toInt().coerceAtLeast(y0 + 1)
    val sum = FloatArray(3); var n = 0
    for (y in y0 until y1) for (x in x0 until x1) {
        val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        if (skipInk && ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 > 200) continue
        sum[0] += (c shr 16 and 0xFF); sum[1] += (c shr 8 and 0xFF); sum[2] += (c and 0xFF); n++
    }
    if (n == 0) return FloatArray(3) { 0f }
    return FloatArray(3) { sum[it] / n }
}


/** Fraction of the pixels in the top band of [r] (the tile's ink-free strip) that are neither near [lo] (not revealed) nor near [hi] (white). */
internal fun greyFraction(f: android.graphics.Bitmap, rootW: Int, r: android.graphics.RectF, lo: Float, hi: Float): Float {
    val k = f.width / rootW.toFloat(); val range = hi - lo
    var grey = 0; var n = 0
    for (y in ((r.top + r.height() * bandY0(r.width(), r.height())) * k).toInt()..((r.top + r.height() * bandY1(r.width(), r.height())) * k).toInt())
        for (x in ((r.left + r.width() * bandLo(r.width(), r.height())) * k).toInt()..((r.left + r.width() * bandHi(r.width(), r.height())) * k).toInt()) {
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

    /**
     * Switching pages from the menu bar (P138, FW setHostedViewController:focusHeading:): the panel slides over frame by frame on a
     * paused clock. No frame is empty (the edge energy of the tile area never falls under half of the weaker settled page: the fades
     * are complementary), none is a pop (no step between frames is most of the whole change), and the settled pages differ.
     */
    @Test fun switchingPagesIsContinuous() {
        TvHarness.setUp()
        // The Alexa page exists only with the Alexa app (P155): without it there is no second page to switch to.
        TvHarness.installAlexa()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            compose.press(Button.Up)
            compose.settle()
            val shots = compose.frames(Button.Right, frames = 40, stepMs = 16)
            fun energy(b: android.graphics.Bitmap): Float {
                var e = 0f
                val x0 = (b.width * 0.68f).toInt(); val x1 = (b.width * 0.97f).toInt() - 1
                val y0 = (b.height * 0.13f).toInt(); val y1 = (b.height * 0.7f).toInt() - 1
                fun l(x: Int, y: Int): Float { val c = b.getPixel(x, y); return ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10f }
                for (y in y0 until y1) for (x in x0 until x1) e += kotlin.math.abs(l(x + 1, y) - l(x, y)) + kotlin.math.abs(l(x, y + 1) - l(x, y))
                return e
            }
            fun diff(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Float {
                var d = 0f; var n = 0
                for (y in (a.height * 0.13f).toInt() until (a.height * 0.7f).toInt()) for (x in (a.width * 0.68f).toInt() until (a.width * 0.97f).toInt()) {
                    val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                    d += (kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + kotlin.math.abs((p and 0xFF) - (q and 0xFF))) / 3f; n++
                }
                return d / n
            }
            val first = shots.first(); val last = shots.last()
            val total = diff(first, last)
            // The page did change: the Alexa page's controls are there (the darker one-material glass, P188, leaves
            // less pixel difference between pages than the old light glass did, so pixels alone don't say).
            assertTrue("the page did not change: no Alexa page after Right (diff $total)",
                compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Ask Alexa", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            val floor = 0.5f * minOf(energy(first), energy(last))
            val low = shots.withIndex().filter { energy(it.value) < floor }.map { it.index }
            assertTrue("frames $low show (almost) nothing: neither page is visible", low.isEmpty())
            val steps = shots.zipWithNext { a, b -> diff(a, b) }
            assertTrue("a pop: one frame carries ${steps.max()} of a total ${total}", steps.max() < total * 0.6f)
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
            // The span, not the net change: the open dims and then reveals (a dip, then a rise), and with no white focus
            // fill (P173) the net change is small while the motion still covers the whole span.
            val total = levels.max() - levels.min()
            val steps = levels.zipWithNext { a, b -> kotlin.math.abs(b - a) }
            val moving = steps.count { it > total * 0.02f }
            val worst = steps.maxOrNull() ?: 0f
            assertTrue("nothing changed while opening: $levels", total > 3f)
            // P195: the glass is Apple's darker wash now, so the span is small (~9 levels) and the screen's own dim
            // step (~2.9 a frame, unchanged) is a larger share of it; a jump is judged against at least a 10-level span.
            assertTrue("a sudden jump: ${"%.1f".format(worst)} of ${"%.1f".format(total)} in one 16 ms frame: $levels", worst < maxOf(total, 10f) * 0.3f)
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
            val root = compose.onRoot().fetchSemanticsNode().size
            val box = tileBounds().entries.first { it.key.startsWith("Bluetooth") }.value
            val closing = compose.frames({ scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }, frames = 24, stepMs = 16)
            // The Bluetooth pill (not the focused Settings tile, whose white fill fades in by design): its middle value over
            // its settled box, a median so the pill's own text (F03 moved the grid up under it) does not count.
            fun level(b: android.graphics.Bitmap): Float {
                val v = ArrayList<Int>()
                val x0 = (b.width * box.left / root.width).toInt(); val x1 = (b.width * box.right / root.width).toInt()
                val y0 = (b.height * box.top / root.height).toInt(); val y1 = (b.height * box.bottom / root.height).toInt()
                for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                    val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
                    v += ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
                }
                v.sort()
                return if (v.isEmpty()) 0f else v[v.size / 2].toFloat()
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
        val labels = listOf("Settings, Fire TV", "Wi-Fi", "Bluetooth", "Sleep Timer", "Launcher Settings", "Game Controllers", "Theme", "Screen Saver", "App Switcher", "Clean Up")
        val out = LinkedHashMap<String, android.graphics.RectF>()
        for (label in labels) {
            val node = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("label $label") { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it == label || (label != "Settings, Fire TV" && it.startsWith(label)) } == true
            }, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: continue
            val b = node.boundsInRoot
            // Rows past the panel's last whole row scroll (P83): their bounds are clipped away, so they aren't on screen.
            if (b.height < 40f) continue
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
            // (Not the Sleep pill: the focused Settings tile above it casts its focus shadow, 15 dp down, onto it, P138.)
            // One material: each unfocused tile shows its own part of the one baked sheet (the sheet's cells
            // under its band, already tinted and dimmed), within 4/255, on landing and when settled.
            val sheet = dev.glasslauncher.home.CcMaterial.last ?: error("Control Center made no sheet")
            for (at in listOf(landed, frames.lastIndex)) for ((name, r) in tiles.filterKeys { it != "Settings, Fire TV" && !it.startsWith("Sleep") }) {
                val want = sheetMean(sheet, r.left + r.width() * bandLo(r.width(), r.height()), r.top + r.height() * bandY0(r.width(), r.height()), r.left + r.width() * bandHi(r.width(), r.height()), r.top + r.height() * bandY1(r.width(), r.height()))
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
                assertTrue("found no tiles", tiles.size >= 4)
                return tiles.average().toFloat() to tiles.minOf { ratio(text, it) }
            }
        }
        val (dark, darkContrast) = measure(dev.glasslauncher.data.ThemeMode.Dark)
        val (light, lightContrast) = measure(dev.glasslauncher.data.ThemeMode.Light)
        println("CC tile luminance: dark ${"%.3f".format(dark)} (text ${"%.1f".format(darkContrast)}:1), light ${"%.3f".format(light)} (text ${"%.1f".format(lightContrast)}:1)")
        // P173 (user): one Control Center style in Light and Dark: the same glass, white text legible on both.
        assertTrue("light-theme tiles ($light) differ from dark-theme ones ($dark): Control Center has one style", kotlin.math.abs(light - dark) <= 0.02f)
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
            // P173: focus is the glass catching light, never a white fill.
            assertTrue("the focused Settings tile filled white (P173: one glass style): $l", l.max() < 200f)

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
                // The strip just left of the panel, low on the screen (the tray and shelf cards): dimmed too. (The panel
                // itself now reaches the bottom: two more rows and the Edit pill, P83.)
                val right = luma(b1, 0.80f, 0.96f, 0.64f, 0.68f) / luma(b0, 0.80f, 0.96f, 0.64f, 0.68f)
                assertTrue("with $ups Ups first, the right side under the panel is $right as bright as before (want <= 0.60)", right <= 0.60f)
            }
        }
    }

    /**
     * P188: one constant material. Over a white backdrop and a black one: (1) each tile's glass, settled, is within 3
     * levels whether it is focused or not (measured at its live bounds, so the x1.21 lift doesn't move the sample);
     * (2) through six focus moves, every frame including mid-move ones, white text keeps 4.5:1 on every tile, so no
     * tile washes out or shows the white backdrop through. Focus may change only the scale, the shadow and the rim.
     */
    @Test fun oneMaterialFocusedOrNotOnAnyBackdrop() {
        fun lin(v: Float) = (v / 255f).let { if (it <= 0.04045f) it / 12.92f else Math.pow(((it + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
        fun lum(c: FloatArray) = 0.2126f * lin(c[0]) + 0.7152f * lin(c[1]) + 0.0722f * lin(c[2])
        fun solid(argb: Int): dev.glasslauncher.data.Wallpaper {
            val f = java.io.File.createTempFile("cc-bg", ".png")
            android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f.outputStream())
            return dev.glasslauncher.data.Wallpaper(dev.glasslauncher.data.WallpaperKind.File, f.absolutePath)
        }
        val problems = mutableListOf<String>()
        for ((name, argb) in listOf("white" to android.graphics.Color.WHITE, "black" to android.graphics.Color.BLACK)) {
            val wp = solid(argb)
            TvHarness.setUp(config = { it.copy(background = dev.glasslauncher.data.BackgroundMode.Wallpaper, wallpaperDark = wp, wallpaperLight = wp) })
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForHome()
                compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
                compose.waitForTag("control-center")
                compose.settle()
                val rootW = compose.onRoot().fetchSemanticsNode().size.width
                fun still() = compose.frames({}, frames = 2, stepMs = 16, scale = 0.5f).last()
                // (1) Wi-Fi unfocused, then focused (Settings starts focused; Wi-Fi sits next to it).
                val wifiRest = tileBounds().getValue("Wi-Fi").let { material(still(), rootW, it) }
                compose.press(Button.Left); compose.settle()
                val wifiFocused = tileBounds().getValue("Wi-Fi").let { material(still(), rootW, it) }
                val d = (0..2).maxOf { kotlin.math.abs(wifiFocused[it] - wifiRest[it]) }
                if (d > 3f) problems += "$name: Wi-Fi's glass is ${"%.0f".format(d)} levels different focused (${wifiFocused.toList()}) than not (${wifiRest.toList()})"
                compose.press(Button.Right); compose.settle()
                // (2) every frame of six focus moves: white text stays legible on every tile.
                val tiles = tileBounds()
                for (k in listOf(Button.Right, Button.Down, Button.Left, Button.Down, Button.Right, Button.Up))
                    for ((fi, f) in compose.frames(k, frames = 8, stepMs = 16, scale = 0.5f).withIndex()) for ((label, r) in tiles) {
                        val ratio = 1.05f / (lum(material(f, rootW, r, skipInk = true)) + 0.05f)
                        if (ratio < 4.5f) problems += "$name $k frame $fi: white text on $label is ${"%.2f".format(ratio)}:1"
                    }
            }
        }
        assertTrue(problems.distinct().take(15).joinToString("\n"), problems.isEmpty())
    }

    /** Not a check: writes the tiles' bounds (fractions of the root) for the stick's per-tile colour measurement. */
    @Test fun dumpTileBounds() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            val root = compose.onRoot().fetchSemanticsNode().size
            val out = StringBuilder()
            for ((label, r) in tileBounds()) out.append("$label\t${r.left / root.width}\t${r.top / root.height}\t${r.right / root.width}\t${r.bottom / root.height}\n")
            java.io.File("build/cc-tile-bounds.tsv").writeText(out.toString())
        }
    }
}
