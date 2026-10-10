package dev.glasslauncher.shots

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.GlideTracker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * P73: after Hide, the tile that wraps up a row (the second grid row's first tile, Twitch) slides out past the
 * end of its old row and in from beyond the end of its new row (tvOS), never crossing another tile, and it fades
 * only past the row's edge, never inside the grid.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class WrapBehindTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private val glider = "app:tv.twitch.android.viewer"

    private fun tiles(): Map<String, androidx.compose.ui.geometry.Rect> =
        compose.onAllNodes(SemanticsMatcher("an app tile") { n ->
            n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("app:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot }

    private fun dist(a: Int, b: Int): Int =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) + abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

    @Test fun aWrappingTileGlidesBehindTheOthers() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.press(Button.Down) // the first grid row's first tile: hiding it makes the second row's first wrap up
            compose.settle()
            // Each tile's own colour, from its top-left corner (clear of its name) before anything moves.
            val before = tiles()
            val shot0 = compose.onRoot().captureToImage().asAndroidBitmap()
            fun corner(r: androidx.compose.ui.geometry.Rect) = shot0.getPixel((r.left + r.width * 0.12f).toInt(), (r.top + r.height * 0.12f).toInt())
            val colour = before.mapValues { corner(it.value) }
            check(glider in before) { "$glider isn't on screen" }
            val seen = GlideTracker.layoutsSeen
            compose.press(Button.Menu)
            compose.settle()
            compose.press(Button.Down, Button.Down, Button.Down)
            compose.settle()
            compose.mainClock.autoAdvance = false
            compose.onRoot().performKeyInput { pressKey(Button.Select.key) }
            run landing@{
                repeat(2000) {
                    Thread.sleep(10)
                    tiles()
                    compose.mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
                    if (GlideTracker.layoutsSeen > seen) return@landing
                }
            }
            val rootW = shot0.width.toFloat()
            val inset = before.values.minOf { it.left }
            var overlapFrames = 0
            var slidOut = false
            var solidInGrid = 0
            var faintInGrid = 0
            var goneRun = 0
            var longestGone = 0
            val worst = StringBuilder()
            repeat(120) { f ->
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
                val now = tiles()
                val g = now[glider] ?: return@repeat
                // Past the grid's side margins (a row's own edge): it is leaving or arriving.
                val inGrid = g.left >= inset - 2f && g.right <= rootW - inset + 2f
                if (!inGrid) slidOut = true
                for ((tag, r) in now) {
                    if (tag == glider) continue
                    val o = r.intersect(g)
                    // (A tile all but faded away at the row's edge may be passed by the neighbour arriving there.)
                    if (o.width > 4f && o.height > 4f && GlideTracker.wrapAlpha > 0.25f) { overlapFrames++; if (worst.length < 300) worst.append("frame $f: over $tag ($o); ") }
                }
                // Out of sight: faded away, or off the screen (the grid reaches nearly to its sides, so a whole tile's
                // travel is off screen: the stick showed that as a disappearance and a pop).
                val vl = g.left.coerceAtLeast(0f); val vr = g.right.coerceAtMost(rootW)
                val visible = ((vr - vl) / g.width).coerceIn(0f, 1f) * GlideTracker.wrapAlpha
                if (visible >= 0.15f) goneRun = 0 else { goneRun++; longestGone = maxOf(longestGone, goneRun) }
                if (inGrid) {
                    val shot = compose.onRoot().captureToImage().asAndroidBitmap()
                    val px = (g.left + g.width * 0.12f).toInt().coerceIn(0, shot.width - 1)
                    val py = (g.top + g.height * 0.12f).toInt().coerceIn(0, shot.height - 1)
                    val covered = now.any { (t, r) -> t != glider && r.contains(androidx.compose.ui.geometry.Offset(px.toFloat(), py.toFloat())) }
                    val mine = colour[glider]
                    if (!covered && mine != null) { if (dist(shot.getPixel(px, py), mine) < 90) solidInGrid++ else faintInGrid++ }
                }
            }
            compose.mainClock.autoAdvance = true
            check(slidOut) { "the wrapping tile never slid out past its row's edge" }
            check(solidInGrid > 0) { "the wrapping tile was never seen solid inside the grid" }
            check(faintInGrid == 0) { "the wrapping tile was faint inside the grid in $faintInGrid frames (it fades only past the row's edge)" }
            check(longestGone <= 6) { "the wrapping tile was out of sight for $longestGone frames in a row (a disappear and pop); it should be mostly in view throughout, fading only briefly at the switch" }
            check(overlapFrames == 0) { "the wrapping tile's path crossed another tile in $overlapFrames frames: $worst" }
        }
    }
}
