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
    for (tag in volatileTags) {
        for (node in onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()) {
            val r = node.boundsInRoot
            // Fixed-width box anchored at the node's right edge (the pill grows leftwards with the time
            // text), padded for the blurred text shadow, so the mask itself doesn't move between runs.
            canvas.drawRect(r.right - 320f, r.top - 24, r.right + 24, r.bottom + 24, paint)
        }
    }
    return bitmap
}
