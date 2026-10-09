package dev.glasslauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.home.CcMorph
import dev.glasslauncher.home.drawCcBubble
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Control Center bubble at 10 / 30 / 60 / 100 % of the open (and on the way back): drawn the way the
 * overlay draws it, black on the rule's own background, then the pixels at the bounding box's corners must be background (a
 * rounded outline, never a box) and the middle of every side must be the bubble.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcBubbleFrameTest {
    @get:Rule val compose = createComposeRule()

    private val pill = Rect(700f, 20f, 920f, 84f)
    private val panel = Rect(500f, 20f, 920f, 620f)
    private var progress by mutableFloatStateOf(0f)
    private var closing = false

    private fun render(t: Float, closing: Boolean): android.graphics.Bitmap {
        progress = t; this.closing = closing
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    private fun check(t: Float, closing: Boolean) {
        val bmp = render(t, closing)
        val b = CcMorph.bubble(t, closing)
        val r = CcMorph.rect(b, pill, panel, CcMorph.squeeze(b, closing))
        val tag = "t=$t closing=$closing rect=$r"
        fun px(x: Float, y: Float) = bmp.getPixel(x.toInt().coerceIn(0, bmp.width - 1), y.toInt().coerceIn(0, bmp.height - 1)) and 0xFFFFFF
        val inset = 2f
        val background = px(1f, 1f)
        for ((x, y) in listOf(r.left + inset to r.top + inset, r.right - inset to r.top + inset, r.left + inset to r.bottom - inset, r.right - inset to r.bottom - inset))
            assertEquals("a rectangular corner at ($x, $y), $tag", background, px(x, y))
        for ((x, y) in listOf(r.center.x to r.top + inset, r.center.x to r.bottom - inset, r.left + inset to r.center.y, r.right - inset to r.center.y, r.center.x to r.center.y))
            assertEquals("a hole in the bubble at ($x, $y), $tag", 0, px(x, y))
    }

    @Test fun roundAtTenThirtySixtyAndAHundredPercentOfTheOpen() {
        compose.setContent {
            val d = LocalDensity.current.density
            Canvas(Modifier.size((1000 / d).dp, (700 / d).dp)) {
                val b = CcMorph.bubble(progress, closing)
                drawCcBubble(b, CcMorph.squeeze(b, closing), pill, panel, Color.Black, 1f, 26f)
            }
        }
        for (t in listOf(0.1f, 0.3f, 0.6f, 1f, 1.03f)) check(t, closing = false)
    }

    @Test fun roundOnTheWayBackToo() {
        compose.setContent {
            val d = LocalDensity.current.density
            Canvas(Modifier.size((1000 / d).dp, (700 / d).dp)) {
                val b = CcMorph.bubble(progress, closing)
                drawCcBubble(b, CcMorph.squeeze(b, closing), pill, panel, Color.Black, 1f, 26f)
            }
        }
        for (t in listOf(0.7f, 0.4f, 0.15f)) check(t, closing = true)
    }
}
