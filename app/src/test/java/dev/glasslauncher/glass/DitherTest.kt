package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P285: RGB_565 halves the backdrop's memory (8.3 -> 4.0 MB per 1080p frame) but quantises to 5 bits of red and blue,
 * which bands a dark gradient. Ordered dithering is what makes that acceptable, so the test measures the banding
 * rather than trusting the matrix.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DitherTest {

    /**
     * A dark vertical gradient: the case 565 is worst at, and what a wallpaper's sky or a vignette looks like.
     * Pixels are written directly rather than through a Canvas shader, so the test does not depend on how well
     * Robolectric renders a LinearGradient - it just needs a known ramp.
     */
    private fun gradient(w: Int = 256, h: Int = 256, top: Int = 0x101014, bottom: Int = 0x2A2A38): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val t = y.toFloat() / (h - 1)
                fun lerp(shift: Int): Int {
                    val a = (top shr shift) and 0xFF; val c = (bottom shr shift) and 0xFF
                    return (a + (c - a) * t).toInt().coerceIn(0, 255)
                }
                val v = (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
                for (x in 0 until w) px[y * w + x] = v
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

    /** Mean absolute error per channel against the original: how far the 565 version sits from the truth. */
    private fun error(original: Bitmap, quantised: Bitmap): Double {
        val a = IntArray(original.width * original.height)
        val b = IntArray(quantised.width * quantised.height)
        original.getPixels(a, 0, original.width, 0, 0, original.width, original.height)
        quantised.getPixels(b, 0, quantised.width, 0, 0, quantised.width, quantised.height)
        var sum = 0L
        for (i in a.indices) {
            sum += Math.abs(Color.red(a[i]) - Color.red(b[i])).toLong()
            sum += Math.abs(Color.green(a[i]) - Color.green(b[i])).toLong()
            sum += Math.abs(Color.blue(a[i]) - Color.blue(b[i])).toLong()
        }
        return sum.toDouble() / (a.size * 3)
    }

    /**
     * Flat runs down a column: how many times the colour stops changing and starts again. A banded gradient sits at
     * one value for tens of rows then jumps; a dithered one changes most rows. Fewer, longer runs = more visible bands.
     */
    private fun longestRun(bitmap: Bitmap, x: Int): Int {
        var longest = 1; var run = 1
        var prev = bitmap.getPixel(x, 0)
        for (y in 1 until bitmap.height) {
            val p = bitmap.getPixel(x, y)
            if (p == prev) run++ else { if (run > longest) longest = run; run = 1 }
            prev = p
        }
        return maxOf(longest, run)
    }

    /** Quantising to 565 on its own leaves long flat runs - this is the banding the dither has to fix. */
    @Test fun aDarkGradientBandsWhenQuantisedWithoutDithering() {
        val src = gradient()
        val plain = src.copy(Bitmap.Config.RGB_565, false)
        assertTrue("565 should band a dark gradient (longest flat run ${longestRun(plain, 128)})", longestRun(plain, 128) > 4)
    }

    @Test fun ditheringShortensTheBands() {
        val src = gradient()
        val undithered = src.copy(Bitmap.Config.RGB_565, false).let { longestRun(it, 128) }

        val ditheredSrc = gradient()
        Dither.apply(ditheredSrc)
        val dithered = ditheredSrc.copy(Bitmap.Config.RGB_565, false).let { longestRun(it, 128) }

        assertTrue("dither must shorten the longest flat run ($dithered vs $undithered)", dithered < undithered)
    }

    /** The dither must move pixels around the true value, not away from it: error stays within a fraction of a step. */
    @Test fun ditheringKeepsTheAverageRight() {
        val src = gradient()
        val d = gradient(); Dither.apply(d)
        val q = d.copy(Bitmap.Config.RGB_565, false)
        val err = error(src, q)
        // A 565 step is ~8/255 red and blue, ~4/255 green; an ordered dither should sit well inside one step.
        assertTrue("mean channel error $err is within a step", err < 4.0)
    }

    /** toGpu is what the loader calls: it must return an opaque 565 bitmap and leave the input usable. */
    @Test fun toGpuReturnsAnOpaque565Bitmap() {
        val src = gradient()
        val gpu = Dither.toGpu(src)
        assertTrue("expected RGB_565, got ${gpu.config}", gpu.config == Bitmap.Config.RGB_565)
        assertTrue("the backdrop must stay opaque", !gpu.hasAlpha())
        assertTrue("same size", gpu.width == src.width && gpu.height == src.height)
        // The input is left as the caller's to recycle, as everywhere else in the loader.
        assertTrue("input must not be recycled by toGpu", !src.isRecycled)
    }

    /** A hardware bitmap is immutable and skips the paint, but that must not throw. */
    @Test fun applyIsSafeOnABitmapItCannotTouch() {
        val src = gradient(64, 64)
        val hw = src.copy(Bitmap.Config.HARDWARE, false)   // HARDWARE is always immutable
        Dither.apply(hw)   // no crash, no change
        assertTrue(hw.width == 64)
    }
}
