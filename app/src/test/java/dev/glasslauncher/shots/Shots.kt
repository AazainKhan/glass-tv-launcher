package dev.glasslauncher.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import dev.glasslauncher.MainActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule

/** The Fire TV Stick 4K: 1920x1080 at density 320 = 960x540 dp, xhdpi, TV UI mode. */
const val TV = "w960dp-h540dp-land-television-xhdpi"

/** Baselines live next to the tests so they're reviewed in diffs like code. */
fun shot(name: String) = "src/test/screenshots/$name.png"

/** Waits until the app list has loaded and the first tile is on screen. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitForHome() {
    waitUntilAtLeastOneExists(hasTestTag("app:${TvHarness.apps.keys.first()}"), timeoutMillis = 10_000)
    // Tile art and Coil images decode off the main thread; let them land.
    repeat(5) { Thread.sleep(100); waitForIdle() }
}

/** Delivers a Home press the way the system does: a MAIN intent to the running singleTask activity. */
fun ActivityScenario<MainActivity>.pressHome() = onActivity { activity ->
    Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        .apply { isAccessible = true }
        .invoke(activity, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
}

/** Tags whose content changes with the time of day or the date; masked out of baselines. */
private val volatileTags = mapOf(  // the pill resizes with the time and date text
    "clock" to MaskAnchor.Right, "status-pill" to MaskAnchor.Right, "date" to MaskAnchor.Right,
    "cc-clock" to MaskAnchor.Left, "cc-date" to MaskAnchor.Left,
)

/** The screen as a bitmap with time-dependent content (clock, date, status pill) painted over, so baselines don't drift by the minute or the day. */
fun ComposeTestRule.stableImage(): Bitmap {
    val bitmap = onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = android.graphics.Color.rgb(40, 40, 46) }  // neutral, but clearly a mask
    // Any text that reads as a time of day ("5:42", "17:05", "5:42 a.m.") is a clock, tagged or not.
    val timeText = androidx.compose.ui.test.SemanticsMatcher("shows a time") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
            .any { Regex("""^\d{1,2}:\d{2}( ?[ap]\.?m\.?)?$""", RegexOption.IGNORE_CASE).matches(it.text.trim()) }
    }
    val tagged = volatileTags.flatMap { (tag, anchor) ->
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot to anchor }
    }
    // An untagged time text is only masked if no tagged node already contains it; its side is a guess by position.
    val untagged = onAllNodes(timeText, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        .filter { r -> tagged.none { (t, _) -> t.left <= r.left && t.top <= r.top && t.right >= r.right && t.bottom >= r.bottom } }
        .map { it to if (bitmap.width - it.right < RIGHT_ANCHOR_BAND) MaskAnchor.Right else MaskAnchor.Left }
    for ((r, anchor) in tagged + untagged) {
        val m = maskRect(r.left, r.top, r.right, r.bottom, anchor)
        canvas.drawRect(m.left, m.top, m.right, m.bottom, paint)
    }
    return bitmap
}

/** An untagged time text this close to the screen's right edge is taken to be right-anchored like the pill; otherwise left-anchored. */
internal const val RIGHT_ANCHOR_BAND = 256f
private const val MASK_MIN_WIDTH = 448f
private const val MASK_GRID = 64f  // snap edges so a pixel of layout drift never moves the mask itself
private const val MASK_PAD = 24f   // text shadow

internal enum class MaskAnchor { Left, Right }

internal data class MaskRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The mask for a time-dependent node with bounds [l],[t],[r],[b] whose text is anchored at [anchor] (the side that stays put as the text changes). It is at
 * least 448 px wide, grown from the edge the text is anchored to (the pill sits against the right edge and
 * grows leftwards; the Control Center clock and date start at a fixed left edge and grow rightwards), and on
 * the free side it extends to the node's own bounds, so it always covers the whole node. The anchored edge
 * never depends on the text, so "9:36 AM" and "10:05 AM" give the same anchored edge; a text wider than
 * 448 px can only grow the mask on the free side, where it is covered anyway.
 */
internal fun maskRect(l: Float, t: Float, r: Float, b: Float, anchor: MaskAnchor): MaskRect {
    val rightAnchored = anchor == MaskAnchor.Right
    val left = if (rightAnchored) minOf(l, r - MASK_MIN_WIDTH) else l
    val right = if (rightAnchored) r else maxOf(r, l + MASK_MIN_WIDTH)
    return MaskRect(
        kotlin.math.floor((left - MASK_PAD) / MASK_GRID) * MASK_GRID,
        kotlin.math.floor((t - MASK_PAD) / MASK_GRID) * MASK_GRID,
        kotlin.math.ceil((right + MASK_PAD) / MASK_GRID) * MASK_GRID,
        kotlin.math.ceil((b + MASK_PAD) / MASK_GRID) * MASK_GRID,
    )
}
