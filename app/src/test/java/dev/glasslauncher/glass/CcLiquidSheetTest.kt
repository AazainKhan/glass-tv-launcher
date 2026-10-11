package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.glasslauncher.shots.TV
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/** P76: Control Center's sheet turns busy art into soft colour (more blur, more vibrancy, lifted dark tiles). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class CcLiquidSheetTest {
    /** A busy 480x270 backdrop: 24 px vertical stripes of a muted red and a muted blue, like a wall of covers. */
    private fun busy(): Bitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until 270) for (x in 0 until 480) {
            val on = (x / 24 + y / 24) % 2 == 0
            b.setPixel(x, y, if (on) 0xFF8A5A5A.toInt() else 0xFF5A5A8A.toInt())
        }
    }

    private fun sheet(passes: Int, saturation: Float, lift: Float) =
        GlassMatch.panelSheet(busy(), 480, 270, 240f, 0f, 240f, 270f, GlassMatch.CC_TINT, GlassMatch.CC_DIM, GlassMatch.CC_COLS,
            blurPasses = passes, saturation = saturation, lift = lift)!!

    /**
     * The RMS step between neighbouring cells (all channels): how sharp the sheet's edges are. (The mean absolute
     * step doesn't change under a blur: a smeared edge has the same total height; its squared steps shrink.)
     */
    private fun detail(s: GlassMatch.Sheet): Double {
        var sum = 0.0; var n = 0
        for (y in 0 until s.height) for (x in 0 until s.width - 1) {
            val a = s.pixels[x + y * s.width]; val b = s.pixels[x + 1 + y * s.width]
            for (sh in intArrayOf(16, 8, 0)) { val d = ((a shr sh) and 0xFF) - ((b shr sh) and 0xFF); sum += d * d }
            n += 3
        }
        return Math.sqrt(sum / n)
    }

    private fun mean(s: GlassMatch.Sheet, ch: Int): Double = s.pixels.map { (it shr ch) and 0xFF }.average()

    @Test fun busyArtBecomesSoftColour() {
        val before = detail(sheet(passes = 1, saturation = 1f, lift = 0f))
        val after = detail(sheet(GlassMatch.CC_BLUR_PASSES, saturation = 1f, lift = 0f))
        println("P76 detail before=$before after=$after")
        assertTrue("the blur alone should leave under half the edge sharpness: before $before, after $after", after < before * 0.5)
    }

    @Test fun colourIsMoreVibrant() {
        val flat = sheet(passes = GlassMatch.CC_BLUR_PASSES, saturation = 1f, lift = 0f)
        val vivid = sheet(passes = GlassMatch.CC_BLUR_PASSES, saturation = GlassMatch.CC_SATURATION, lift = 0f)
        val spread = { s: GlassMatch.Sheet -> abs(mean(s, 16) - mean(s, 0)) }
        assertTrue("red and blue should be further apart: ${spread(flat)} -> ${spread(vivid)}", spread(vivid) > spread(flat) * 1.2)
    }

    @Test fun darkTilesAreLiftedNotBlack() {
        val plain = sheet(passes = GlassMatch.CC_BLUR_PASSES, saturation = 1f, lift = 0f)
        val lifted = sheet(passes = GlassMatch.CC_BLUR_PASSES, saturation = 1f, lift = GlassMatch.CC_LIFT)
        val luma = { s: GlassMatch.Sheet -> (mean(s, 16) + mean(s, 8) + mean(s, 0)) / 3 }
        assertTrue("lifted ${luma(lifted)} should be lighter than ${luma(plain)}", luma(lifted) > luma(plain) + 8)
    }
}
