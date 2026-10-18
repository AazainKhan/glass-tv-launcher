package dev.glasslauncher.shots

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.MainActivity
import dev.glasslauncher.glass.CcAdapt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P206: Control Center's tiles are the dock's clear glass (E): at open they take the backdrop's own clear texture in its
 * text-safe range, the one the tray samples, and bake nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcDockGlassTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val pinnedClock = PinnedClockRule()

    @After fun backToA() { CcAdapt.defaultVariant = "A"; CcAdapt.variant = "A" }

    @Test fun controlCenterDrawsTheDocksClearTexture() {
        TvHarness.setUp()
        CcAdapt.defaultVariant = "E"; CcAdapt.variant = "E"
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForHome()
            compose.press(Button.Up, Button.Up, Button.Up)
            compose.settle()
            val home = dev.glasslauncher.home.ControlCenterWindow.homeBackdrop ?: error("Home has no backdrop")
            val bakes = dev.glasslauncher.glass.GlassMatch.sheetBakes
            compose.press(Button.Select)
            compose.waitForTag("control-center"); compose.settle()
            assertEquals("opening Control Center baked a sheet", bakes, dev.glasslauncher.glass.GlassMatch.sheetBakes)
            val used = dev.glasslauncher.home.CcMaterial.last ?: error("Control Center has no material")
            assertSame("Control Center does not draw the dock's clear texture", home.clearLegible.asAndroidBitmap(), used.bitmap)
        }
    }
}
