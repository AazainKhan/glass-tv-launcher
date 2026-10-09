package dev.glasslauncher.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Liquid Glass's lens edge without a shader language (API 30 has no RuntimeShader): a surface's patch of
 * the baked backdrop, warped once with [Canvas.drawBitmapMesh] so the band along each edge shows content
 * from just beyond it, compressed, curving back to flat glass toward the middle. The result is a small
 * bitmap (the backdrop is quarter resolution) that the surface then draws as its one fill, so the bend
 * costs nothing per frame; it is redrawn only when the surface's size, place or backdrop changes.
 */
object LensWarp {
    /**
     * Where texture position [t] (0 at [beyond] outside the surface's start, `length + 2*beyond` at the
     * far side) lands inside a surface [length] long. The middle maps 1:1; each [band] at the ends takes
     * the band plus everything [beyond] the edge, along a power curve whose slope meets 1 at the band's
     * inner edge, so there is no seam.
     */
    fun place(t: Float, length: Float, band: Float, beyond: Float): Float {
        val b = band.coerceAtMost(length * 0.3f)
        if (b <= 0f || beyond <= 0f) return t - beyond.coerceAtLeast(0f)
        val span = beyond + b
        val r = span / b
        val total = length + beyond * 2
        return when {
            t <= span -> b * (t.coerceAtLeast(0f) / span).pow(r)
            t >= total - span -> length - b * ((total - t).coerceAtLeast(0f) / span).pow(r)
            else -> t - beyond
        }
    }

    /** Reused buffers for one surface: the patch with its margin, and the warped result. */
    class Buffers {
        internal var patch: Bitmap? = null
        internal var out: Bitmap? = null
        internal var verts = FloatArray(0)
    }

    /**
     * The warped patch of [source] under a surface at ([left], [top]) sized [width]×[height], all in
     * [source]'s pixels. Edges past the backdrop's own edge repeat its last pixels.
     */
    fun render(source: Bitmap, left: Float, top: Float, width: Float, height: Float, band: Float, beyond: Float, buffers: Buffers): Bitmap {
        val pw = ceil(width + beyond * 2).toInt().coerceAtLeast(1)
        val ph = ceil(height + beyond * 2).toInt().coerceAtLeast(1)
        val ow = ceil(width).toInt().coerceAtLeast(1)
        val oh = ceil(height).toInt().coerceAtLeast(1)
        val patch = buffers.patch?.takeIf { it.width == pw && it.height == ph } ?: Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888).also { buffers.patch = it }
        val out = buffers.out?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { buffers.out = it }

        // The patch, beyond margin included (clamped at the backdrop's edges).
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(left - beyond), -(top - beyond)) })
        Canvas(patch).drawPaint(Paint().apply { this.shader = shader })

        // A mesh about four patch pixels per cell: smooth enough for the curve, cheap enough to redraw.
        val cols = (pw / 4).coerceIn(2, 200)
        val rows = (ph / 4).coerceIn(2, 100)
        val n = (cols + 1) * (rows + 1) * 2
        if (buffers.verts.size != n) buffers.verts = FloatArray(n)
        val v = buffers.verts
        var i = 0
        for (y in 0..rows) {
            val dy = place(ph * y / rows.toFloat(), height, band, beyond)
            for (x in 0..cols) {
                v[i++] = place(pw * x / cols.toFloat(), width, band, beyond)
                v[i++] = dy
            }
        }
        out.eraseColor(0)
        Canvas(out).drawBitmapMesh(patch, cols, rows, v, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
