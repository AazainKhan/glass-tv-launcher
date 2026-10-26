package dev.glasslauncher.glass

import android.graphics.Bitmap

/**
 * Ordered dithering for the 16-bit (RGB_565) backdrop (P285).
 *
 * RGB_565 is 4.0 MB per 1080p frame against ARGB_8888's 8.3, and the backdrop bitmaps are already marked opaque, so
 * 565 costs nothing semantically - but it drops to 5 bits of red and blue, and a dark gradient (a wallpaper's sky, a
 * vignette) then steps in visible bands, which is exactly where a full-screen backdrop is looked at.
 *
 * The fix is the standard one: before quantising, nudge each channel by a fraction of one 565 step according to where
 * the pixel sits in an 8x8 Bayer matrix. The error is spread over neighbouring pixels instead of landing as a hard
 * edge, so the band boundaries read as texture. It is a few adds per pixel with no floating point, and it is applied
 * to the *software* bitmap just before the GPU copy, so nothing in the shader path changes.
 */
internal object Dither {

    /** 8x8 Bayer matrix, 0..63: the classic ordered-dither threshold map. */
    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * How hard to dither, as a fraction of one quantisation step. 1.0 spreads a full step (maximum smoothness, at the
     * cost of a little grain); 0 is plain truncation. Two thirds is the usual compromise and what the test pins.
     */
    const val AMOUNT = 2f / 3f

    /** One 565 step per channel: red and blue have 5 bits (32 levels), so a step is 8 of 255. */
    private const val STEP_RB = 255f / 31f
    private const val STEP_G = 255f / 63f

    /**
     * Applies the dither in place. A no-op on a hardware bitmap (immutable) or a non-ARGB_8888 one, so callers can
     * pass whatever they have.
     */
    fun apply(bitmap: Bitmap, amount: Float = AMOUNT) {
        if (!bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) return
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = (y and 7) shl 3
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                // (m + 0.5)/64 maps the matrix to -0.5..0.5, so the nudge is signed and averages out.
                val bias = ((BAYER[row + (x and 7)] + 0.5f) / 64f - 0.5f) * amount
                val r = clamp(((c shr 16) and 0xFF) + bias * STEP_RB)
                val g = clamp(((c shr 8) and 0xFF) + bias * STEP_G)
                val b = clamp((c and 0xFF) + bias * STEP_RB)
                px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else v.toInt()

    /**
     * An opaque GPU bitmap at [config], dithered first when going to 565. Returns the input when the copy fails, so a
     * caller never has to null-check. The software [bitmap] is recycled by the caller, as elsewhere in the loader.
     */
    fun toGpu(bitmap: Bitmap, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap {
        if (config == Bitmap.Config.RGB_565) apply(bitmap)
        return bitmap.copy(config, false) ?: bitmap
    }
}
