package dev.glasslauncher.widgets

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.GlassApp
import dev.glasslauncher.app
import dev.glasslauncher.data.LauncherConfig
import dev.glasslauncher.glass.BackdropState
import dev.glasslauncher.glass.LocalBackdrop
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

/**
 * P52: over white the status pill's edge is a soft shadow and the glass's bright rim, not a drawn line. The old
 * border was 22% black, 1 dp: against white that is a step of ~55 grey levels in one pixel.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class PillEdgeTest {
    @get:Rule val compose = createComposeRule()

    private fun grey(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)

    @Test fun overWhiteTheEdgeIsSoftAndStillSeen() {
        val app = ApplicationProvider.getApplicationContext<GlassApp>().app
        val white = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE) }
        val state = BackdropState()
        runBlocking { state.swap(app.wallpapers.fromImage(white, light = true), animate = false) }
        compose.setContent {
            CompositionLocalProvider(LocalBackdrop provides state, LocalPalette provides Palette(light = true)) {
                Box(Modifier.fillMaxSize().background(Color.White).onSizeChanged { state.rootSize = it }) {
                    StatusPill(LauncherConfig(), IdleState(60_000), focusable = false, onSelect = {}, modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 30.dp))
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        val node = compose.onNodeWithTag("status-pill").fetchSemanticsNode().boundsInRoot
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val d = compose.density.density
        // Straight down the middle of the capsule's right-hand third (the gear is at the far end; the clock left).
        val x = (node.left + node.width * 0.55f).toInt()
        var hardest = 0f
        var darkest = 255f
        var y = (node.bottom - 3 * d).toInt()
        val end = (node.bottom + 9 * d).toInt()
        while (y < end) {
            val a = grey(shot.getPixel(x, y)); val b = grey(shot.getPixel(x, y + 1))
            hardest = maxOf(hardest, abs(b - a)); darkest = minOf(darkest, a, b)
            y++
        }
        println("PILLEDGE hardest=$hardest darkest=$darkest")
        assertTrue("a $hardest level step at the bottom edge reads as a drawn line (max 12)", hardest <= 12f)
        assertTrue("the edge should still be seen: darkest $darkest of 255 (need < 249)", darkest < 249f)
    }
}
