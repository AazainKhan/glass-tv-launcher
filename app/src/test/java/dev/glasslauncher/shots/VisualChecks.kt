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
import dev.glasslauncher.MainActivity
import org.junit.Assume.assumeTrue
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
 * - Control Center: one material (tiles agree), no colour change after landing, and the Settings
 *   tile is never caught passing through grey.
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
    @Test fun controlCenterHasNoGhostFrames() = expectFail("P10") { noGhosts("control-center-open", Button.Select, Button.Up, Button.Up, Button.Up) }

    /** Presses [setup], then [button], and checks every element that appeared for long half-transparent runs. */
    private fun noGhosts(name: String, button: Button, vararg setup: Button) = onHome {
        compose.press(*setup)
        compose.settle()
        val before = capture()
        val beforeTags = tags().keys
        val frames = record(button, FRAMES, STEP_MS)
        compose.settle()
        val arrived = tags().filterKeys { it !in beforeTags }.values.filter { (it.r - it.l) >= 24 && (it.b - it.t) >= 16 }
        val last = frames.last()
        val problems = arrived.mapNotNull { box ->
            val bg = mean(before, box); val fin = mean(last, box)
            val d = FloatArray(3) { fin[it] - bg[it] }
            val dd = d.sumOf { (it * it).toDouble() }.toFloat()
            if (dd < 30f * 30f) return@mapNotNull null // can't tell it from what was behind
            var run = 0; var worst = 0
            for (f in frames) {
                val m = mean(f, box)
                val a = (0..2).sumOf { ((m[it] - bg[it]) * d[it]).toDouble() }.toFloat() / dd
                run = if (a in 0.2f..0.8f) run + 1 else 0
                worst = max(worst, run)
            }
            if (worst > GHOST_MAX_RUN) "${box.name}: half-transparent for $worst frames (${worst * STEP_MS} ms)" else null
        }
        if (problems.isNotEmpty()) fail("$name ghost frames:\n" + problems.joinToString("\n"))
    }

    // ── Control Center material (P10) ───────────────────────────────────────────────────────────

    @Test fun controlCenterIsOneMaterial() = expectFail("P10") { controlCenter { frames, tiles, settings ->
        val landed = frames.takeLast(LANDED_FRAMES)
        val problems = mutableListOf<String>()
        // (a) No tile changes colour once Control Center has landed.
        for (t in tiles) {
            val means = landed.map { mean(it.bitmap, band(t)) }
            val drift = means.maxOf { m -> means.maxOf { n -> (0..2).maxOf { abs(m[it] - n[it]) } } }
            if (drift > LANDED_DRIFT) problems += "${t.name} changes ${"%.1f".format(drift)} levels after landing"
        }
        // (b) The unfocused glass tiles agree with each other (one material).
        val glass = tiles.filter { it != settings }.map { it to mean(landed.last().bitmap, band(it)) }
        for ((a, ma) in glass) for ((b, mb) in glass) {
            val diff = (0..2).maxOf { abs(ma[it] - mb[it]) }
            if (a.name < b.name && diff > MATERIAL_DELTA) problems += "${a.name} vs ${b.name} differ by ${"%.0f".format(diff)} levels"
        }
        if (problems.isNotEmpty()) fail("Control Center material:\n" + problems.distinct().take(12).joinToString("\n"))
    } }

    @Test fun controlCenterSettingsNeverGrey() = expectFail("P10") { controlCenter { frames, _, settings ->
        settings ?: throw AssertionError("no Settings tile found in Control Center")
        val grey = frames.withIndex().filter { (_, f) -> luminance(mean(f.bitmap, inner(settings!!))) in GREY_MIN..GREY_MAX }
        if (grey.isNotEmpty()) fail("Settings tile is grey at ${grey.joinToString { "${it.index * STEP_MS} ms" }}")
    } }

    /** Opens Control Center from the dock and hands over its frames, its tiles and the Settings tile. */
    private fun controlCenter(check: (List<Frame>, List<Box>, Box?) -> Unit) = onHome {
        compose.press(Button.Up, Button.Up, Button.Up)
        compose.settle()
        val frames = record(Button.Select, FRAMES, STEP_MS).map { Frame(it) }
        compose.settle()
        val panel = compose.onAllNodes(hasTestTag("control-center"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: throw AssertionError("Control Center didn't open")
        val tiles = descendants(panel).filter { it.config.contains(SemanticsProperties.Focused) }.map { it.toBox() }
        val settings = tiles.firstOrNull { it.name.contains("Settings") && !it.name.contains("Launcher") }
        check(frames, tiles, settings)
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────────

    private fun onHome(block: () -> Unit) {
        TvHarness.setUp()
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

    /** A band near the top of a tile, clear of its icon and label: the material itself. */
    private fun band(b: Box): Box {
        val w = b.r - b.l; val h = b.b - b.t
        return Box(b.name, b.l + w * 3 / 10, b.t + h * 8 / 100, b.r - w * 3 / 10, b.t + h * 22 / 100)
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
        const val MATERIAL_DELTA = 12f
        const val GREY_MIN = 90f
        const val GREY_MAX = 205f
    }
}
