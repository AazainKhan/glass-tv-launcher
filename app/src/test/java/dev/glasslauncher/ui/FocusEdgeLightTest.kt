package dev.glasslauncher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tvOS 27 lights only the focused tile's edge: a thin bright line along the top (+25-30% luminance over the
 * face) and a fainter one along the bottom. Resting tiles show none. Rendered through the real [FocusTile].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class FocusEdgeLightTest {
    @get:Rule val compose = createComposeRule()

    /** One 150x90 dp tile on a solid face, focused or not, settled; the tile's own pixels. */
    private fun render(focused: Boolean, face: Color): Pair<Bitmap, Float> {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            val requester = remember { FocusRequester() }
            if (focused) LaunchedEffect(Unit) { requester.requestFocus() }
            // Scale 1 and no shadow: the captured bounds are exactly the tile.
            FocusTile(
                label = "tile",
                onClick = {},
                shape = RoundedCornerShape(15.dp),
                focusedScale = 1f,
                shadow = false,
                modifier = Modifier.size(150.dp, 90.dp).focusRequester(requester).testTag("tile"),
            ) { Box(Modifier.fillMaxSize().background(face)) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return compose.onNodeWithTag("tile").captureToImage().asAndroidBitmap() to density
    }

    /** Mean luminance (0..255) of rows [firstRow, firstRow + rows), over the middle 60% so corners don't count. */
    private fun Bitmap.meanLuma(firstRow: Int, rows: Int): Float {
        var sum = 0.0
        var n = 0
        for (y in firstRow until firstRow + rows) for (x in (width * 0.2f).toInt() until (width * 0.8f).toInt()) {
            val p = getPixel(x, y)
            sum += 0.2126 * android.graphics.Color.red(p) + 0.7152 * android.graphics.Color.green(p) + 0.0722 * android.graphics.Color.blue(p)
            n++
        }
        return (sum / n).toFloat()
    }

    private val dark = Color(0xFF2A2C33)

    @Test fun focusedTileHasABrightLineAlongItsTopEdge() {
        val (bmp, density) = render(focused = true, face = dark)
        val band = ceil(1.25f * density).toInt()
        val top = bmp.meanLuma(0, band)
        val face = bmp.meanLuma((20 * density).roundToInt(), band)
        assertTrue("top band $top is not 20% above the face $face", top >= face * 1.2f)
        // The bottom edge is lit too, but fainter than the top.
        val bottom = bmp.meanLuma(bmp.height - band, band)
        assertTrue("bottom band $bottom should be above the face $face", bottom > face * 1.05f)
        assertTrue("bottom band $bottom should be fainter than the top $top", bottom < top)
    }

    @Test fun restingTileShowsNoEdgeLight() {
        val (bmp, density) = render(focused = false, face = dark)
        val band = ceil(1.25f * density).toInt()
        val top = bmp.meanLuma(0, band)
        val face = bmp.meanLuma((20 * density).roundToInt(), band)
        assertEquals("resting top band $top vs face $face", 1f, top / face, 0.03f)
        val bottom = bmp.meanLuma(bmp.height - band, band)
        assertEquals("resting bottom band $bottom vs face $face", 1f, bottom / face, 0.03f)
    }

    @Test fun whiteTileIsUnharmed() {
        val (bmp, density) = render(focused = true, face = Color.White)
        val band = ceil(1.25f * density).toInt()
        assertEquals(255f, bmp.meanLuma(0, band), 1f)
    }
}
