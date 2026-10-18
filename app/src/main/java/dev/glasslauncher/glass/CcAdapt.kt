package dev.glasslauncher.glass

import kotlin.math.pow

/**
 * Control Center's one material (P188, replacing P137's two modes): every pixel of the baked sheet is held at or
 * below relative luminance [CAP], whatever is behind it, so white labels keep at least 4.5:1 (they get 4.77:1) on a
 * white app as on a dark one. There is no light-scene mode and no dark-ink variant: P137's light branch floored the
 * sheet at 0.55 for dark ink, and once the ink was made white (P173) it put white labels on near-white glass.
 * All of it is arithmetic on the small baked sheet, off the main thread; nothing is sampled while drawing.
 */
object CcAdapt {
    /** The glass never rises above this relative luminance: white text keeps 4.5:1 (it gets 4.77:1). */
    const val CAP = 0.05f

    private val toLinear = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private fun toSrgb(l: Float): Int {
        val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f
        return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun relLuminance(argb: Int): Float =
        0.2126f * toLinear[(argb shr 16) and 0xFF] + 0.7152f * toLinear[(argb shr 8) and 0xFF] + 0.0722f * toLinear[argb and 0xFF]

    /** WCAG contrast ratio between two relative luminances. */
    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /** Composite [fg] (ARGB, any alpha) over opaque [bg]. */
    fun over(fg: Int, bg: Int): Int {
        val a = ((fg ushr 24) and 0xFF) / 255f
        fun ch(s: Int) = (((fg shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The one glass tone every tile settles on (a neutral dark grey, sRGB about 56): white text gets 5.8:1 on it. */
    const val TONE = 0.04f
    /** How much of the scene survives in the glass: a hint only, so every tile reads as the same colour (P188). */
    const val SCENE = 0.15f

    /**
     * One sheet pixel (opaque ARGB): held at or below [CAP], then mostly replaced by the neutral [TONE], keeping
     * [SCENE] of its brightness (in linear light) and none of its colour. Tiles over a dark navy patch and over a bright wall used to differ by 15-20 levels
     * though both were capped; now every tile is the same dark glass with a faint hint of what is behind it.
     */
    fun fit(argb: Int): Int {
        val y = relLuminance(argb)
        // The scene's brightness only (capped), never its colour: the glass stays neutral over any hue.
        val g = toSrgb(TONE * (1f - SCENE) + minOf(y, CAP) * SCENE)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}
