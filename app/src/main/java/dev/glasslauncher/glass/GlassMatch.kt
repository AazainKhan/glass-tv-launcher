package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

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

    /** Cells across the sheet: about 6 dp each on the panel, a blur near the tray's clear glass. */
    const val COLS = 40

    /** A baked sheet: [pixels] (ARGB) of [width]×[height]. */
    class Sheet(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * A small blurred, tinted and dimmed picture of the rectangle [left],[top],[width],[height] (root pixels) of
     * [source], which covers the root: [cols] cells across, each the mean of a few samples, then one 3x3 box blur,
     * then [fill]. The scene stays visible: nothing is averaged towards one colour. Drawn stretched over the rectangle (bilinear), it is a soft sheet of the scene behind it.
     */
    fun panelSheet(source: Bitmap, rootWidth: Int, rootHeight: Int, left: Float, top: Float, width: Float, height: Float, tint: Color, dim: Float, cols: Int = COLS): Sheet? {
        if (rootWidth <= 0 || rootHeight <= 0 || width <= 0f || height <= 0f) return null
        val rows = (cols * height / width).toInt().coerceIn(2, 64)
        val sx = source.width / rootWidth.toFloat(); val sy = source.height / rootHeight.toFloat()
        // The crop of the panel from the (480x270) sample, read once in one call: about 70x160 px, so the whole
        // bake is a few thousand integer operations, not thousands of getPixel calls.
        val x0 = (left * sx).toInt().coerceIn(0, source.width - 1)
        val y0 = (top * sy).toInt().coerceIn(0, source.height - 1)
        val cw = (Math.ceil(((left + width) * sx).toDouble()).toInt().coerceIn(x0 + 1, source.width)) - x0
        val ch = (Math.ceil(((top + height) * sy).toDouble()).toInt().coerceIn(y0 + 1, source.height)) - y0
        val crop = IntArray(cw * ch)
        runCatching { source.getPixels(crop, 0, cw, x0, y0, cw, ch) }.onFailure { return null }
        val r = FloatArray(cols * rows); val g = FloatArray(cols * rows); val b = FloatArray(cols * rows)
        for (j in 0 until rows) {
            val ya = j * ch / rows; val yb = maxOf(ya + 1, (j + 1) * ch / rows)
            for (i in 0 until cols) {
                val xa = i * cw / cols; val xb = maxOf(xa + 1, (i + 1) * cw / cols)
                var rr = 0; var gg = 0; var bb = 0
                for (y in ya until yb) for (x in xa until xb) {
                    val p = crop[x + y * cw]
                    rr += p shr 16 and 0xFF; gg += p shr 8 and 0xFF; bb += p and 0xFF
                }
                val n = (yb - ya) * (xb - xa) * 255f
                val k = i + j * cols
                r[k] = rr / n; g[k] = gg / n; b[k] = bb / n
            }
        }
        fun blur(c: FloatArray) = FloatArray(c.size).also { out ->
            for (j in 0 until rows) for (i in 0 until cols) {
                var sum = 0f; var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val x = i + di; val y = j + dj
                    if (x in 0 until cols && y in 0 until rows) { sum += c[x + y * cols]; n++ }
                }
                out[i + j * cols] = sum / n
            }
        }
        val br = blur(r); val bg = blur(g); val bbl = blur(b)
        val pixels = IntArray(cols * rows) { Color(br[it], bg[it], bbl[it]).let { c -> fill(c, tint, dim).toArgb() } }
        return Sheet(pixels, cols, rows)
    }
}
