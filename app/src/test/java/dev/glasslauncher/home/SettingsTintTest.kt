package dev.glasslauncher.home

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P105 #8: the Settings page is a pale/deep tint of the scene's hue, never a flat near-white. */
class SettingsTintTest {
    private fun lum(c: Int) = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    @Test fun noSceneIsTheNeutralPair() {
        assertEquals(0xFFF2F2F8.toInt(), SettingsTint.page(null, light = true)[0].toArgb())
        assertEquals(0xFF22252E.toInt(), SettingsTint.page(null, light = false)[0].toArgb())
    }

    @Test fun aWarmSceneTintsALightPageTowardsRed() {
        val red = 0xFFB03020.toInt()
        val top = SettingsTint.page(red, light = true)[0].toArgb()
        assertTrue("red channel leads: ${top.toString(16)}", (top shr 16 and 0xFF) > (top and 0xFF) + 4)
        assertTrue("still a light page: ${lum(top)}", lum(top) > 0.80f)
    }

    @Test fun aDarkPageStaysDeepWhateverTheScene() {
        val teal = 0xFF20A0A0.toInt()
        val c = SettingsTint.page(teal, light = false)
        assertTrue("dark: ${lum(c[0].toArgb())}", lum(c[0].toArgb()) < 0.30f && lum(c[1].toArgb()) < lum(c[0].toArgb()))
    }

    @Test fun aGreyScenePageIsNearNeutral() {
        val grey = 0xFF808080.toInt()
        val t = SettingsTint.page(grey, light = true)[0].toArgb()
        val spread = maxOf(t shr 16 and 0xFF, t shr 8 and 0xFF, t and 0xFF) - minOf(t shr 16 and 0xFF, t shr 8 and 0xFF, t and 0xFF)
        assertTrue("spread $spread", spread < 16)
    }

    @Test fun theBottomIsDarkerThanTheTopInLight() {
        val l = SettingsTint.page(0xFF3060C0.toInt(), light = true)
        assertTrue(lum(l[1].toArgb()) < lum(l[0].toArgb()))
    }
}
