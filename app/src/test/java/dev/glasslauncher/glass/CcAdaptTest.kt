package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P188: Control Center's glass is one material on every backdrop. Every sheet pixel stays at or below [CcAdapt.CAP],
 * so white labels keep 4.5:1 over a white app as over a dark one; there is no light mode left to switch into.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcAdaptTest {
    private fun scene(base: Int, patch: Int? = null): Bitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until 270) for (x in 0 until 480) b.setPixel(x, y, if (patch != null && x in 260..420 && y in 120..200) patch else base)
    }

    private fun bake(src: Bitmap) = GlassMatch.panelSheet(
        src, 480, 270, 480 * GlassMatch.CC_LEFT, 0f, 480 * (1f - GlassMatch.CC_LEFT), 270f,
        GlassMatch.CC_TINT, GlassMatch.CC_SHEET_DIM, GlassMatch.CC_COLS,
        blurPasses = GlassMatch.CC_BLUR_PASSES, saturation = GlassMatch.CC_SATURATION, lift = GlassMatch.CC_LIFT, adapt = true,
    )!!

    private fun worstWhite(sheet: GlassMatch.Sheet): Float = sheet.pixels.minOf { px ->
        val bg = px or (0xFF shl 24)
        CcAdapt.contrast(CcAdapt.relLuminance(Color.White.toArgb()), CcAdapt.relLuminance(bg))
    }

    private val scenes = listOf(
        "white" to scene(0xFFFFFFFF.toInt()),
        "YouTube white with a dark wordmark" to scene(0xFFF9F9F9.toInt(), 0xFF0F0F0F.toInt()),
        "pale blue" to scene(0xFFDDEBFF.toInt()),
        "mid grey" to scene(0xFF808080.toInt()),
        "saturated red" to scene(0xFFE02020.toInt()),
        "black" to scene(0xFF000000.toInt()),
        "dark with a white patch" to scene(0xFF101418.toInt(), 0xFFFFFFFF.toInt()),
    )

    @Test fun everyBackdropGivesDarkGlassUnderWhiteText() {
        for ((name, src) in scenes) {
            val s = bake(src)
            val brightest = s.pixels.maxOf { CcAdapt.relLuminance(it or (0xFF shl 24)) }
            assertTrue("$name: glass reaches luminance $brightest (cap ${CcAdapt.CAP})", brightest <= CcAdapt.CAP + 0.002f)
            val text = worstWhite(s)
            assertTrue("$name: white text is $text:1 on the glass", text >= 4.5f)
        }
    }

    @Test fun theCapKeepsHueAndLeavesDarkPixelsAlone() {
        val dark = 0xFF101820.toInt()
        assertTrue(CcAdapt.fit(dark) == dark)
        val red = CcAdapt.fit(0xFFFF3030.toInt())
        assertTrue("capped red stays reddish", (red shr 16 and 0xFF) > (red shr 8 and 0xFF))
        assertTrue(CcAdapt.relLuminance(red) <= CcAdapt.CAP + 0.002f)
    }
}
