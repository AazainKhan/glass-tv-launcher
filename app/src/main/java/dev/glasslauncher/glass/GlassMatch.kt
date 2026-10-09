package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The flat colour a surface over the scene takes: the scene behind it (averaged), under a tint, then the
 * scene's dim. Control Center computes it once per open for the whole panel and draws every tile, and the
 * bubble that grows into the panel, in that one colour (no texture to fade in, nothing sampled per frame).
 */
object GlassMatch {
    fun fill(average: Color, tint: Color, dim: Float): Color {
        val tinted = tint.compositeOver(average.copy(alpha = 1f))
        return Color.Black.copy(alpha = dim.coerceIn(0f, 1f)).compositeOver(tinted)
    }

    /** Mean colour of [source] in the rectangle [left]..[left]+[width] × [top]..[top]+[height] (source pixels), from a 7×7 sample. */
    fun average(source: Bitmap, left: Float, top: Float, width: Float, height: Float): Color {
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (j in 0 until 7) for (i in 0 until 7) {
            val x = (left + width * (i + 0.5f) / 7f).toInt().coerceIn(0, source.width - 1)
            val y = (top + height * (j + 0.5f) / 7f).toInt().coerceIn(0, source.height - 1)
            val p = source.getPixel(x, y)
            r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); n++
        }
        return Color(r / n / 255f, g / n / 255f, b / n / 255f)
    }

    /**
     * [fill] for the screen rectangle [left],[top],[width],[height] (root pixels, [rootWidth] by [rootHeight]),
     * averaged from [source], which covers the whole root at its own resolution. Null if it can't be sampled.
     */
    fun regionFill(source: Bitmap, rootWidth: Int, rootHeight: Int, left: Float, top: Float, width: Float, height: Float, tint: Color, dim: Float): Color? {
        if (rootWidth <= 0 || rootHeight <= 0 || width <= 0f || height <= 0f) return null
        val sx = source.width / rootWidth.toFloat()
        val sy = source.height / rootHeight.toFloat()
        return runCatching { fill(average(source, left * sx, top * sy, width * sx, height * sy), tint, dim) }.getOrNull()
    }
}
