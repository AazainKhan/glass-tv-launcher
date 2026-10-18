package dev.glasslauncher.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.data.Folder
import dev.glasslauncher.data.folderKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * P192: opening and closing a folder is one continuous zoom between the folder tile and its panel. A flash is a frame whose whole-screen
 * brightness steps far from the one before (a one-frame blur or an un-drawn Home), so the mean luminance per frame must change smoothly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FolderZoomTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    private val apps = listOf("org.jellyfin.androidtv", "org.videolan.vlc", "com.hbo.hbonow", "tv.twitch.android.viewer", "com.esaba.downloader", "com.estrongs.android.pop")

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun mean(b: Bitmap): Float {
        var sum = 0.0; var n = 0
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) {
            val c = b.getPixel(x, y); sum += 0.299 * (c shr 16 and 0xff) + 0.587 * (c shr 8 and 0xff) + 0.114 * (c and 0xff); n++
        }
        return (sum / n).toFloat()
    }

    private fun record(frames: Int = 40, stepMs: Long = 28, act: () -> Unit): List<Float> {
        compose.mainClock.autoAdvance = false
        try {
            act()
            return List(frames) { if (it == 0) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(stepMs); mean(capture()) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    /** The biggest brightness change between two consecutive frames: a flash is a step, a zoom is a slope. */
    private fun biggestStep(m: List<Float>): Float = m.zipWithNext { a, b -> abs(b - a) }.max()

    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun onFolder(block: () -> Unit) {
        TvHarness.setUp(config = { it.copy(folders = listOf(Folder("media", "Media", apps)), order = listOf(folderKey("media"))) })
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            scenario = sc
            compose.waitForHome(); compose.settle()
            compose.press(Button.Down); compose.settle()
            block()
        }
    }

    @Test fun openingAFolderHasNoFlashFrame() = onFolder {
        val m = record { compose.onRoot().performKeyInput { pressKey(Button.Select.key) } }
        println("P192 open luma: " + m.joinToString(" ") { "%.0f".format(it) })
        assertTrue("open jumps ${biggestStep(m)} in one frame: ${m.joinToString(" ") { "%.0f".format(it) }}", biggestStep(m) <= MAX_STEP_LUMA)
    }

    @Test fun closingAFolderHasNoFlashFrame() = onFolder {
        compose.press(Button.Select); compose.settle()
        compose.mainClock.advanceTimeBy(1_000)
        val m = record { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
        println("P192 close luma: " + m.joinToString(" ") { "%.0f".format(it) })
        assertTrue("close jumps ${biggestStep(m)} in one frame: ${m.joinToString(" ") { "%.0f".format(it) }}", biggestStep(m) <= MAX_STEP_LUMA)
    }

    private companion object { const val MAX_STEP_LUMA = 10f }
}
