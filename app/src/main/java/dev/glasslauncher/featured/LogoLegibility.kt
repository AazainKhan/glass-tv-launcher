package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import coil3.size.Size
import coil3.transform.Transformation
import dev.glasslauncher.glass.Blur
import kotlin.math.max

/**
 * Makes a title logo legible over any artwork, once at decode time. Logos keep their own colours (a
 * multicolour logo like Toy Story's became a white blob when every logo was tinted white); only
 * mostly-dark logos, which would vanish on dark art, are drawn white. Every logo gets a soft contact
 * shadow baked under it, so light logos still read on bright art. The bitmap grows by [pad] on each
 * side to hold the shadow.
 */
class LogoLegibility : Transformation() {
    override val cacheKey = "logo-legibility-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val src = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        val dark = isMostlyDark(src)
        val pad = max(4, src.height / 10)
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Shadow: the logo's silhouette in black, blurred, slightly below.
        val silhouette = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        Canvas(silhouette).drawBitmap(src, pad.toFloat(), pad.toFloat() + pad / 5f, Paint().apply {
            colorFilter = PorterDuffColorFilter(Color.argb(150, 0, 0, 0), PorterDuff.Mode.SRC_IN)
        })
        Blur.blurInPlace(silhouette, max(2, pad / 3))
        canvas.drawBitmap(silhouette, 0f, 0f, null)
        silhouette.recycle()
        canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (dark) colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        })
        if (src !== input) src.recycle()
        return out
    }

    /** Mean luminance of the logo's visible pixels below 0.3: black or near-black wordmarks. */
    private fun isMostlyDark(bitmap: Bitmap): Boolean {
        val step = max(1, max(bitmap.width, bitmap.height) / 120)
        var sum = 0.0
        var n = 0
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
            n++
        }
        return n > 0 && sum / n < 0.3
    }
}
