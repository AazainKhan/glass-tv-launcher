package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P237: Control Center's white labels sit on the dark-ink range of the scene, so even a white scene keeps them at 4.5:1. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CcLegibleTextureTest {
    private fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }

    @Test fun whiteLabelsStayLegibleOnAWhiteScene() {
        val white = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Blur.legible(white, light = false)
        val p = white.getPixel(1, 1)
        val l = 0.2126 * lin(Color.red(p)) + 0.7152 * lin(Color.green(p)) + 0.0722 * lin(Color.blue(p))
        val contrast = 1.05 / (l + 0.05)
        assertTrue("white text on the brightest Control Center glass is $contrast:1", contrast >= 4.5)
    }
}
