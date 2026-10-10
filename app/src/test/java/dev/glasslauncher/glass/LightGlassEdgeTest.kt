package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.GlassApp
import dev.glasslauncher.app
import dev.glasslauncher.shots.TV
import dev.glasslauncher.ui.LocalPalette
import dev.glasslauncher.ui.Palette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/** P18: light glass over a light page keeps a defined, soft edge: a faint inner band and a bright rim, no hard line. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class LightGlassEdgeTest {
    @get:Rule val compose = createComposeRule()

    private fun grey(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)

    private fun profile(style: GlassStyle): List<Float> {
        val app = ApplicationProvider.getApplicationContext<GlassApp>().app
        val white = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE) }
        val state = BackdropState()
        runBlocking { state.swap(app.wallpapers.fromImage(white, light = true), animate = false) }
        compose.setContent {
            CompositionLocalProvider(LocalBackdrop provides state, LocalPalette provides Palette(light = true)) {
                Box(Modifier.fillMaxSize().background(Color.White).onSizeChanged { state.rootSize = it }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(300.dp, 140.dp).glass(state, RoundedCornerShape(24.dp), style).testTag("panel"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val b = compose.onNodeWithTag("panel").fetchSemanticsNodeBounds()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val d = compose.density.density
        val y = b.center.y.toInt()
        // Scan inward from just outside the left edge to well inside.
        val x0 = (b.left - 3 * d).toInt(); val x1 = (b.left + 16 * d).toInt()
        val profile = (x0..x1).map { grey(shot.getPixel(it, y)) }
        return profile
    }

    @Test fun theEdgeOfLightGlassOverWhiteIsADefinedButSoftBand() {
        val profile = profile(GlassStyle.panel(true))
        val interior = profile.last()
        val darkestInside = profile.drop(((3 + 0.5f) * compose.density.density).toInt()).minOrNull() ?: interior
        val hardest = profile.zipWithNext().maxOf { (a, c) -> abs(c - a) }
        assertTrue("a faint band inside the edge should be visible: darkest $darkestInside vs interior $interior", interior - darkestInside >= 2f)
        assertTrue("and soft: no step over 16 levels between neighbouring pixels (got $hardest)", hardest <= 16f)
    }

    @Test fun styleSwitchesTheBandOnlyOnTheLargeSurfaces() {
        assertTrue(GlassStyle.panel(true).edgeBand && GlassStyle.shelf(true).edgeBand)
        assertTrue("dark glass has no band", !GlassStyle.panel(false).edgeBand && !GlassStyle.shelf(false).edgeBand)
        assertTrue("Control Center tiles are many: no band", !GlassStyle.control(true).edgeBand)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodeBounds() = fetchSemanticsNode().boundsInRoot
}
