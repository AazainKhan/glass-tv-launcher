package dev.glasslauncher.shots

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P93 #8: Reduce Transparency must reach Control Center. It reached Glass's own surfaces (the dock, the panels) but
 * Control Center draws from its own sheet, which never consulted the flag - so with the setting on the dock went solid
 * and Control Center stayed frosted, which looks broken next to it.
 *
 * The measure is a *diff* between the two renderings rather than a spread: the panel band contains tiles carrying their
 * own glyphs, so absolute spread hardly moves (measured 33.1 solid vs 33.6 frosted), while the honest question - "did
 * any of these pixels change at all?" - is unambiguous and is exactly zero on the broken code.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcReduceTransparencyTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    /** The Control Center panel's pixels, opened with the given setting. */
    private fun panel(reduceTransparency: Boolean): IntArray {
        TvHarness.setUp(config = { it.copy(reduceTransparency = reduceTransparency) })
        var out = IntArray(0)
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up, Button.Select)
            compose.waitForTag("control-center")
            compose.settle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val all = IntArray(bmp.width * bmp.height)
            bmp.getPixels(all, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            // The panel occupies the right of the 1920-wide TV layout, above the tray.
            val px = ArrayList<Int>()
            for (y in 120 until 620) for (x in 900 until 1900) px += all[y * bmp.width + x]
            out = px.toIntArray()
        }
        return out
    }

    private fun changedPct(a: IntArray, b: IntArray): Double {
        var changed = 0
        for (i in a.indices) {
            val d = Math.abs((a[i] and 0xFF) - (b[i] and 0xFF)) +
                Math.abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)) +
                Math.abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            if (d > 6) changed++
        }
        return changed * 100.0 / a.size
    }

    @Test fun reduceTransparencyRepaintsThePanel() {
        val frosted = panel(reduceTransparency = false)
        val solid = panel(reduceTransparency = true)
        assertTrue("both renders must be the same size", frosted.size == solid.size)
        val pct = changedPct(frosted, solid)
        assertTrue("Reduce Transparency must repaint the Control Center panel (${"%.1f".format(pct)}% of pixels)", pct > 20.0)
    }
}
