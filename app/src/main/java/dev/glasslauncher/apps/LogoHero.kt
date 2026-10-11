package dev.glasslauncher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.glasslauncher.glass.ImageGate

/**
 * tvOS's logo-only Top Shelf: the app's logo about half the screen wide, centred in the space above the
 * tray, on its own background colour. Built from any logo art (Amazon's 16:9 Fire TV icon, the app's TV
 * banner or its icon): the logo's ink is found against the art's edge colour, the frame is filled with
 * that colour, and the ink is drawn at tvOS's size, never stretched edge to edge.
 */
object LogoHero {
    private const val INK_WIDTH = 0.52f
    private const val INK_HEIGHT = 0.30f
    /** Where the logo's centre sits, as a fraction of the height: low in the room above the tray (user, 2026-10-09: 0.38 read too high). */
    const val CENTRE_Y = 0.44f
    /** How far (summed RGB) from the edge colour a pixel must be to count as logo: gradients stay background. */
    private const val INK_DISTANCE = 110

    /** What the quality gate (P81) makes of a logo: how big it may be drawn, and which level that is. */
    data class Plan(val scale: Float, val level: ImageGate.Level)

    /**
     * Pure: a logo whose ink is [inkW]x[inkH] art pixels, on a [w]x[h] hero, where one art pixel is
     * 1/[nativeFactor] native pixels (null: vector or unknown, which scales cleanly). Sharp when it can be drawn at
     * the size tvOS wants without enlarging the native pixels more than [ImageGate.LIMIT]; Soft when it has to be
     * drawn smaller than that (still sharp, on a colour field made from the art); Plain when the native art is
     * icon-sized: the logo is still drawn, at no more than [ImageGate.LIMIT] times its native pixels and centred, on a
     * plain gradient from the app's colour (the app keeps its identity; P81 stick finding, Stremio).
     */
    fun plan(inkW: Int, inkH: Int, w: Int, h: Int, nativeFactor: Float?): Plan {
        val wanted = minOf(w * INK_WIDTH / inkW.coerceAtLeast(1), h * INK_HEIGHT / inkH.coerceAtLeast(1))
        if (nativeFactor == null) return Plan(wanted, ImageGate.Level.Sharp)
        val nativeW = inkW / nativeFactor; val nativeH = inkH / nativeFactor
        val most = ImageGate.LIMIT / nativeFactor
        if (maxOf(nativeW, nativeH) < ICON_INK) return Plan(minOf(wanted, most), ImageGate.Level.Plain)
        return if (wanted <= most) Plan(wanted, ImageGate.Level.Sharp) else Plan(most, ImageGate.Level.Soft)
    }

    /** Below this many native pixels the logo's ink is icon-sized: never drawn enlarged. */
    private const val ICON_INK = 110

    /**
     * [nativeFactor]: art pixels per native pixel when [art] is a raster picture drawn larger than it was made
     * (a 320 px banner drawn into a 960 px bitmap is 3); null for vectors and art at its own size. The logo is drawn
     * at most [ImageGate.LIMIT] times its native size (a colour field made from the art around it when that is
     * smaller than wanted), or not at all when it is icon-sized (the app's colour alone).
     */
    fun compose(art: Bitmap, w: Int = 1280, h: Int = 720, nativeFactor: Float? = null): Bitmap {
        val bg = edgeColour(art)
        val ink = inkBounds(art, bg) ?: Rect(0, 0, art.width, art.height)
        val plan = plan(ink.width(), ink.height(), w, h, nativeFactor)
        val scale = plan.scale
        val dw = ink.width() * scale; val dh = ink.height() * scale
        val left = (w - dw) / 2f; val top = h * CENTRE_Y - dh / 2f
        // The logo's own background (often a gradient, as Stremio's) fades into the fill instead of showing
        // as a box: pixels close to the edge colour turn transparent, softly.
        val logo = Bitmap.createBitmap(art, ink.left, ink.top, ink.width(), ink.height()).copy(Bitmap.Config.ARGB_8888, true)
        keyOut(logo, bg)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var plain: Bitmap? = null
        Canvas(out).apply {
            // Sharp: the art's own colour. Soft: a colour-rich field made from the art itself, so a small logo sits
            // on something with depth rather than on a flat plate.
            when (plan.level) {
                ImageGate.Level.Soft -> drawBitmap(softField(art, bg, w, h), 0f, 0f, null)
                ImageGate.Level.Plain -> drawBitmap(plainField(art, bg, w, h).also { plain = it }, 0f, 0f, null)
                else -> drawColor(bg)
            }
            drawBitmap(logo, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        logo.recycle()
        plain?.recycle()
        return out
    }

    /** The art blurred and boosted into a smooth field the size of the hero, tinted toward its edge colour. */
    private fun softField(art: Bitmap, bg: Int, w: Int, h: Int): Bitmap {
        val field = dev.glasslauncher.glass.Blur.backdrop(art, w / 4, h / 4, radius = 8, saturation = 1.3f)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawBitmap(field, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            drawColor(Color.argb(110, Color.red(bg), Color.green(bg), Color.blue(bg)))
        }
        field.recycle()
        return out
    }

    /** No usable art: a quiet vertical gradient from the app's own colour (lighter above, deeper below). */
    private fun plainField(art: Bitmap, bg: Int, w: Int, h: Int): Bitmap {
        val top = Color.rgb(minOf(255, Color.red(bg) + 28), minOf(255, Color.green(bg) + 28), minOf(255, Color.blue(bg) + 28))
        val bottom = Color.rgb((Color.red(bg) * 0.7f).toInt(), (Color.green(bg) * 0.7f).toInt(), (Color.blue(bg) * 0.7f).toInt())
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
            })
        }
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
