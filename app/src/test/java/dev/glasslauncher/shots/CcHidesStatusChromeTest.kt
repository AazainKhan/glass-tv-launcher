package dev.glasslauncher.shots

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.home.ControlCenterWindow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P207: Control Center draws Home's capture under itself (P202), and that capture holds the status pill and search
 * circle as they were (the pill focused, light). Once Control Center is open they must be gone, as when Home's live
 * pill faded: its header is drawn where they were, not on top of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcHidesStatusChromeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @Test fun theStatusPillAndSearchAreGoneUnderAnOpenControlCenter() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome(); compose.settle()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center"); compose.settle()
            val pill = ControlCenterWindow.pillBounds ?: error("no pill bounds")
            val shot = compose.onRoot().captureToImage().asAndroidBitmap()
            var sum = 0.0; var n = 0
            for (y in pill.top.toInt() + 8 until pill.bottom.toInt() - 8 step 2) for (x in pill.left.toInt() + 8 until pill.right.toInt() - 8 step 2) {
                val c = shot.getPixel(x, y)
                sum += (0.2126 * (c shr 16 and 0xFF) + 0.7152 * (c shr 8 and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0; n++
            }
            val mean = sum / n
            // The focused pill is a light capsule (~0.6); the scene behind it here is the dark Home backdrop.
            assertTrue("the status pill still shows under Control Center (mean luma ${"%.2f".format(mean)} in its place)", mean < 0.3)
        }
    }
}
