package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * tvOS's logo-only Top Shelf: the app's logo about half the screen wide, centred in the space above the
 * tray, on its own background colour. Built from any logo art (Amazon's 16:9 Fire TV icon, the app's TV
 * banner or its icon): the logo's ink is found against the art's edge colour, the frame is filled with
 * that colour, and the ink is drawn at tvOS's size, never stretched edge to edge.
 */
object LogoHero {
    private const val INK_WIDTH = 0.52f
    private const val INK_HEIGHT = 0.30f
    private const val CENTRE_Y = 0.38f
    /** How far (summed RGB) from the edge colour a pixel must be to count as logo: gradients stay background. */
    private const val INK_DISTANCE = 110

    fun compose(art: Bitmap, w: Int = 1280, h: Int = 720): Bitmap {
        val bg = edgeColour(art)
        val ink = inkBounds(art, bg) ?: Rect(0, 0, art.width, art.height)
        val scale = minOf(w * INK_WIDTH / ink.width(), h * INK_HEIGHT / ink.height())
        val dw = ink.width() * scale; val dh = ink.height() * scale
        val left = (w - dw) / 2f; val top = h * CENTRE_Y - dh / 2f
        // The logo's own background (often a gradient, as Stremio's) fades into the fill instead of showing
        // as a box: pixels close to the edge colour turn transparent, softly.
        val logo = Bitmap.createBitmap(art, ink.left, ink.top, ink.width(), ink.height()).copy(Bitmap.Config.ARGB_8888, true)
        keyOut(logo, bg)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(bg)
            drawBitmap(logo, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        logo.recycle()
        return out
    }

    /** The art's background: the average of its opaque border pixels (rounded transparent corners skipped). */
    fun edgeColour(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        fun add(c: Int) { if (Color.alpha(c) > 200) { r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++ } }
        val stepX = maxOf(1, b.width / 64); val stepY = maxOf(1, b.height / 36)
        for (x in 0 until b.width step stepX) { add(b.getPixel(x, 1)); add(b.getPixel(x, b.height - 2)) }
        for (y in 0 until b.height step stepY) { add(b.getPixel(1, y)); add(b.getPixel(b.width - 2, y)) }
        return if (n == 0) Color.rgb(20, 22, 28) else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun keyOut(b: Bitmap, bg: Int) {
        // Opaque art (Amazon's icons are RGB) copies as "no alpha": without this the keyed pixels were drawn
        // as if opaque, as dark boxes and leftover memory.
        b.setHasAlpha(true)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) {
            val c = px[i]
            val d = Math.abs(Color.red(c) - Color.red(bg)) + Math.abs(Color.green(c) - Color.green(bg)) + Math.abs(Color.blue(c) - Color.blue(bg))
            val a = ((d - 45f) / 80f).coerceIn(0f, 1f)
            px[i] = (c and 0x00FFFFFF) or (((Color.alpha(c) * a).toInt()) shl 24)
        }
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }

    /** Where the logo is: the box around pixels that differ clearly from [bg] (transparent counts as bg). */
    private fun inkBounds(b: Bitmap, bg: Int): Rect? {
        val step = maxOf(1, b.width / 320)
        var l = b.width; var t = b.height; var r = -1; var bo = -1
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            val d = Math.abs(Color.red(c) - Color.red(bg)) + Math.abs(Color.green(c) - Color.green(bg)) + Math.abs(Color.blue(c) - Color.blue(bg))
            if (d > INK_DISTANCE) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bo) bo = y }
        }
        if (r < l || bo < t) return null
        val pad = step * 2
        return Rect((l - pad).coerceAtLeast(0), (t - pad).coerceAtLeast(0), (r + pad).coerceAtMost(b.width), (bo + pad).coerceAtMost(b.height))
    }
}
