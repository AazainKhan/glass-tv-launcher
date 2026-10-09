package dev.glasslauncher.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import dev.glasslauncher.ui.Shapes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Light glass over a smooth, flat backdrop must not show a grey band along its bottom edge (P14): the
 * bevel's black inner shadow, even at 5%, read as dirt with a visible start line. Down an empty column of
 * the surface the luminance changes smoothly (no 3 px step over 2 luma) and the bottom 30% stays within 6%
 * of the mid-height row.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class LightGlassBottomEdgeTest {
    @get:Rule val compose = createComposeRule()

    private fun luma(c: Int) = 0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)

    private fun check(name: String, style: GlassStyle) {
        val state = BackdropState()
        val flat = android.graphics.Bitmap.createBitmap(32, 18, android.graphics.Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(0xFFDDDFE8.toInt()) }.asImageBitmap()
        kotlinx.coroutines.runBlocking {
            state.swap(Backdrop(flat, flat, listOf(flat), flat, flat, isLight = true), animate = false)
        }
        var bounds = androidx.compose.ui.geometry.Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalBackdrop provides state) {
                Box(Modifier.size(400.dp, 300.dp).background(Color(0xFFDDDFE8)).onSizeChanged { state.rootSize = it }) {
                    Box(
                        Modifier.padding(20.dp).size(360.dp, 200.dp)
                            .onGloballyPositioned { bounds = it.boundsInRoot() }
                            .glass(state, Shapes.panel, style),
                    )
                }
            }
        }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage().asAndroidBitmap()
        val x = bounds.center.x.toInt()
        val top = bounds.top.toInt(); val h = bounds.height.toInt()
        // Mean luma per 3 px row, from below the top rim to above the bottom rim.
        val ys = ((top + (h * 0.08f).toInt())..(top + (h * 0.96f).toInt() - 3) step 3).toList()
        val rows = ys.map { y -> (y until y + 3).sumOf { yy -> (x - 6 until x + 6).sumOf { xx -> luma(img.getPixel(xx, yy)) } } / 36.0 }
        val steps = rows.zipWithNext { a, b -> abs(b - a) }
        val mid = rows[ys.indexOfFirst { it >= top + h / 2 }]
        val lowRows = rows.filterIndexed { i, _ -> ys[i] >= top + (h * 0.7f).toInt() }
        val darkest = lowRows.min()
        val profile = (0..10).joinToString { "%.1f".format(rows[(rows.size - 1) * it / 10]) }
        assertTrue("$name: a ${"%.1f".format(steps.max())} luma step between 3 px rows (max 2); profile $profile", steps.max() <= 2.0)
        val page = luma(img.getPixel(x, top - 6))
        assertTrue("$name: glass bottom ${"%.1f".format(darkest)} is darker than the page behind it ${"%.1f".format(page)}; profile $profile", darkest >= page)
        assertTrue("$name: bottom 30% darkest ${"%.1f".format(darkest)} is ${"%.1f".format(darkest / mid * 100)}% of mid $mid (min 94%); profile $profile", darkest >= 0.94 * mid)
    }

    @Test fun trayFollowsTheBackdropDownToItsBottomEdge() = check("tray", GlassStyle.shelf(true))
    @Test fun panelFollowsTheBackdropDownToItsBottomEdge() = check("panel", GlassStyle.panel(true))
    @Test fun controlTileFollowsTheBackdropDownToItsBottomEdge() = check("control", GlassStyle.control(true))
}
