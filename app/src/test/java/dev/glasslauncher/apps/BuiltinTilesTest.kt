package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.glasslauncher.shots.TV
import dev.glasslauncher.shots.shot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P90: the Smart Home tile's own art (Alexa blue), on dark and light rows, and a sanity check that it isn't flat. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV)
class BuiltinTilesTest {
    private fun sheet(): Bitmap {
        val w = 1600; val h = 760
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((row, bg) in listOf(0xFF111318.toInt(), 0xFFE9EAEE.toInt()).withIndex()) {
            p.color = bg; c.drawRect(0f, row * 380f, w.toFloat(), (row + 1) * 380f, p)
            for (variant in 0..0) {
                val tile = BuiltinTiles.smartHome(560, 315)
                // The row's tile shape: rounded, as Home draws it.
                val x = 80f + variant * 760f; val y = row * 380f + 32f
                val save = c.save()
                val path = android.graphics.Path().apply { addRoundRect(RectF(x, y, x + 560, y + 315), 40f, 40f, android.graphics.Path.Direction.CW) }
                c.clipPath(path); c.drawBitmap(tile, x, y, null); c.restoreToCount(save)
            }
        }
        return out
    }

    @Test fun onDarkAndLightRows() {
        sheet().captureRoboImage(shot("builtin-smart-home-options"))
    }

    @Test fun theTileIsRichNotFlat() {
        for (variant in 0..0) {
            val t = BuiltinTiles.smartHome(560, 315)
            val top = t.getPixel(20, 20); val bottom = t.getPixel(540, 300)
            val d = Math.abs(Color.red(top) - Color.red(bottom)) + Math.abs(Color.green(top) - Color.green(bottom)) + Math.abs(Color.blue(top) - Color.blue(bottom))
            assertTrue("variant $variant has a gradient (diff $d)", d > 120)
            // A white glyph at the centre.
            val centre = t.getPixel(280, 140)
            assertTrue("white glyph at the centre", Color.red(centre) > 230 && Color.green(centre) > 230 && Color.blue(centre) > 230)
        }
    }
}
