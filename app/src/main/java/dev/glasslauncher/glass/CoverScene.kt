package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader

/** The look of a Cover Flow background (P33), tuned by eye on the stick; see [coverScene]. */
internal object CoverLook {
    /** The scene is composed at half screen size: it is blurred and then scaled up anyway. */
    const val W = 960
    const val H = 540
    /** Very mild: blurred at a quarter of the screen, so details soften but the cover still reads. */
    const val BLUR_RADIUS = 3
    /** The sheen: white at the top fading to nothing a third of the way down. */
    const val GLOSS = 0.12f
    const val GLOSS_TO = 0.35f
    /** The darker bottom, under the titles: from the middle down to this much black at the bottom edge. */
    const val SHADE = 0.45f
    const val SHADE_FROM = 0.5f
}

/**
 * [cover] (square art) filling a 16:9 scene: centre-cropped to the screen's shape, softened, then a glossy sheen
 * over the top and a shade over the bottom half. The result is [CoverLook.W] x [CoverLook.H]; [cover] is untouched.
 */
internal fun coverScene(cover: Bitmap): Bitmap {
    val w = CoverLook.W; val h = CoverLook.H
    // Centre crop to 16:9: the cover's full width, its middle rows.
    val srcH = (cover.width * h / w).coerceAtMost(cover.height)
    val top = (cover.height - srcH) / 2
    val filled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(filled).drawBitmap(cover, Rect(0, top, cover.width, top + srcH), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    val soft = Blur.backdrop(filled, w / 2, h / 2, radius = CoverLook.BLUR_RADIUS, saturation = 1f)
    filled.recycle()
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(soft, Rect(0, 0, soft.width, soft.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    soft.recycle()
    canvas.drawRect(0f, 0f, w.toFloat(), h * CoverLook.GLOSS_TO, Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h * CoverLook.GLOSS_TO, argb(CoverLook.GLOSS, 255), argb(0f, 255), Shader.TileMode.CLAMP)
    })
    canvas.drawRect(0f, h * CoverLook.SHADE_FROM, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, h * CoverLook.SHADE_FROM, 0f, h.toFloat(), argb(0f, 0), argb(CoverLook.SHADE, 0), Shader.TileMode.CLAMP)
    })
    return out
}

private fun argb(alpha: Float, grey: Int) = ((alpha * 255).toInt() shl 24) or (grey shl 16) or (grey shl 8) or grey
