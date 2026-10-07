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
private val volatileTags = listOf("clock", "status-pill") // the pill resizes with the time text

/** The screen as a bitmap with time-dependent content painted over, so baselines don't drift by the minute. */
fun ComposeTestRule.stableImage(): Bitmap {
    val bitmap = onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = android.graphics.Color.MAGENTA }
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
            // A box at least 320 px wide from whichever edge is anchored (the pill grows leftwards, the
            // Control Center clock rightwards), padded for text shadow, so the mask doesn't move between runs.
            val grid = 64f  // snap edges so a few px of text-width change never moves the mask itself
            val l = kotlin.math.floor((minOf(r.left, r.right - 320f) - 24) / grid) * grid
            val rt = kotlin.math.ceil((maxOf(r.right, r.left + 320f) + 24) / grid) * grid
            canvas.drawRect(l, kotlin.math.floor((r.top - 24) / grid) * grid, rt, kotlin.math.ceil((r.bottom + 24) / grid) * grid, paint)
        }
    }
    return bitmap
}
