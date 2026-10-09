package dev.glasslauncher.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fill drawn before a tile's glass arrives is the colour the glass will settle to, so the swap is invisible. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [35])
class GlassMatchTest {
    @Test fun matchesTintThenDimOverTheSceneBehind() {
        val c = GlassMatch.fill(Color(0.5f, 0.5f, 0.5f), Color.Black.copy(alpha = 0.06f), dim = 0.42f)
        assertEquals(0.5f * 0.94f * 0.58f, c.red, 0.01f)
        assertEquals(1f, c.alpha, 0f)
    }

    @Test fun aWhiteTintLightensIt() {
        val c = GlassMatch.fill(Color(0.2f, 0.2f, 0.2f), Color.White.copy(alpha = 0.5f), dim = 0f)
        assertEquals(0.6f, c.red, 0.01f)
    }

    private fun bitmap(w: Int, h: Int, color: (Int, Int) -> Int) = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, color(x, y))
    }

    @Test fun aFlatSceneBakesToTheFlatFillEverywhere() {
        val grey = 0xFF808080.toInt()
        val sheet = GlassMatch.panelSheet(bitmap(48, 27) { _, _ -> grey }, 960, 540, 600f, 40f, 300f, 480f, Color.Black.copy(alpha = 0.06f), 0.42f)!!
        val want = GlassMatch.fill(Color(grey), Color.Black.copy(alpha = 0.06f), 0.42f).toArgb()
        assertTrue(sheet.pixels.all { kotlin.math.abs(android.graphics.Color.red(it) - android.graphics.Color.red(want)) <= 1 })
    }

    // A scene that is dark on top and red below gives a sheet with only a faint drift between the two.
    @Test fun theSheetKeepsOnlyAFaintDriftOfTheScene() {
        val scene = bitmap(48, 27) { _, y -> if (y < 14) 0xFF0A0C12.toInt() else 0xFF8A2A24.toInt() }
        val sheet = GlassMatch.panelSheet(scene, 960, 540, 600f, 0f, 300f, 540f, Color.Black.copy(alpha = 0.06f), 0.42f)!!
        val top = android.graphics.Color.red(sheet.pixels[0]); val bottom = android.graphics.Color.red(sheet.pixels[sheet.pixels.size - 1])
        val raw = android.graphics.Color.red(GlassMatch.fill(Color(0x8A / 255f, 0f, 0f), Color.Black.copy(alpha = 0.06f), 0.42f).toArgb()) - android.graphics.Color.red(GlassMatch.fill(Color(0x0A / 255f, 0f, 0f), Color.Black.copy(alpha = 0.06f), 0.42f).toArgb())
        assertTrue("drift ${bottom - top} of ${raw}", bottom - top in 1..(raw / 3))
        assertTrue(GlassMatch.panelSheet(scene, 0, 540, 0f, 0f, 1f, 1f, Color.Black, 0f) == null)
    }
}
