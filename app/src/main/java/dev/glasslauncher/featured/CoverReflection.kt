package dev.glasslauncher.featured

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

/**
 * A cover with its reflection baked under it, once: the picture on top and, below, a mirrored copy fading to
 * nothing over [HEIGHT] of the cover's height. Drawing the result is one image, so the flow needs no per-frame
 * layers or blurs for it.
 */
object CoverReflection {
    /** The reflection's height as a fraction of the cover's. */
    const val HEIGHT = 0.3f
    /** How visible the reflection is where it meets the cover. */
    const val STRENGTH = 0.38f

    /** [source] cropped to a centred square (a non-square image in a square slot is cropped, not stretched). */
    fun squared(source: Bitmap): Bitmap {
        if (source.width == source.height) return source
        val side = minOf(source.width, source.height)
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    }

    fun bake(cover: Bitmap): Bitmap {
        val w = cover.width
        val h = cover.height
        val r = (h * HEIGHT).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h + r, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cover, 0f, 0f, null)
        // The mirrored strip, faded by a gradient on its own layer.
        val layer = canvas.saveLayer(0f, h.toFloat(), w.toFloat(), (h + r).toFloat(), null)
        val flip = Matrix().apply { postScale(1f, -1f); postTranslate(0f, 2f * h) }
        canvas.save()
        canvas.clipRect(0f, h.toFloat(), w.toFloat(), (h + r).toFloat())
        canvas.drawBitmap(cover, flip, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, h.toFloat(), 0f, (h + r).toFloat(),
                ((STRENGTH * 255).toInt() shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, h.toFloat(), w.toFloat(), (h + r).toFloat(), fade)
        canvas.restoreToCount(layer)
        return out
    }
}
