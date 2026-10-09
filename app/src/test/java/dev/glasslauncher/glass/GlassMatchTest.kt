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

    // The sheet shows the scene: dark on top and red below stays dark on top and red below, by the full difference.
    @Test fun theSheetShowsTheSceneItIsMadeFrom() {
        val scene = bitmap(48, 27) { _, y -> if (y < 14) 0xFF0A0C12.toInt() else 0xFF8A2A24.toInt() }
        val tint = Color.Black.copy(alpha = 0.06f)
        val sheet = GlassMatch.panelSheet(scene, 960, 540, 600f, 0f, 300f, 540f, tint, 0.42f)!!
        val top = android.graphics.Color.red(sheet.pixels[2 * sheet.width + 5]); val bottom = android.graphics.Color.red(sheet.pixels[(sheet.height - 3) * sheet.width + 5])
        val raw = android.graphics.Color.red(GlassMatch.fill(Color(0x8A / 255f, 0f, 0f), tint, 0.42f).toArgb()) - android.graphics.Color.red(GlassMatch.fill(Color(0x0A / 255f, 0f, 0f), tint, 0.42f).toArgb())
        assertTrue("drift ${bottom - top} of $raw", bottom - top >= raw * 0.9f)
        assertTrue(GlassMatch.panelSheet(scene, 0, 540, 0f, 0f, 1f, 1f, Color.Black, 0f) == null)
    }

    // The bake works on the panel's crop of the 480x270 sample: a few KB, never a full-size intermediate.
    @Test fun theBakeIsSmall() {
        val scene = bitmap(480, 270) { x, y -> 0xFF000000.toInt() or (x and 0xFF shl 16) or (y and 0xFF shl 8) }
        val sheet = GlassMatch.panelSheet(scene, 1920, 1080, 1314f, 0f, 606f, 790f, Color.Black.copy(alpha = 0.06f), 0.42f)!!
        assertTrue("sheet ${sheet.pixels.size * 4} bytes", sheet.pixels.size * 4 <= 64 * 1024)
        // The crop is read from the right place: the sheet's left edge is darker in red than its right edge.
        assertTrue(android.graphics.Color.red(sheet.pixels[0]) < android.graphics.Color.red(sheet.pixels[sheet.width - 1]))
    }

    // Over another app the capture is baked by glassOnly: it carries the sheet too, so the open has nothing to bake.
    @Test fun theOverAppCaptureCarriesTheSheet() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = WallpaperLoader(context, okhttp3.OkHttpClient())
        val shot = bitmap(192, 108) { x, _ -> if (x < 96) 0xFF0A0C12.toInt() else 0xFF8A2A24.toInt() }
        GlassMatch.keepPixels = true
        val backdrop = try { kotlinx.coroutines.runBlocking { loader.glassOnly(shot) } } finally { GlassMatch.keepPixels = false }
        val sheet = backdrop.ccSheet
        org.junit.Assert.assertNotNull("glassOnly made no Control Center sheet", sheet)
        // The right half of the screen, full height, at ~6 dp cells: tens of KB, not a screen-sized copy.
        val px = sheet!!.pixels!!
        assertTrue("sheet ${px.width}x${px.height}, ${px.pixels.size * 4} bytes", px.width == GlassMatch.CC_COLS && px.pixels.size * 4 <= 32 * 1024)
        assertEquals(0.5f, sheet.left, 0f); assertEquals(1f, sheet.bottom, 0f)
        // It is the scene on the right (red), not the dark left half.
        assertTrue(android.graphics.Color.red(px.pixels[px.pixels.size / 2]) > 2 * android.graphics.Color.blue(px.pixels[px.pixels.size / 2]))
    }
}
