package dev.glasslauncher.shots

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P64: a confirm card opened from Settings sits over the page, under its scrim: Settings doesn't vanish behind it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class ConfirmOverSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    /** Down the page until the focused row reads [text]. */
    private fun focusText(text: String) {
        repeat(40) {
            if (compose.focused()?.contains(text) == true) return
            compose.press(Button.Down)
            compose.settle()
        }
        error("never reached $text; focus is ${compose.focused()}")
    }

    /** The spread of brightness inside [text]'s node: a drawn row has text on a panel, a blurred snapshot is smooth. */
    private fun contrastAround(text: String): Int {
        val b = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        var lo = 255; var hi = 0
        for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(shot.height)) {
            for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(shot.width)) {
                val c = shot.getPixel(x, y)
                val l = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3
                lo = minOf(lo, l); hi = maxOf(hi, l)
            }
        }
        return hi - lo
    }

    @Test fun settingsStaysDrawnUnderAConfirmCard() {
        TvHarness.setUp()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.settle()
            compose.focusTag("settings-tile")
            compose.press(Button.Select)
            compose.settle()
            focusText("Backup & Restore")
            compose.press(Button.Select)
            compose.settle()
            focusText("Restore from Downloads")
            compose.press(Button.Select)
            compose.settle()
            check(compose.onAllNodes(hasText("Restore from Downloads?"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "the confirm card didn't open" }
            // "Save Backup" is a row of the page behind the card, clear of the card itself. Drawn (under the scrim),
            // its text still stands out of the panel (about 130 levels); with the page gone it's the smooth blurred
            // Home snapshot (about 65).
            val contrast = contrastAround("Save Backup")
            assertTrue("Settings vanished behind the card (contrast around its rows: $contrast)", contrast >= 100)
        }
    }
}
