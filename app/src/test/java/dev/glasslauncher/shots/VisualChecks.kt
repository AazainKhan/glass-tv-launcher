package dev.glasslauncher.shots

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.data.folderKey
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.CcMaterial
import dev.glasslauncher.home.CcSheet
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max

/**
 * Automated eyes for the bugs the user keeps catching by hand (run by `scripts/shots all`, so the
 * Stop and pre-commit hooks enforce them):
 * - ghost frames: an element that appears during a transition must not sit half-transparent for
 *   more than [GHOST_MAX_RUN] frames;
 * - Control Center: one material (every unlit tile shows the open's baked sheet at its own position), no
 *   colour change after landing, and the Settings tile is never caught half-grey (measured per pixel: a tile
 *   being uncovered by the growing bubble is part white, part dark, never grey).
 *
 * A check with a known open bug is marked with its board item via [expectFail]: it reports as
 * skipped while it still fails, and FAILS once it starts passing so the marker gets removed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class VisualChecks {

    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private class Frame(val bitmap: Bitmap)
    private data class Box(val name: String, val l: Int, val t: Int, val r: Int, val b: Int)

    // ── ghost frames ─────────────────────────────────────────────────────────────────────────────

    @Test fun appMenuHasNoGhostFrames() = noGhosts("open-app-menu", Button.Menu)
    @Test fun settingsPageHasNoGhostFrames() = noGhosts("settings-page-push", Button.Select, Button.Down, Button.Down, Button.Right, Button.Right, Button.Select)
    @Test fun controlCenterHasNoGhostFrames() = noGhosts("control-center-open", Button.Select, Button.Up, Button.Up, Button.Up, dimRef = ::unclaimedPatch)

    /**
     * Presses [setup], then [button], and checks every element that appeared for long half-transparent runs.
     * Measured per pixel ([ghostPixelFraction]): an element being uncovered by a growing clip has pixels that are
     * fully drawn or not there yet, never half-way, so only a real fade trips it. [dimRef] is a patch of the screen
     * that no element arrives in: while a wash dims the whole screen it says by how much, so what the element's
     * pixels are compared with is the dimmed scene, not the scene before the wash (a darkening backdrop is not an
     * element fading in).
     */
    private fun noGhosts(name: String, button: Button, vararg setup: Button, dimRef: ((Bitmap) -> Box)? = null) = onHome {
        compose.press(*setup)
        compose.settle()
        val before = capture()
        val beforeTags = tags().keys
        val frames = record(button, FRAMES, STEP_MS)
        compose.settle()
        val arrived = tags().filterKeys { it !in beforeTags }.values.filter { (it.r - it.l) >= 24 && (it.b - it.t) >= 16 }
        val last = frames.last()
        val ratios = frames.map { f -> dimRef?.let { r -> dimRatio(before, f, r(before)) } ?: floatArrayOf(1f, 1f, 1f) }
        val problems = arrived.mapNotNull { box ->
            var run = 0; var worst = 0
            // Without a dimming wash the element's mean colour tells (the long-standing measure, unchanged for the
            // menus); with one, per pixel against the dimmed scene.
            val bg = mean(before, box); val fin = mean(last, box)
            val d = FloatArray(3) { fin[it] - bg[it] }
            val dd = d.sumOf { (it * it).toDouble() }.toFloat()
            if (dimRef == null && dd < 30f * 30f) return@mapNotNull null // can't tell it from what was behind
            for ((i, f) in frames.withIndex()) {
                val ghost = if (dimRef != null) ghostPixelFraction(before, f, last, box, ratios[i]) > GHOST_PIXELS
                else (0..2).sumOf { ((mean(f, box)[it] - bg[it]) * d[it]).toDouble() }.toFloat() / dd in 0.2f..0.8f
                run = if (ghost) run + 1 else 0
                worst = max(worst, run)
            }
            if (worst > GHOST_MAX_RUN) "${box.name}: half-transparent for $worst frames (${worst * STEP_MS} ms)" else null
        }
        if (problems.isNotEmpty()) fail("$name ghost frames:\n" + problems.joinToString("\n"))
    }

    // ── continuity: things that move must move continuously ──────────────────────────────────

    // Every focus move and transition on Home, plus move mode (the user's report: P20).
    @Test fun dockRightIsContinuous() = continuous("dock-right", Button.Right)
    @Test fun dockToGridIsContinuous() = continuous("dock-to-grid", Button.Down)
    @Test fun gridToDockIsContinuous() = continuous("grid-to-dock", Button.Up, Button.Down)
    @Test fun dockToFeaturedIsContinuous() = continuous("dock-to-featured", Button.Up)
    @Test fun featuredToDockIsContinuous() = continuous("featured-to-dock", Button.Down, Button.Up)
    @Test fun appMenuIsContinuous() = continuous("open-app-menu", Button.Menu)
    @Test fun settingsPageIsContinuous() = continuous("settings-page-push", Button.Select, Button.Down, Button.Down, Button.Right, Button.Right, Button.Select)
    @Test fun controlCenterIsContinuous() = continuous("control-center-open", Button.Select, Button.Up, Button.Up, Button.Up)
    // Move mode: Menu on the first dock app, "Move" (second row), then move it right / down.
    @Test fun moveModeRightIsContinuous() = continuous("move-right", Button.Right, Button.Menu, Button.Down, Button.Select)
    @Test fun moveModeDownIsContinuous() = continuous("move-down", Button.Down, Button.Menu, Button.Down, Button.Select)

    // Rearrange Folder (P35): Menu on the first app in an open folder, Rearrange Folder, then move it right.
    @Test fun folderRearrangeIsContinuous() = onHome(config = { c ->
        c.copy(folders = listOf(Folder("media", "Media", FOLDER_APPS)), order = listOf(folderKey("media")))
    }) {
        compose.focusTag(folderKey("media"))
        compose.settle()
        compose.press(Button.Select)
        compose.waitForTag("folder-title")
        compose.press(Button.Menu, Button.Select)
        val moved = "app:org.jellyfin.androidtv"
        val other = "app:org.videolan.vlc"
        val before = tagged()
        trackTransition("folder-rearrange", Button.Right)
        // And it really moved: the first app now sits where the second was, and the second where the first was.
        val after = tagged()
        check(after.getValue(moved).l > before.getValue(moved).l && after.getValue(other).l < before.getValue(other).l) {
            "Rearrange Folder + Right didn't swap the first two apps: $moved ${before[moved]} -> ${after[moved]}, $other ${before[other]} -> ${after[other]}"
        }
    }

    private val FOLDER_APPS = listOf(
        "org.jellyfin.androidtv", "org.videolan.vlc", "com.hbo.hbonow", "tv.twitch.android.viewer", "com.esaba.downloader", "com.estrongs.android.pop",
    )

    /**
     * Records [button]'s transition with every watched element's bounds per frame, and fails when an
     * element jumps: one frame's move is large (> [JUMP_MIN_PX]) and far bigger than its typical
     * per-frame motion ([JUMP_RATIO]x the median of its moving frames), or it teleports (one big
     * move with no motion around it). Bounds are layout positions, so graphicsLayer-only motion isn't
     * seen; confirm flags against `scripts/shots strips`.
     */
    private fun continuous(name: String, button: Button, vararg setup: Button) = onHome {
        compose.press(*setup)
        trackTransition(name, button)
    }

    private fun trackTransition(name: String, button: Button) {
        compose.settle()
        val track = ArrayList<Map<String, Box>>()
        compose.mainClock.autoAdvance = false
        try {
            track += tagged()
            compose.onRoot().performKeyInput { pressKey(button.key) }
            repeat(FRAMES) {
                if (it == 0) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(STEP_MS)
                track += tagged()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val problems = mutableListOf<String>()
        val names = track.flatMap { it.keys }.toSet()
        for (n in names) {
            val boxes = track.map { it[n] }
            val steps = boxes.zipWithNext().mapIndexedNotNull { i, (a, b) ->
                if (a == null || b == null) null else i to max(
                    max(abs(((a.l + a.r) - (b.l + b.r)) / 2), abs(((a.t + a.b) - (b.t + b.b)) / 2)),
                    max(abs((a.r - a.l) - (b.r - b.l)), abs((a.b - a.t) - (b.b - b.t))),
                )
            }
            // A jump is a spike: one frame's move far bigger than BOTH neighbours (an ease-out's fast first
            // frame isn't, since the next frame is still large). A move with nothing around it is a teleport.
            val byFrame = steps.toMap()
            for ((i, d) in steps) {
                if (d <= JUMP_MIN_PX) continue
                val around = max(byFrame[i - 1] ?: 0, byFrame[i + 1] ?: 0)
                if (d > JUMP_RATIO * max(around, 1)) problems += "$n jumps ${d}px in one frame at ${i * STEP_MS} ms (neighbouring frames move ${around}px)"
            }
        }
        if (problems.isNotEmpty()) fail("$name discontinuities:\n" + problems.distinct().take(15).joinToString("\n"))
    }

    // ── Control Center material (P10) ───────────────────────────────────────────────────────────

    @Test fun controlCenterIsOneMaterial() = controlCenter { frames, tiles, _, _ ->
        val landed = frames.takeLast(LANDED_FRAMES)
        val problems = mutableListOf<String>()
        // (a) No tile changes colour once Control Center has landed.
        for (t in tiles) {
            val means = landed.map { mean(it.bitmap, band(t)) }
            val drift = means.maxOf { m -> means.maxOf { n -> (0..2).maxOf { abs(m[it] - n[it]) } } }
            if (drift > LANDED_DRIFT) problems += "${t.name} changes ${"%.1f".format(drift)} levels after landing"
        }
        // (b) Each unlit glass tile (page discs included) shows the open's baked sheet at its own position: that is
        // what "one material" means. The sheet is the scene behind the panel, so tiles are NOT the same colour as
        // each other (a dark navy corner up top, a warm one lower down); only their sheet is shared.
        val sheet = CcMaterial.last ?: throw AssertionError("Control Center made no sheet")
        val rootW = landed.last().bitmap.width
        for (t in tiles) {
            val got = mean(landed.last().bitmap, band(t))
            if (luminance(got) >= LIT_MIN) continue // lit (on / focused): a white or solid fill by design, not glass
            val want = sheetMean(sheet, t.l + (t.r - t.l) * 0.35f, t.t + (t.b - t.t) * BAND0, t.l + (t.r - t.l) * 0.65f, t.t + (t.b - t.t) * BAND1)
            val off = (0..2).maxOf { abs(got[it] - want[it]) }
            if (off > SHEET_DELTA) problems += "${t.name} is ${"%.0f".format(off)} levels off the sheet at its position (got ${got.joinToString { "%.0f".format(it) }}, sheet ${want.joinToString { "%.0f".format(it) }})"
        }
        if (problems.isNotEmpty()) fail("Control Center material:\n" + problems.distinct().take(12).joinToString("\n"))
    }

    @Test fun controlCenterSettingsNeverGrey() = controlCenter { frames, _, settings, before ->
        settings ?: throw AssertionError("no Settings tile found in Control Center")
        val sheet = CcMaterial.last ?: throw AssertionError("Control Center made no sheet")
        val box = inner(settings)
        val grey = frames.withIndex().mapNotNull { (i, f) ->
            val ratio = dimRatio(before, f.bitmap, unclaimedPatch(before))
            val share = greyPixelFraction(f.bitmap, box) { x, y, c ->
                isNear(c, 0xFFFFFFFF.toInt()) || // the lit fill
                    isNear(c, scaled(before.getPixel(x, y), ratio)) || // the scene behind, dimmed so far (not uncovered yet)
                    isNear(c, sheetAt(sheet, x, y)) // the bubble's or the tile's own glass
            }
            if (share > GREY_SHARE) i to share else null
        }
        if (grey.isNotEmpty()) fail("Settings tile is part grey at ${grey.joinToString { "${it.first * STEP_MS} ms (${"%.0f".format(it.second * 100)}% of its pixels)" }}")
    }

    /** Opens Control Center from the dock and hands over its frames, its tiles and the Settings tile. */
    private fun controlCenter(check: (List<Frame>, List<Box>, Box?, Bitmap) -> Unit) = onHome {
        compose.press(Button.Up, Button.Up, Button.Up)
        compose.settle()
        val before = capture()
        val frames = record(Button.Select, FRAMES, STEP_MS).map { Frame(it) }
        compose.settle()
        val panel = compose.onAllNodes(hasTestTag("control-center"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: throw AssertionError("Control Center didn't open")
        val tiles = descendants(panel).filter { it.config.contains(SemanticsProperties.Focused) }.map { it.toBox() }
        val settings = tiles.firstOrNull { it.name.contains("Settings") && !it.name.contains("Launcher") }
        check(frames, tiles, settings, before)
    }

    // ── per-pixel measurements ───────────────────────────────────────────────────────────────────

    /** A patch of Home's top-left that no Control Center element arrives in: only the dim wash changes it. */
    private fun unclaimedPatch(b: Bitmap) = Box("dim reference", b.width * 3 / 100, b.height * 4 / 100, b.width * 40 / 100, b.height * 28 / 100)

    /** How much of each channel [frame] keeps of [before] over [ref] (1 = untouched, 0.58 = Control Center's dim at rest). */
    private fun dimRatio(before: Bitmap, frame: Bitmap, ref: Box): FloatArray {
        val a = mean(before, ref); val f = mean(frame, ref)
        return FloatArray(3) { if (a[it] < 1f) 1f else (f[it] / a[it]).coerceAtMost(1f) }
    }

    private fun scaled(c: Int, ratio: FloatArray): Int =
        (0xFF shl 24) or ((((c shr 16) and 0xff) * ratio[0]).toInt() shl 16) or ((((c shr 8) and 0xff) * ratio[1]).toInt() shl 8) or ((c and 0xff) * ratio[2]).toInt()

    private fun isNear(a: Int, b: Int, tol: Int = NEAR): Boolean =
        abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) <= tol && abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) <= tol && abs((a and 0xff) - (b and 0xff)) <= tol

    private fun sheetAt(sheet: CcSheet, x: Int, y: Int): Int {
        val m = sheetMean(sheet, x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat())
        return (0xFF shl 24) or (m[0].toInt() shl 16) or (m[1].toInt() shl 8) or m[2].toInt()
    }

    /**
     * The share of [box]'s pixels that [accepts] rejects: a pixel that is fully drawn, or not there yet, is accepted;
     * a pixel caught half-way between (a half-transparent fill) is not. Per pixel, so an element being uncovered by a
     * growing clip (some pixels drawn, some not) is not mistaken for one that is half-transparent.
     */
    private fun greyPixelFraction(f: Bitmap, box: Box, accepts: (Int, Int, Int) -> Boolean): Float {
        var bad = 0; var n = 0
        for (y in box.t.coerceIn(0, f.height - 1) until box.b.coerceIn(0, f.height) step 2) for (x in box.l.coerceIn(0, f.width - 1) until box.r.coerceIn(0, f.width) step 2) {
            if (!accepts(x, y, f.getPixel(x, y))) bad++
            n++
        }
        return if (n == 0) 0f else bad / n.toFloat()
    }

    /**
     * The share of [box]'s distinguishable pixels (those whose final colour differs from the scene behind, [before]
     * dimmed by [ratio], by 30+ levels) that sit between the two (20-80% of the way from the scene to the final colour).
     */
    private fun ghostPixelFraction(before: Bitmap, f: Bitmap, last: Bitmap, box: Box, ratio: FloatArray): Float {
        var ghost = 0; var n = 0
        for (y in box.t.coerceIn(0, f.height - 1) until box.b.coerceIn(0, f.height) step 2) for (x in box.l.coerceIn(0, f.width - 1) until box.r.coerceIn(0, f.width) step 2) {
            val bg = scaled(before.getPixel(x, y), ratio); val fin = last.getPixel(x, y); val c = f.getPixel(x, y)
            val d = IntArray(3) { ((fin shr (16 - 8 * it)) and 0xff) - ((bg shr (16 - 8 * it)) and 0xff) }
            val dd = d.sumOf { it * it }
            if (dd < 30 * 30) continue
            val a = (0..2).sumOf { (((c shr (16 - 8 * it)) and 0xff) - ((bg shr (16 - 8 * it)) and 0xff)) * d[it] }.toFloat() / dd
            n++
            if (a in 0.2f..0.8f) ghost++
        }
        return if (n == 0) 0f else ghost / n.toFloat()
    }

    private fun solid(w: Int, h: Int, argb: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(argb) }
    private fun halves(w: Int, h: Int, left: Int, right: Int) = solid(w, h, right).also { b -> for (y in 0 until h) for (x in 0 until w / 2) b.setPixel(x, y, left) }

    /** The measurement itself, on synthetic frames (P17): a half-white fill is caught, a half-uncovered white one is not. */
    @Test fun greyMeasurementCatchesAHalfTransparentFill() {
        val dark = 0xFF202020.toInt(); val white = 0xFFFFFFFF.toInt()
        val box = Box("tile", 0, 0, 100, 100)
        val ok = { _: Int, _: Int, c: Int -> isNear(c, white) || isNear(c, dark) }
        val half = solid(100, 100, (0xFF shl 24) or (((0x20 + 0xFF) / 2) * 0x010101)) // 50% white over the dark
        assertTrue("a 50% white fill must be caught (${greyPixelFraction(half, box, ok)})", greyPixelFraction(half, box, ok) > GREY_SHARE)
        assertTrue("a half-uncovered white tile (white | dark) must not be", greyPixelFraction(halves(100, 100, white, dark), box, ok) <= GREY_SHARE)
        assertTrue("a white tile must not be", greyPixelFraction(solid(100, 100, white), box, ok) <= GREY_SHARE)
    }

    /** The ghost measurement on synthetic frames (P17): a fading element is caught; a clip, and a darkening scene, are not. */
    @Test fun ghostMeasurementTellsAFadeFromAClipAndFromTheDim() {
        val box = Box("icon", 0, 0, 100, 100)
        val noDim = floatArrayOf(1f, 1f, 1f)
        val before = solid(100, 100, 0xFF202020.toInt()); val last = solid(100, 100, 0xFFFFFFFF.toInt())
        val fade = solid(100, 100, 0xFF909090.toInt())
        assertTrue("a 50% fade must be caught", ghostPixelFraction(before, fade, last, box, noDim) > GHOST_PIXELS)
        assertTrue("half-uncovered must not be", ghostPixelFraction(before, halves(100, 100, 0xFFFFFFFF.toInt(), 0xFF202020.toInt()), last, box, noDim) <= GHOST_PIXELS)
        // The Screen Saver case: the scene (86) dims to 70 on the way to a tile of glass that is 59 at rest.
        val scene = solid(100, 100, 0xFF565656.toInt()); val glass = solid(100, 100, 0xFF3B3B3B.toInt()); val dimming = solid(100, 100, 0xFF464646.toInt())
        val ratio = floatArrayOf(70 / 86f, 70 / 86f, 70 / 86f)
        assertTrue("a scene dimming under a tile that isn't there yet is not a fade", ghostPixelFraction(scene, dimming, glass, box, ratio) <= GHOST_PIXELS)
        assertTrue("(without the dim the same frames read as a fade, which was the P17 false positive)", ghostPixelFraction(scene, dimming, glass, box, noDim) > GHOST_PIXELS)
    }


    // ── harness ──────────────────────────────────────────────────────────────────────────────────

    private fun onHome(config: (LauncherConfig) -> LauncherConfig = { it }, block: () -> Unit) {
        TvHarness.setUp(config = config)
        ActivityScenario.launch(MainActivity::class.java).use { compose.waitForHome(); block() }
    }

    private fun expectFail(item: String, block: () -> Unit) {
        val failure: Throwable = runCatching(block).exceptionOrNull()
            ?: throw AssertionError("This check now passes: remove expectFail(\"$item\") and close $item on the board.")
        assumeTrue("expected failure until $item is fixed: ${failure.message?.lineSequence()?.take(3)?.joinToString(" / ")}", false)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun record(button: Button, frames: Int, stepMs: Long): List<Bitmap> {
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performKeyInput { pressKey(button.key) }
            return List(frames) {
                if (it == 0) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(stepMs)
                capture()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    /** Every element worth watching: tagged nodes plus focusable or text nodes (tiles often have no tag). */
    private fun tags(): Map<String, Box> {
        val watch = SemanticsMatcher("tagged, focusable or text") {
            it.config.contains(SemanticsProperties.TestTag) || it.config.contains(SemanticsProperties.Focused) ||
                it.config.contains(SemanticsProperties.Text)
        }
        val boxes = compose.onAllNodes(watch, useUnmergedTree = true).fetchSemanticsNodes().map { it.toBox() }
        // Names can repeat (two "On" captions); keep each occurrence distinct but stable between captures.
        return boxes.groupBy { it.name }.flatMap { (name, list) -> list.mapIndexed { i, b -> (if (i == 0) name else "$name#$i") to b } }.toMap()
    }

    /** Only test-tagged nodes: their tags are stable identities across frames (text labels aren't). */
    private fun tagged(): Map<String, Box> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes().groupBy { it.config[SemanticsProperties.TestTag] }
            .filterValues { it.size == 1 }.mapValues { it.value.single().toBox() }

    private fun descendants(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + descendants(it) }

    private fun SemanticsNode.toBox(): Box {
        val name = config.getOrNull(SemanticsProperties.TestTag)
            ?: (listOf(this) + descendants(this)).firstNotNullOfOrNull { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            } ?: "node#$id"
        val r = boundsInRoot
        return Box(name, r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    /** A band near the top of a tile, clear of its icon, its label and the bevel: the material itself (as the Control Center motion tests' band). */
    private fun band(b: Box): Box {
        val w = b.r - b.l; val h = b.b - b.t
        return Box(b.name, b.l + w * 35 / 100, b.t + h * 15 / 100, b.r - w * 35 / 100, b.t + h * 22 / 100)
    }

    /** The middle of a tile, inset from its rounded corners. */
    private fun inner(b: Box): Box {
        val w = b.r - b.l; val h = b.b - b.t
        return Box(b.name, b.l + w / 5, b.t + h / 5, b.r - w / 5, b.b - h / 5)
    }

    private fun mean(bitmap: Bitmap, box: Box): FloatArray {
        val l = box.l.coerceIn(0, bitmap.width - 1); val r = box.r.coerceIn(l + 1, bitmap.width)
        val t = box.t.coerceIn(0, bitmap.height - 1); val bt = box.b.coerceIn(t + 1, bitmap.height)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (y in t until bt step 2) for (x in l until r step 2) {
            val c = bitmap.getPixel(x, y)
            rs += (c shr 16) and 0xff; gs += (c shr 8) and 0xff; bs += c and 0xff; n++
        }
        return floatArrayOf(rs / n.toFloat(), gs / n.toFloat(), bs / n.toFloat())
    }

    private fun luminance(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private companion object {
        const val FRAMES = 30
        const val STEP_MS = 28L
        const val GHOST_MAX_RUN = 2
        const val LANDED_FRAMES = 8
        const val LANDED_DRIFT = 3f
        const val SHEET_DELTA = 6f
        const val NEAR = 20
        const val GHOST_PIXELS = 0.10f
        const val GREY_SHARE = 0.10f
        const val LIT_MIN = 200f
        const val JUMP_MIN_PX = 40
        const val JUMP_RATIO = 4
    }
}
