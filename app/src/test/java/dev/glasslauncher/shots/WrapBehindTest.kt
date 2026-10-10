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
 * P73: after Hide, the tile that wraps up a row (the second grid row's first tile, Twitch) glides across the
 * others behind them, never in front of their faces. Judged by pixels: where it overlaps another tile, the
 * picture there must be that tile's, not the wrapping one's.
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
            var overlaps = 0
            var inFront = 0
            val worst = StringBuilder()
            val path = ArrayList<androidx.compose.ui.geometry.Rect>()
            val ghostSamples = ArrayList<Pair<Float, Int>>() // (how far along the path, distance of the pixel from the tile's colour)
            repeat(100) { f ->
                Thread.sleep(8)
                compose.mainClock.advanceTimeBy(16)
                val now = tiles()
                val g = now[glider] ?: return@repeat
                val shot = compose.onRoot().captureToImage().asAndroidBitmap()
                path += g
                // Clear of every other tile: the pixel at the tile's own top-left corner is the tile or what is behind it.
                val px = (g.left + g.width * 0.12f).toInt(); val py = (g.top + g.height * 0.12f).toInt()
                val clear = now.none { (t, r) -> t != glider && r.contains(androidx.compose.ui.geometry.Offset(px.toFloat(), py.toFloat())) }
                if (clear) colour[glider]?.let { c -> ghostSamples += (g.center.x to dist(shot.getPixel(px.coerceIn(0, shot.width - 1), py.coerceIn(0, shot.height - 1)), c)) }
                for ((tag, r) in now) {
                    if (tag == glider) continue
                    val o = r.intersect(g)
                    if (o.width < 8f || o.height < 8f) continue
                    val mine = colour[glider] ?: continue
                    val theirs = colour[tag] ?: continue
                    if (dist(mine, theirs) < 90) continue // too alike to tell apart
                    // Nine points over the overlap: which tile's colour is drawn there?
                    var gl = 0; var other = 0
                    for (i in 1..3) for (j in 1..3) {
                        val px = shot.getPixel((o.left + o.width * i / 4f).toInt().coerceIn(0, shot.width - 1), (o.top + o.height * j / 4f).toInt().coerceIn(0, shot.height - 1))
                        if (dist(px, mine) + 20 < dist(px, theirs)) gl++ else if (dist(px, theirs) + 20 < dist(px, mine)) other++
                    }
                    if (gl + other == 0) continue
                    overlaps++
                    if (gl > other) { inFront++; if (worst.length < 400) worst.append("frame $f: $glider over $tag ($gl vs $other points); ") }
                }
            }
            compose.mainClock.autoAdvance = true
            // Mid-flight over clear space the tile is a ghost: its corner isn't its colour (at the ends it is).
            val x0 = path.first().center.x; val x1 = path.last().center.x
            val mid = ghostSamples.filter { (x, _) -> abs((x - x0) / (x1 - x0).let { if (it == 0f) 1f else it } - 0.5f) < 0.2f }
            check(mid.isNotEmpty()) { "no frame of the flight was over clear space (path ${path.size} frames, $x0 -> $x1)" }
            check(mid.any { it.second > 60 }) { "the wrapping tile stays solid mid-flight (distances ${mid.map { it.second }})" }
            check(overlaps > 0) { "the wrapping tile never overlapped another tile, so this proves nothing" }
            check(inFront == 0) { "the wrapping tile was drawn in front of others in $inFront of $overlaps overlaps: $worst" }
        }
    }
}
