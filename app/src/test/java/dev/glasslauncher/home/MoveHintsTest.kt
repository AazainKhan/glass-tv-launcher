package dev.glasslauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The move-mode hint bar: separate key-chip hints, legible over bright tiles, and it fits at the largest text size. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class MoveHintsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachPlaceHasItsOwnShortHints() {
        assertEquals(listOf("Rearrange", "Move to Apps", "Done"), moveHints(inDock = true, inFolder = false).map { it.label })
        assertEquals(listOf("Move", "Add to Top Row", "Done"), moveHints(inDock = false, inFolder = false).map { it.label })
        assertEquals(listOf("Rearrange", "Done"), moveHints(inDock = false, inFolder = true).map { it.label })
        // Every set ends with Select = Done, and no label is long.
        listOf(true to false, false to false, false to true).forEach { (dock, folder) ->
            val hints = moveHints(dock, folder)
            assertEquals(HintKey.Select, hints.last().key)
            assertTrue(hints.all { it.label.length <= 16 })
        }
    }

    private fun show(fontScale: Float, background: Color) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.BottomCenter) {
                    MoveBanner("app:com.example", HomeLayout(), inFolder = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun itFitsInsideTheSafeWidthAtTheLargestTextSize() {
        show(fontScale = 1.4f, background = Color.Black)
        val bounds = compose.onNodeWithTag("move-banner").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("banner ${bounds.left}..${bounds.right} px is not inside the 48 dp safe margins", bounds.left >= 48f * density && bounds.right <= 960f * density - 48f * density)
    }

    @Test fun everyHintIsDescribedInWordsForScreenReaders() {
        show(fontScale = 1f, background = Color.Black)
        val described = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("hint") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("move-hint:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, described.size)
        assertTrue(described.all { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.contains(":") } == true
        })
    }

    @Test fun inTheLightThemeTheBarStaysLightOverABlackPage() {
        compose.setContent {
            CompositionLocalProvider(dev.glasslauncher.ui.LocalPalette provides dev.glasslauncher.ui.Palette(light = true)) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
                    MoveBanner("app:com.example", HomeLayout(), inFolder = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val bmp = compose.onNodeWithTag("move-banner").captureToImage().asAndroidBitmap()
        val lums = ArrayList<Double>()
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(p) < 255) continue
            lums += 0.2126 * android.graphics.Color.red(p) + 0.7152 * android.graphics.Color.green(p) + 0.0722 * android.graphics.Color.blue(p)
        }
        val median = lums.sorted()[lums.size / 2]
        assertTrue("a white scrim over black should read well above mid-grey, got $median", median > 120)
    }

    @Test fun theBarStaysDenseOverAWhitePage() {
        show(fontScale = 1f, background = Color.White)
        val bmp = compose.onNodeWithTag("move-banner").captureToImage().asAndroidBitmap()
        // The pill's own fill is most of its pixels (chips and text are small): over a white page the median pixel
        // must still be clearly dark, not a see-through film showing the page.
        val lums = ArrayList<Double>()
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(p) < 255) continue
            lums += 0.2126 * android.graphics.Color.red(p) + 0.7152 * android.graphics.Color.green(p) + 0.0722 * android.graphics.Color.blue(p)
        }
        // Corners outside the pill are the page's white: leave them to the median, which sits in the pill.
        val luminance = lums.sorted()[lums.size / 2]
        assertTrue("the bar's fill over white is $luminance of 255", luminance < 150)
    }
}
