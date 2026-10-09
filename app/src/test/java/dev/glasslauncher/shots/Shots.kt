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

/** Tags whose content changes with wall-clock time; masked out of baselines. */
private val volatileTags = listOf("clock", "status-pill", "date", "cc-clock", "cc-date") // the pill resizes with the time and date text

/** The screen as a bitmap with time-dependent content painted over, so baselines don't drift by the minute. */
fun ComposeTestRule.stableImage(): Bitmap {
    val bitmap = onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = android.graphics.Color.rgb(40, 40, 46) }  // neutral, but clearly a mask
    // Any text that reads as a time of day ("5:42", "17:05", "5:42 a.m.") is a clock, tagged or not.
    val timeText = androidx.compose.ui.test.SemanticsMatcher("shows a time") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
            .any { Regex("""^\d{1,2}:\d{2}( ?[ap]\.?m\.?)?$""", RegexOption.IGNORE_CASE).matches(it.text.trim()) }
    }
    val volatileNodes = volatileTags.flatMap { onAllNodes(hasTestTag(it), useUnmergedTree = true).fetchSemanticsNodes() } +
        onAllNodes(timeText, useUnmergedTree = true).fetchSemanticsNodes()
    run {
        for (node in volatileNodes) {
            val r = node.boundsInRoot
            // A box 448 px wide, grown from the edge the text is anchored to: the pill sits against the screen's
            // right edge and grows leftwards, the Control Center clock and date start at a fixed left edge and
            // grow rightwards. Only the anchored edge (which doesn't depend on the text) positions the box, so
            // it is identical for "9:36 AM" and "10:05 AM", "Wed, Oct 7" and "Wed, Oct 14". The old box took
            // min/max over both anchors, so the text's free edge moved it across a 64 px snap boundary.
            val grid = 64f  // snap edges so a pixel of layout drift never moves the mask itself
            val pad = 24f   // text shadow
            val rightAnchored = bitmap.width - r.right < 256f
            val l = kotlin.math.floor(((if (rightAnchored) r.right - 448f else r.left) - pad) / grid) * grid
            val rt = kotlin.math.ceil(((if (rightAnchored) r.right else r.left + 448f) + pad) / grid) * grid
            canvas.drawRect(l, kotlin.math.floor((r.top - pad) / grid) * grid, rt, kotlin.math.ceil((r.bottom + pad) / grid) * grid, paint)
        }
    }
    return bitmap
}
