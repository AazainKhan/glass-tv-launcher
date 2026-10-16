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
    class Sheet(val pixels: IntArray, val width: Int, val height: Int, val lightScene: Boolean = false)

    /**
     * Control Center's material, baked with the backdrop: [bitmap] covers the screen rectangle [left]..[right] ×
     * [top]..[bottom] (fractions of the root). [pixels] is kept only for tests ([keepPixels]).
     */
    class PanelSheet(val bitmap: Bitmap, val left: Float, val top: Float, val right: Float, val bottom: Float, val pixels: Sheet?, val luma: Float = 0f, val lightScene: Boolean = false)

    /** Mean luminance (0..1) of ARGB [px]: read at bake time, since a HARDWARE bitmap cannot be read back. */
    internal fun meanLuma(px: IntArray): Float {
        if (px.isEmpty()) return 0f
        var sum = 0.0
        for (c in px) sum += 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        return (sum / (px.size * 255.0)).toFloat()
    }

    /** Tests only: how many sheets have been baked (Control Center's open must not add one). */
    @androidx.annotation.VisibleForTesting @Volatile var sheetBakes = 0
    /** Tests only: keep each [PanelSheet]'s pixels (release builds keep just the small GPU bitmap). */
    @androidx.annotation.VisibleForTesting @Volatile var keepPixels = false

    /** Control Center's tint over the scene in dark appearance: a faint darkening for the labels. */
    val CC_TINT = Color.Black.copy(alpha = 0.06f)
    /** In light appearance: a white veil (20%, as before the shared sheet) so the glass reads lighter; Control Center keeps white text on it, so the dim is baked in as well. */
    val CC_TINT_LIGHT = Color.White.copy(alpha = 0.2f)
    /**
     * Liquid-glass body (P76), all in the bake, so the draw costs nothing: the scene is blurred [CC_BLUR_PASSES]
     * times (busy art, a Cover Flow, becomes soft colour fields, not shapes), its colour boosted by [CC_SATURATION]
     * around its own luma (vibrancy, as tvOS's glass), and lifted toward white by [CC_LIFT] so
     * the tiles read as translucent glass, not near-black slabs. The sheet stays one opaque picture, so tiles
     * remain "the sheet at their position" (VisualChecks.controlCenterIsOneMaterial). A departure from P10's
     * flat dimmed scene, on purpose (the user: "too sharp", "more liquid glass bubble").
     */
    const val CC_BLUR_PASSES = 10
    const val CC_SATURATION = 1.35f
    const val CC_LIFT = 0.14f
    /** Light appearance's lift: its tiles must stay clearly brighter than the dark ones (ControlCenterMotionTest). */
    const val CC_LIFT_LIGHT = 0.26f

    /**
     * How much Control Center mutes the screen behind it (the scrim). The controls do NOT sample the dimmed scene: their
     * sheet is the undimmed art (iOS 27's Regular material), so they stay lighter than what is behind them (F02).
     */
    const val CC_DIM = 0.42f
    /** What the sheet itself is dimmed by: nothing (the tint and lift keep labels legible). */
    const val CC_SHEET_DIM = 0f
    /**
     * Light appearance carries white labels on a light material, and ControlCenterMotionTest holds them at 4.5:1: 0.2 and
     * 0.31 both fell short (4.29:1 at 0.31), so it keeps the scene's whole dim baked in. The undimmed sheet is dark only
     * until the labels go dark on light glass (as tvOS's light appearance does).
     */
    const val CC_SHEET_DIM_LIGHT = 0.38f
    /** Light appearance mutes less (tvOS: x0.69 light, x0.57 dark; P105 #14). 0.31 is tvOS but left white text on light tiles at 4.29:1, under the 4.5:1 test, so 0.38. */
    const val CC_DIM_LIGHT = 0.38f
    /**
     * The screen region the sheet covers (fractions of the root): from a quarter of the width to the right edge, full
     * height. Control Center's panel sits top-right (never wider than 40% at the largest text), and edit mode (P101)
     * moves it to the middle of the screen, so one sheet serves both places, every page and every text size, and
     * nothing about the panel needs to be known when it is baked.
     */
    const val CC_LEFT = 0.25f
    /** Cells across [CC_LEFT]..1: about 6 dp each, as Control Center's panel had. */
    const val CC_COLS = 120

    /**
     * Control Center's sheet, baked from a backdrop's clear texture [clear] (which covers the root) when the
     * backdrop is baked, off the main thread: [panelSheet] over the right half, uploaded as a small hardware
     * bitmap (80x90, ~28 KB). Opening Control Center then only maps it; null if [clear] can't be read.
     */
    fun ccSheet(clear: Bitmap, @Suppress("UNUSED_PARAMETER") theme: Boolean = false): PanelSheet? {
        // P173 (user): Control Center has ONE style, the clear glass with white labels, in Light and Dark alike. The sheet
        // still adapts to a light scene (it darkens enough for white text), but never takes the light theme's material.
        val light = false
        val t0 = android.os.SystemClock.elapsedRealtime()
        val w = clear.width.toFloat(); val h = clear.height.toFloat()
        val sheet = panelSheet(clear, clear.width, clear.height, w * CC_LEFT, 0f, w * (1f - CC_LEFT), h, if (light) CC_TINT_LIGHT else CC_TINT, if (light) CC_SHEET_DIM_LIGHT else CC_SHEET_DIM, CC_COLS,
            blurPasses = CC_BLUR_PASSES, saturation = CC_SATURATION, lift = if (light) CC_LIFT_LIGHT else CC_LIFT, adapt = true,
            darkCap = if (light) CcAdapt.DARK_CAP_LIGHT_THEME else CcAdapt.DARK_CAP) ?: return null
        val soft = Bitmap.createBitmap(sheet.pixels, sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
        val bitmap = soft.copy(Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft
        // Fire OS drops Log.d from apps, so debug builds log at info level.
        if (dev.glasslauncher.BuildConfig.DEBUG) android.util.Log.i("CcMaterial", "sheet bake ${android.os.SystemClock.elapsedRealtime() - t0}ms (${sheet.width}x${sheet.height}, ${sheet.pixels.size * 4} bytes) on ${Thread.currentThread().name}")
        return PanelSheet(bitmap, CC_LEFT, 0f, 1f, 1f, sheet.takeIf { keepPixels }, meanLuma(sheet.pixels), sheet.lightScene)
    }

    /**
     * A small blurred, tinted and dimmed picture of the rectangle [left],[top],[width],[height] (root pixels) of
     * [source], which covers the root: [cols] cells across, each the mean of a few samples, then one 3x3 box blur,
     * then [fill]. The scene stays visible: nothing is averaged towards one colour. Drawn stretched over the rectangle (bilinear), it is a soft sheet of the scene behind it.
     */
    fun panelSheet(
        source: Bitmap, rootWidth: Int, rootHeight: Int, left: Float, top: Float, width: Float, height: Float, tint: Color, dim: Float, cols: Int = 40,
        blurPasses: Int = 1, saturation: Float = 1f, lift: Float = 0f, adapt: Boolean = false, darkCap: Float = CcAdapt.DARK_CAP,
    ): Sheet? {
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
        var br = r; var bg = g; var bbl = b
        repeat(blurPasses.coerceAtLeast(1)) { br = blur(br); bg = blur(bg); bbl = blur(bbl) }
        // P137: the scene's own mean luminance (before tint, dim and lift) picks the mode, so the glass and its labels
        // follow the backdrop (CcAdapt).
        var sceneSum = 0.0
        for (k in br.indices) sceneSum += 0.2126f * br[k] + 0.7152f * bg[k] + 0.0722f * bbl[k]
        val lightScene = adapt && CcAdapt.isLightScene((sceneSum / br.size).toFloat())
        val pixels = IntArray(cols * rows) {
            // Colour boosted around its own luma, then the usual tint and dim, then the lift toward white.
            val y = 0.2126f * br[it] + 0.7152f * bg[it] + 0.0722f * bbl[it]
            val base = Color(
                (y + (br[it] - y) * saturation).coerceIn(0f, 1f),
                (y + (bg[it] - y) * saturation).coerceIn(0f, 1f),
                (y + (bbl[it] - y) * saturation).coerceIn(0f, 1f),
            )
            val filled = fill(base, tint, dim)
            val argb = (if (lift > 0f) Color.White.copy(alpha = lift).compositeOver(filled) else filled).toArgb()
            if (adapt) CcAdapt.fit(argb, lightScene, darkCap) else argb
        }
        return Sheet(pixels, cols, rows, lightScene)
    }
}
