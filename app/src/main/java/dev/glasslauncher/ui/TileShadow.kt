package dev.glasslauncher.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The shadows under app tiles, as tvOS 27 casts them: a wide soft one that drops away as a tile takes
 * focus, and a tight one that holds a resting tile just above the backdrop. Under both sits a glow in the
 * tile's own colour (tvOS blurs a copy of the tile's art there), strong under saturated tiles and absent under white ones.
 *
 * Each is a rounded rectangle blurred once with an exact Gaussian and drawn scaled with the tile
 * (a blur animated per frame would re-tessellate on the render thread). Sizes are in "u", tvOS's 1080p
 * pixels with a tile 250 u wide, so the same numbers hold at any tile size: draw at `k = tile width / 250`
 * pixels per u. The shadows are black and the glow white, only alpha varying (the glow is tinted to the tile
 * when drawn); their peak strength is applied when drawing.
 *
 * The blur is done here, not by [dev.glasslauncher.glass.Blur]: its radius means a different thing on the
 * stick's GPU path than on the JVM fallback, so a profile checked in a unit test would not be the one on screen.
 */
object TileShadow {
    /** How much bigger than its resting size a focused tile is drawn (tvOS 27). */
    const val FOCUS_SCALE = 1.224f

    /**
     * One baked shadow. The shape is [coreW] x [coreH] u with corner [radius], blurred with a Gaussian of
     * [sigma], centred in a bitmap with [margin] on every side so the halo is never clipped. One bitmap
     * pixel covers [unitsPerPx] u. Drawn at [peak] strength and [drop] u below the tile's centre.
     */
    enum class Kind(
        val coreW: Float,
        val coreH: Float,
        val radius: Float,
        val sigma: Float,
        val margin: Float,
        val unitsPerPx: Float,
        val peak: Float,
        val drop: Float,
    ) {
        /** The tile's lifted shadow: 40 u lower, 0.35 dark at the tile's bottom edge, gone about 100 u below it. */
        Focus(306f, 184f, 36.7f, 25f, 100f, 2f, 0.35f, 40f),

        /** A resting tile's shadow: 6 u lower, 0.10 dark, gone about 14 u below the tile. */
        Contact(250f, 150f, 30f, 2.5f, 20f, 1f, 0.10f, 6f),

        /** The tile-coloured glow: 10 u lower, up to 0.30 strong (see [glowAlpha]), at rest and focused alike. */
        Glow(250f, 150f, 30f, 5f, 20f, 1f, 0.30f, 10f),
    }

    val focus: ImageBitmap by lazy { bake(Kind.Focus) }
    val contact: ImageBitmap by lazy { bake(Kind.Contact) }
    val glow: ImageBitmap by lazy { bake(Kind.Glow) }

    /** The draw alpha for [kind] at focus value [lift] (0 resting, 1 focused; springs overshoot, so it is clamped). */
    fun alpha(kind: Kind, lift: Float): Float {
        val l = lift.coerceIn(0f, 1f)
        return kind.peak * when (kind) {
            Kind.Focus -> l
            Kind.Contact -> 1f - l
            Kind.Glow -> 1f
        }
    }

    /**
     * How strongly a tile of [color] glows: [Kind.Glow]'s peak for a bright, saturated colour, fading to nothing
     * for white, grey and black, and only part way for dark colours (HSV saturation times brightness over 0.4).
     */
    fun glowAlpha(color: Color): Float {
        val hi = max(color.red, max(color.green, color.blue))
        val lo = min(color.red, min(color.green, color.blue))
        val saturation = if (hi > 0f) (hi - lo) / hi else 0f
        return Kind.Glow.peak * saturation * min(1f, hi / 0.4f)
    }

    /**
     * Where to draw [kind]'s bitmap, in px from the tile's top-left corner, for a tile [tileW] x [tileH] px
     * at animated [scale] and focus value [lift], with [k] px per u. The bitmap's core always equals the tile's
     * current scaled size (the focus shadow is baked at [FOCUS_SCALE] times a resting tile), and it is centred
     * on the tile, [Kind.drop] u lower (the focus shadow's drop grows with [lift]). The bitmaps are 5:3 like
     * Home's tiles; for a tile of another aspect (16:9 shelf cards) the height follows the tile's, so the core
     * still equals the tile. An empty tile (or a [k] that isn't a positive number) has no shadow: [Rect.Zero].
     */
    fun destRect(kind: Kind, tileW: Float, tileH: Float, scale: Float, lift: Float, k: Float): Rect {
        if (!(k > 0f && tileW > 0f && tileH > 0f) || !k.isFinite() || !tileW.isFinite() || !tileH.isFinite()) return Rect.Zero
        val pxPerU = if (kind == Kind.Focus) k * scale / FOCUS_SCALE else k * scale
        val w = (kind.coreW + 2 * kind.margin) * pxPerU
        val h = (kind.coreH + 2 * kind.margin) * pxPerU * (tileH / (Kind.Contact.coreH * k))
        val cx = tileW / 2
        val cy = tileH / 2 + kind.drop * k * if (kind == Kind.Focus) lift else 1f
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /**
     * Rasterises the rounded rectangle (anti-aliased from its signed distance) and blurs it with a separable
     * Gaussian whose kernel reaches 3 sigma, treating everything outside the bitmap as empty. Only the rows and
     * columns the shape can reach are visited, which keeps the focus bake near 4M multiply-adds.
     */
    private fun bake(kind: Kind): ImageBitmap {
        val upp = kind.unitsPerPx
        val w = ((kind.coreW + 2 * kind.margin) / upp).roundToInt()
        val h = ((kind.coreH + 2 * kind.margin) / upp).roundToInt()
        val cx = w * upp / 2
        val cy = h * upp / 2
        val halfW = kind.coreW / 2
        val halfH = kind.coreH / 2

        // The shape's pixel extent, and the kernel.
        val x0 = max(0, floor((cx - halfW) / upp).toInt())
        val x1 = min(w, ceil((cx + halfW) / upp).toInt())
        val y0 = max(0, floor((cy - halfH) / upp).toInt())
        val y1 = min(h, ceil((cy + halfH) / upp).toInt())
        val sigmaPx = kind.sigma / upp
        val reach = ceil(3 * sigmaPx).toInt()
        val kernel = FloatArray(2 * reach + 1) { exp(-((it - reach) * (it - reach)) / (2 * sigmaPx * sigmaPx)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val bx0 = max(0, x0 - reach)
        val bx1 = min(w, x1 + reach)
        val by0 = max(0, y0 - reach)
        val by1 = min(h, y1 + reach)

        // Coverage of each pixel by the rounded rectangle, from the distance of its centre to the edge.
        val shape = FloatArray(w * h)
        val straightX = halfW - kind.radius
        val straightY = halfH - kind.radius
        for (y in y0 until y1) {
            val qy = abs((y + 0.5f) * upp - cy) - straightY
            for (x in x0 until x1) {
                val qx = abs((x + 0.5f) * upp - cx) - straightX
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val distance = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - kind.radius
                shape[y * w + x] = (0.5f - distance / upp).coerceIn(0f, 1f)
            }
        }

        // Horizontal pass over the shape's rows, then vertical over every row the halo reaches.
        val across = FloatArray(w * h)
        for (y in y0 until y1) {
            val row = y * w
            for (x in bx0 until bx1) {
                var sum = 0f
                for (i in max(x - reach, x0)..min(x + reach, x1 - 1)) sum += shape[row + i] * kernel[i - x + reach]
                across[row + x] = sum
            }
        }
        val rgb = if (kind == Kind.Glow) 0xFFFFFF else 0
        val pixels = IntArray(w * h)
        val column = FloatArray(w)
        for (y in by0 until by1) {
            column.fill(0f, bx0, bx1)
            for (i in max(y - reach, y0)..min(y + reach, y1 - 1)) {
                val weight = kernel[i - y + reach]
                val row = i * w
                for (x in bx0 until bx1) column[x] += across[row + x] * weight
            }
            for (x in bx0 until bx1) pixels[y * w + x] = ((column[x] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or rgb
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap.asImageBitmap()
    }
}
