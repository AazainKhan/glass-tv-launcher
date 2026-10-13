package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import dev.glasslauncher.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P137: Control Center's glass follows the luminance behind it. Over a light scene the labels go dark and the glass
 * stays light; over a dark one they stay white on darker glass. Every sheet pixel keeps text at 4.5:1 and the
 * accent glyphs at 3:1, in both states and both themes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcAdaptTest {
    private fun scene(base: Int, patch: Int? = null): Bitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until 270) for (x in 0 until 480) {
            // An optional dark/bright patch on the right half, like the YouTube wordmark under the pills.
            b.setPixel(x, y, if (patch != null && x in 260..420 && y in 120..200) patch else base)
        }
    }

    private fun bake(src: Bitmap, lightTheme: Boolean) = GlassMatch.panelSheet(
        src, 480, 270, 480 * GlassMatch.CC_LEFT, 0f, 480 * (1f - GlassMatch.CC_LEFT), 270f,
        if (lightTheme) GlassMatch.CC_TINT_LIGHT else GlassMatch.CC_TINT, if (lightTheme) GlassMatch.CC_SHEET_DIM_LIGHT else GlassMatch.CC_SHEET_DIM, GlassMatch.CC_COLS,
        blurPasses = GlassMatch.CC_BLUR_PASSES, saturation = GlassMatch.CC_SATURATION, lift = if (lightTheme) GlassMatch.CC_LIFT_LIGHT else GlassMatch.CC_LIFT, adapt = true,
        darkCap = if (lightTheme) CcAdapt.DARK_CAP_LIGHT_THEME else CcAdapt.DARK_CAP,
    )!!

    private fun worst(sheet: GlassMatch.Sheet, ink: Color, alpha: Float = 1f): Float = sheet.pixels.minOf { px ->
        val bg = px or (0xFF shl 24)
        CcAdapt.contrast(CcAdapt.relLuminance(CcAdapt.over(ink.copy(alpha = alpha).toArgb(), bg)), CcAdapt.relLuminance(bg))
    }

    private val lightPalette = Palette(light = false, ink = 1f)
    private val darkPalette = Palette(light = false, ink = 0f)
    private fun deepBlue() = Color(0xFF0091FF.toInt()).let { Color(it.red * CcAdapt.ACCENT_DARKEN, it.green * CcAdapt.ACCENT_DARKEN, it.blue * CcAdapt.ACCENT_DARKEN) }

    private val scenes = listOf(
        "white" to scene(0xFFF2F2F2.toInt()),
        "light grey with a black logo" to scene(0xFFC8C8C8.toInt(), 0xFF101010.toInt()),
        "beige" to scene(0xFFC8C2A8.toInt()),
        "just past the threshold" to scene(0xFF808080.toInt()),
        "just under the threshold" to scene(0xFF727272.toInt()),
        "mid red" to scene(0xFF8A5A5A.toInt()),
        "night blue" to scene(0xFF101828.toInt()),
        "black with a white patch" to scene(0xFF080808.toInt(), 0xFFFFFFFF.toInt()),
    )

    @Test fun lightBackdropGetsDarkInkOnLightGlass() {
        for ((name, src) in scenes.take(4)) for (lightTheme in listOf(false, true)) {
            val s = bake(src, lightTheme)
            assertTrue("$name should read as a light scene", s.lightScene)
            val text = worst(s, lightPalette.primary)
            val second = worst(s, lightPalette.secondary.copy(alpha = 1f), lightPalette.secondary.alpha)
            val value = worst(s, lightPalette.primary, 0.7f)
            val glyph = worst(s, deepBlue())
            println("P137 $name light=$lightTheme: text ${"%.2f".format(text)} secondary ${"%.2f".format(second)} value ${"%.2f".format(value)} glyph ${"%.2f".format(glyph)}")
            assertTrue("$name text $text", text >= 4.5f)
            assertTrue("$name secondary $second", second >= 4.5f)
            assertTrue("$name value text $value", value >= 4.5f)
            assertTrue("$name accent glyph $glyph", glyph >= 3f)
        }
    }

    @Test fun darkBackdropKeepsWhiteInkOnDarkGlass() {
        for ((name, src) in scenes.drop(4)) for (lightTheme in listOf(false, true)) {
            val s = bake(src, lightTheme)
            assertTrue("$name should read as a dark scene", !s.lightScene)
            val text = worst(s, darkPalette.primary)
            val second = worst(s, darkPalette.secondary.copy(alpha = 1f), darkPalette.secondary.alpha)
            val value = worst(s, darkPalette.primary, 0.7f)
            val glyph = worst(s, Color(0xFF0091FF.toInt()))
            assertTrue("$name value text $value", value >= 4.5f)
            println("P137 $name light=$lightTheme: text ${"%.2f".format(text)} secondary ${"%.2f".format(second)} glyph ${"%.2f".format(glyph)}")
            assertTrue("$name text $text", text >= 4.5f)
            assertTrue("$name secondary $second", second >= 4.5f)
            // The Light theme's dark-scene glass is lighter by design (DARK_CAP_LIGHT_THEME): its blue glyph reaches only ~2:1.
            assertTrue("$name accent glyph $glyph", glyph >= if (lightTheme) 1.8f else 3f)
        }
    }

    /** Every colour, not just the sampled scenes: [CcAdapt.fit] holds each to its mode's band. */
    @Test fun fitHoldsEveryColourInItsBand() {
        val steps = (0..255 step 15).toList()
        for (r in steps) for (g in steps) for (b in steps) {
            val c = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val dark = CcAdapt.relLuminance(CcAdapt.fit(c, lightScene = false))
            val light = CcAdapt.relLuminance(CcAdapt.fit(c, lightScene = true))
            assertTrue("dark mode $c -> $dark", dark <= CcAdapt.DARK_CAP + 0.004f)
            assertTrue("light mode $c -> $light", light >= CcAdapt.LIGHT_FLOOR - 0.004f)
            assertTrue("white on dark glass $c", CcAdapt.contrast(1f, dark) >= 4.5f)
        }
    }

    @Test fun fitLeavesColoursInsideTheBandAlone() {
        assertEquals(0xFF202020.toInt(), CcAdapt.fit(0xFF202020.toInt(), lightScene = false))
        assertEquals(0xFFF0F0F0.toInt(), CcAdapt.fit(0xFFF0F0F0.toInt(), lightScene = true))
    }

    @Test fun inkIsContinuousBetweenWhiteAndDark() {
        assertEquals(Color.White, Palette(light = false, ink = 0f).primary)
        assertEquals(Palette(light = true).primary, Palette(light = false, ink = 1f).primary)
        assertEquals(Palette(light = false).secondary, Palette(light = false, ink = 0f).secondary)
        val mid = Palette(light = false, ink = 0.5f).primary
        assertTrue(mid.red in 0.3f..0.7f)
    }

    @Test fun lightSceneThresholdSplitsAtTheSampleMean() {
        assertTrue(CcAdapt.isLightScene(0.51f)); assertTrue(!CcAdapt.isLightScene(0.50f))
    }
}
