package dev.glasslauncher.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P38: the tiles' bottom bevel over a light scene must not read as grey halos. */
class CcBevelTest {
    @Test fun overADarkOrMidSceneItIsTheFaintBlackShade() {
        for (luma in listOf(0.05f, 0.3f, 0.6f)) assertEquals(Color.Black.copy(alpha = 0.12f), bevelShade(luma))
    }

    @Test fun overALightSceneItIsALighterCoolShadeAtAThirdOfTheStrength() {
        for (luma in listOf(0.7f, 0.95f, 1f)) {
            val c = bevelShade(luma)
            assertTrue("alpha ${c.alpha}", c.alpha <= 0.04f + 1e-6f)
            assertTrue("it is not black: ${c.red}", c.red > 0.3f && c.blue > c.red)
        }
    }

    @Test fun meanLumaIsReadFromPixelsNotFromTheHardwareBitmap() {
        val white = IntArray(16) { 0xFFFFFFFF.toInt() }
        val black = IntArray(16) { 0xFF000000.toInt() }
        assertEquals(1f, dev.glasslauncher.glass.GlassMatch.meanLuma(white), 0.01f)
        assertEquals(0f, dev.glasslauncher.glass.GlassMatch.meanLuma(black), 0.01f)
        assertEquals(0.5f, dev.glasslauncher.glass.GlassMatch.meanLuma(white + black), 0.01f)
        assertEquals(0f, dev.glasslauncher.glass.GlassMatch.meanLuma(IntArray(0)), 0f)
    }
}
