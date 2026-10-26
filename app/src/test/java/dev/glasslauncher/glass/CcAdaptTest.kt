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
 * P188: Control Center's glass is one material on every backdrop. P195: Liquid Glass's bake (brightness and detail, no colour). Every sheet pixel stays at or below [CcAdapt.CAP],
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

    /**
     * P289: the control fill takes the colour behind it, and the scrim stays on the scrim. GlassMatch bakes the sheet
     * with CC_SHEET_DIM = 0 for exactly this reason (F02), so the tiles are the scene, not the scene under the dim.
     * Measured on mid grey: on a white scene CcAdapt's luminance cap binds and hides the difference.
     */
    @Test fun theControlFillTakesTheSceneRatherThanTheDim() {
        val grey = scene(0xFF808080.toInt())
        fun luma(dim: Float) = GlassMatch.panelSheet(
            grey, 480, 270, 480 * GlassMatch.CC_LEFT, 0f, 480 * (1f - GlassMatch.CC_LEFT), 270f,
            GlassMatch.CC_TINT, dim, GlassMatch.CC_COLS,
            blurPasses = GlassMatch.CC_BLUR_PASSES, saturation = GlassMatch.CC_SATURATION,
            lift = GlassMatch.CC_LIFT, adapt = true,
        )!!.pixels.map { CcAdapt.relLuminance(it or (0xFF shl 24)) }.average()
        val undimmed = luma(GlassMatch.CC_SHEET_DIM)
        val dimmed = luma(0.42f)
        assertTrue("the fill must not carry the scrim ($undimmed vs $dimmed)", undimmed > dimmed + 0.002)
        // And the scrim is still a real dim, so it is on the backdrop and not simply gone.
        assertTrue("the scrim still dims (${GlassMatch.CC_DIM})", GlassMatch.CC_DIM > 0.2f)
    }

    /** P195: any colour becomes neutral grey (no saturation boost, P188), within Apple's compressed, washed range. */
    @Test fun anyColourBecomesNeutralGlass() {
        for (c in listOf(0xFF000000.toInt(), 0xFF101820.toInt(), 0xFFFF3030.toInt(), 0xFF2050FF.toInt(), 0xFFFFFFFF.toInt())) {
            val g = CcAdapt.fit(c)
            val ch = listOf((g shr 16) and 0xFF, (g shr 8) and 0xFF, g and 0xFF)
            assertTrue("fit(${Integer.toHexString(c)}) = $ch is not neutral glass", ch.all { it in 30..110 } && ch.max() == ch.min())
        }
    }

    /** P195: the glass keeps the scene's brightness as detail (Liquid Glass), with no colour cast anywhere. */
    @Test fun theGlassShowsTheScenesDetailWithoutColour() {
        fun level(name: String) = bake(scenes.first { it.first == name }.second).pixels.map { it and 0xFF }.average()
        val dark = level("black"); val white = level("white")
        assertTrue("white scene ($white) should read brighter than black ($dark)", white > dark + 15)
        for ((name, src) in scenes) for (px in bake(src).pixels) {
            val ch = listOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            assertTrue("$name: a glass pixel $ch has a colour cast", ch.max() - ch.min() <= 2)
        }
    }

    /** P206: the stick-picked candidates keep white text at 4.5:1 on every scene; B stays neutral, C keeps colour small. */
    @Test fun theTransparentCandidatesKeepTextLegible() {
        try {
            for (v in listOf("B", "C")) {
                CcAdapt.variant = v
                for ((name, src) in scenes) for (px in bake(src).pixels) {
                    val text = CcAdapt.contrast(CcAdapt.relLuminance(Color.White.toArgb()), CcAdapt.relLuminance(px or (0xFF shl 24)))
                    assertTrue("$v $name: white text is $text:1", text >= 4.5f)
                    val ch = listOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
                    assertTrue("$v $name: colour spread ${ch.max() - ch.min()}", ch.max() - ch.min() <= if (v == "B") 2 else 40)
                }
            }
        } finally { CcAdapt.variant = "A" }
    }
}
