package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Bakes the hero's fade-out edges into the decoded bitmap once, so drawing and cross-fading the
 * hero each frame is a plain bitmap draw instead of two full-screen offscreen blend passes.
 */
class HeroMask : Transformation() {
    override val cacheKey = "hero-mask-v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(input, 0f, 0f, null)
        val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            xfermode = dstIn
            shader = LinearGradient(0f, h * 0.5f, 0f, h.toFloat(), Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            xfermode = dstIn
            shader = LinearGradient(0f, 0f, w * 0.4f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        })
        return out
    }
}
