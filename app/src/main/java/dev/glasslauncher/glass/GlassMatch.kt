package dev.glasslauncher.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

/**
 * Control Center's material: the scene behind the panel, under a tint, then the scene's dim ([fill]), baked as a
 * small soft sheet ([ccSheet]) with each backdrop, so every tile and the bubble draw their part of one picture and
 * nothing is sampled when Control Center opens or while it animates.
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

    /** A baked sheet: [pixels] (ARGB) of [width]×[height]. */
    class Sheet(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Control Center's material, baked with the backdrop: [bitmap] covers the screen rectangle [left]..[right] ×
     * [top]..[bottom] (fractions of the root). [pixels] is kept only for tests ([keepPixels]).
     */
    class PanelSheet(val bitmap: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float, val pixels: Sheet?)

    /** Tests only: how many sheets have been baked (Control Center's open must not add one). */
    @androidx.annotation.VisibleForTesting @Volatile var sheetBakes = 0
    /** Tests only: keep each [PanelSheet]'s pixels (release builds keep just the small GPU bitmap). */
    @androidx.annotation.VisibleForTesting @Volatile var keepPixels = false

    /** Control Center's tint over the scene in dark appearance: a faint darkening for the labels. */
    val CC_TINT = Color.Black.copy(alpha = 0.06f)
    /** In light appearance: a white veil (20%, as before the shared sheet) so the glass reads lighter; Control Center keeps white text on it, so the dim is baked in as well. */
    val CC_TINT_LIGHT = Color.White.copy(alpha = 0.2f)
    /** How much Control Center mutes the screen behind it; its sheet is the scene muted by the same amount. */
    const val CC_DIM = 0.42f
    /**
     * The screen region the sheet covers (fractions of the root): the right half, full height. Control Center's
     * panel sits top-right and is never wider than this at any text size (about 40% at the largest), so one
     * sheet serves every page and every text size, and nothing about the panel needs to be known when it is baked.
     */
    const val CC_LEFT = 0.5f
    /** Cells across [CC_LEFT]..1: about 6 dp each, as Control Center's panel had. */
    const val CC_COLS = 80

    /**
     * Control Center's sheet, baked from a backdrop's clear texture [clear] (which covers the root) when the
     * backdrop is baked, off the main thread: [panelSheet] over the right half, uploaded as a small hardware
     * bitmap (80x90, ~28 KB). Opening Control Center then only maps it; null if [clear] can't be read.
     */
    fun ccSheet(clear: Bitmap, light: Boolean = false): PanelSheet? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val w = clear.width.toFloat(); val h = clear.height.toFloat()
        val sheet = panelSheet(clear, clear.width, clear.height, w * CC_LEFT, 0f, w * (1f - CC_LEFT), h, if (light) CC_TINT_LIGHT else CC_TINT, CC_DIM, CC_COLS) ?: return null
        val soft = Bitmap.createBitmap(sheet.pixels, sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
        val bitmap = soft.copy(Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft
        // Fire OS drops Log.d from apps, so debug builds log at info level.
        if (dev.glasslauncher.BuildConfig.DEBUG) android.util.Log.i("CcMaterial", "sheet bake ${android.os.SystemClock.elapsedRealtime() - t0}ms (${sheet.width}x${sheet.height}, ${sheet.pixels.size * 4} bytes) on ${Thread.currentThread().name}")
        return PanelSheet(bitmap, CC_LEFT, 0f, 1f, 1f, sheet.takeIf { keepPixels })
    }

    /**
     * A small blurred, tinted and dimmed picture of the rectangle [left],[top],[width],[height] (root pixels) of
     * [source], which covers the root: [cols] cells across, each the mean of a few samples, then one 3x3 box blur,
     * then [fill]. The scene stays visible: nothing is averaged towards one colour. Drawn stretched over the rectangle (bilinear), it is a soft sheet of the scene behind it.
     */
    fun panelSheet(source: Bitmap, rootWidth: Int, rootHeight: Int, left: Float, top: Float, width: Float, height: Float, tint: Color, dim: Float, cols: Int = 40): Sheet? {
        if (rootWidth <= 0 || rootHeight <= 0 || width <= 0f || height <= 0f) return null
        sheetBakes++
        val rows = (cols * height / width).toInt().coerceIn(2, 128)
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
